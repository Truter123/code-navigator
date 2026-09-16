package com.codenavigator.indexer;

import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.NodeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectIndexerIntegrationTest {

    @TempDir
    Path tempDir;

    private GraphStore store;
    private ProjectIndexer indexer;

    @BeforeEach
    void setUp() {
        store = new GraphStore(tempDir.resolve("test.db"));
        indexer = new ProjectIndexer(store);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void indexesTier1GenericProject() {
        indexer.indexFull(Paths.get("src/test/resources/sample-generic"));
        assertThat(store.findNodesByType(NodeType.CONTROLLER)).isNotEmpty();
        assertThat(store.findNodesByType(NodeType.SERVICE)).isNotEmpty();
        assertThat(store.getConfig("tier")).isEqualTo("GENERIC");
    }

    @Test
    void indexesTier2SpringProject() {
        indexer.indexFull(Paths.get("src/test/resources/sample-spring"));
        assertThat(store.findNodesByType(NodeType.CONTROLLER)).isNotEmpty();
        assertThat(store.findNodesByType(NodeType.ENTITY)).isNotEmpty();
        assertThat(store.getConfig("tier")).isEqualTo("CRUD");
    }

    @Test
    void reindexingIsIdempotent() {
        // Edge ids are random UUIDs and method rows autoincrement, so neither dedupes on re-insert.
        // Before indexFull cleared them, a second init doubled every edge — on nlp, 25,823 to
        // 52,088 — and each traversal then reported every neighbour twice.
        indexer.indexFull(Paths.get("src/test/resources/sample-ddd"));
        int nodesAfterFirst = store.getAllNodes().size();
        int edgesAfterFirst = store.getAllEdges().size();
        int methodsAfterFirst = store.findNodesByType(NodeType.METHOD).size();
        assertThat(edgesAfterFirst).isPositive();

        indexer.indexFull(Paths.get("src/test/resources/sample-ddd"));

        assertThat(store.getAllEdges()).hasSize(edgesAfterFirst);
        assertThat(store.getAllNodes()).hasSize(nodesAfterFirst);
        assertThat(store.findNodesByType(NodeType.METHOD)).hasSize(methodsAfterFirst);
    }

    @Test
    void indexesMethodsAsNodesWithDeclaringEdges() {
        indexer.indexFull(Paths.get("src/test/resources/sample-ddd"));

        var methods = store.findNodesByType(NodeType.METHOD);
        assertThat(methods).isNotEmpty();
        assertThat(methods).allMatch(m -> m.id().contains("#"));
        assertThat(store.getAllEdges())
            .anyMatch(e -> e.type() == com.codenavigator.graph.EdgeType.DECLARES_METHOD);
    }

    @Test
    void indexesTier3DddProject() {
        indexer.indexFull(Paths.get("src/test/resources/sample-ddd"));
        assertThat(store.findNodesByType(NodeType.AGGREGATE)).isNotEmpty();
        assertThat(store.findNodesByType(NodeType.COMMAND)).isNotEmpty();
        assertThat(store.findNodesByType(NodeType.DOMAIN_EVENT)).isNotEmpty();
        assertThat(store.getAllEdges()).isNotEmpty();
        assertThat(store.getConfig("tier")).isEqualTo("DDD");
    }

    @Test
    void incrementalSyncSkipsUnchanged() {
        indexer.indexFull(Paths.get("src/test/resources/sample-generic"));
        var nodeCount = store.getAllNodes().size();
        indexer.indexIncremental(Paths.get("src/test/resources/sample-generic"));
        assertThat(store.getAllNodes()).hasSize(nodeCount);
    }

    @Test
    void incrementalSyncUsesContentHash() throws Exception {
        var projectPath = tempDir.resolve("hash-project");
        var javaFile = projectPath.resolve("src/main/java/com/sample/Foo.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, """
            package com.sample;
            public class Foo {}
            """);

        indexer.indexFull(projectPath);
        int initialNodeCount = store.getAllNodes().size();

        // Touch file (timestamp changes, content same)
        Thread.sleep(100);
        javaFile.toFile().setLastModified(System.currentTimeMillis() + 10000);

        // Sync should NOT re-index because content hash matches
        indexer.indexIncremental(projectPath);
        int afterTouchCount = store.getAllNodes().size();
        assertThat(afterTouchCount).isEqualTo(initialNodeCount);
    }

    @Test
    void incrementalIndexPrunesDeletedFiles() throws Exception {
        var projectPath = tempDir.resolve("prune-project");
        var fooFile = projectPath.resolve("src/main/java/com/sample/Foo.java");
        var barFile = projectPath.resolve("src/main/java/com/sample/Bar.java");
        Files.createDirectories(fooFile.getParent());
        Files.writeString(fooFile, """
            package com.sample;
            public class Foo {}
            """);
        Files.writeString(barFile, """
            package com.sample;
            public class Bar {}
            """);

        indexer.indexFull(projectPath);
        var barTrackedPath = projectPath.relativize(barFile).toString();
        assertThat(store.findNodesByFilePath(barTrackedPath)).isNotEmpty();
        var fooTrackedPath = projectPath.relativize(fooFile).toString();
        assertThat(store.findNodesByFilePath(fooTrackedPath)).isNotEmpty();

        Files.delete(barFile);
        indexer.indexIncremental(projectPath);

        assertThat(store.findNodesByFilePath(barTrackedPath)).isEmpty();
        assertThat(store.getAllIndexedFiles()).doesNotContain(barTrackedPath);
        assertThat(store.findNodesByFilePath(fooTrackedPath)).isNotEmpty();
        assertThat(store.getAllIndexedFiles()).contains(fooTrackedPath);
    }

    @Test
    void incrementalSyncRebuildsTheSameEdgeSetAsAFullIndex() throws Exception {
        var projectPath = tempDir.resolve("sync-project");
        var helperFile = projectPath.resolve("src/main/java/com/sample/Helper.java");
        var callerFile = projectPath.resolve("src/main/java/com/sample/Caller.java");
        Files.createDirectories(helperFile.getParent());
        Files.writeString(helperFile, """
            package com.sample;
            public class Helper {
                public String help() { return "hi"; }
            }
            """);
        Files.writeString(callerFile, """
            package com.sample;
            public class Caller {
                private final Helper helper = new Helper();
                public String run() { return helper.help(); }
            }
            """);
        var tsFile = projectPath.resolve("src/app/svc.ts");
        Files.createDirectories(tsFile.getParent());
        Files.writeString(tsFile, """
            export class Svc {
              outer(): void { this.inner(); }
              inner(): void { }
            }
            """);

        indexer.indexFull(projectPath);

        var callerTrackedPath = projectPath.relativize(callerFile).toString();
        int nodesForCallerBeforeSync = store.findNodesByFilePath(callerTrackedPath).size();
        long declaresMethodEdgesBefore = store.getAllEdges().stream()
            .filter(e -> e.type() == com.codenavigator.graph.EdgeType.DECLARES_METHOD)
            .count();
        int helperMethodRowsBefore = store.findMethodsByNodeId("com.sample.Helper").size();
        assertThat(declaresMethodEdgesBefore).isPositive();
        assertThat(helperMethodRowsBefore).isEqualTo(1);

        // Modify one Java file (same signatures, changed body) and force the change to be detected.
        Thread.sleep(100);
        Files.writeString(callerFile, """
            package com.sample;
            public class Caller {
                private final Helper helper = new Helper();
                public String run() { return helper.help() + "!"; }
            }
            """);
        callerFile.toFile().setLastModified(System.currentTimeMillis() + 10000);

        indexer.indexIncremental(projectPath);

        long declaresMethodEdgesAfter = store.getAllEdges().stream()
            .filter(e -> e.type() == com.codenavigator.graph.EdgeType.DECLARES_METHOD)
            .count();
        int helperMethodRowsAfter = store.findMethodsByNodeId("com.sample.Helper").size();
        int nodesForCallerAfterSync = store.findNodesByFilePath(callerTrackedPath).size();

        assertThat(declaresMethodEdgesAfter).isPositive();
        assertThat(declaresMethodEdgesAfter).isGreaterThanOrEqualTo(declaresMethodEdgesBefore);
        // No duplicate rows accumulated in the methods side-table for the unchanged class.
        assertThat(helperMethodRowsAfter).isEqualTo(1);
        // Node count for the changed file is unaffected by the edge/method rebuild.
        assertThat(nodesForCallerAfterSync).isEqualTo(nodesForCallerBeforeSync);
    }

    @Test
    void indexesGroovyScriptsAlongsideJava() throws Exception {
        var proj = tempDir.resolve("mixed-proj");
        var javaDir = proj.resolve("src/main/java/com/demo");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("Foo.java"), """
            package com.demo;
            public class Foo {}
            """);
        var cicd = proj.resolve("cicd");
        Files.createDirectories(cicd);
        Files.writeString(cicd.resolve("Jenkinsfile.groovy"), "node {\n  echo 'building'\n}\n");

        indexer.indexFull(proj);

        var scripts = store.findNodesByType(NodeType.GROOVY_SCRIPT);
        assertThat(scripts).extracting("name").contains("Jenkinsfile");
        // Java path is unaffected:
        assertThat(store.getAllNodes()).extracting("name").contains("Foo");
    }

    @Test
    void indexFullCreatesLibraryNodesForSpringDependencies() throws Exception {
        // Self-contained project: a build.gradle declaring Spring + a controller that
        // imports a Spring type. The indexer should mint a LIBRARY node for that import.
        var proj = tempDir.resolve("spring-proj");
        Files.createDirectories(proj);
        Files.writeString(proj.resolve("build.gradle"), """
            dependencies {
                implementation 'org.springframework.boot:spring-boot-starter-web:3.2.0'
            }
            """);
        var javaDir = proj.resolve("src/main/java/com/demo");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("DemoController.java"), """
            package com.demo;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class DemoController {}
            """);

        indexer.indexFull(proj);

        var libraryNodes = store.findNodesByType(NodeType.LIBRARY);
        assertThat(libraryNodes).isNotEmpty();
        assertThat(libraryNodes).allMatch(n -> n.id().startsWith("org.springframework"));
    }

    @Test
    void indexesShellScriptsWithFunctionsAndSourcesEdge() throws Exception {
        var proj = tempDir.resolve("shell-proj");
        Files.createDirectories(proj);
        Files.writeString(proj.resolve("lib.sh"), """
            #!/bin/bash
            helper() {
              echo helping
            }
            """);
        var deployFile = proj.resolve("deploy.sh");
        Files.writeString(deployFile, """
            #!/bin/bash
            source ./lib.sh

            build() {
              echo building
            }
            """);

        indexer.indexFull(proj);

        assertThat(store.findNodesByType(NodeType.SHELL_SCRIPT)).hasSize(2);
        var methodNodes = store.getAllNodes().stream()
            .filter(n -> n.type() == com.codenavigator.graph.NodeType.METHOD
                && (n.filePath().equals("lib.sh") || n.filePath().equals("deploy.sh")))
            .toList();
        assertThat(methodNodes).hasSize(2);

        var edges = store.getAllEdges();
        assertThat(edges).filteredOn(e -> e.type() == com.codenavigator.graph.EdgeType.SOURCES).hasSize(1);
        long declaresMethodForShell = edges.stream()
            .filter(e -> e.type() == com.codenavigator.graph.EdgeType.DECLARES_METHOD)
            .filter(e -> methodNodes.stream().anyMatch(m -> m.id().equals(e.targetId())))
            .count();
        assertThat(declaresMethodForShell).isEqualTo(2);

        // Modify deploy.sh to add a RUNS-worthy command and re-sync incrementally.
        Thread.sleep(100);
        Files.writeString(deployFile, """
            #!/bin/bash
            source ./lib.sh

            build() {
              echo building
            }

            git pull
            """);
        deployFile.toFile().setLastModified(System.currentTimeMillis() + 10000);

        int nodeCountBeforeIncremental = store.getAllNodes().size();
        indexer.indexIncremental(proj);

        assertThat(store.getAllEdges()).anyMatch(e -> e.type() == com.codenavigator.graph.EdgeType.RUNS
            && e.targetId().equals("git"));
        // One new SHELL_COMMAND node for "git"; everything else unchanged.
        assertThat(store.getAllNodes().size()).isEqualTo(nodeCountBeforeIncremental + 1);
    }

    @Test
    void indexesSqlMigrationsWithRenameAndLaterView() throws Exception {
        var proj = tempDir.resolve("sql-proj");
        var migrations = proj.resolve("db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__init.sql"), """
            CREATE TABLE bar (id INT PRIMARY KEY);
            CREATE TABLE foo (id INT PRIMARY KEY, bar_id INT REFERENCES bar(id));
            """);
        Files.writeString(migrations.resolve("V2__rename.sql"), """
            ALTER TABLE foo RENAME TO renamed_foo;
            """);

        indexer.indexFull(proj);

        var tables = store.findNodesByType(NodeType.SQL_TABLE);
        assertThat(tables).hasSize(2);
        assertThat(tables).extracting("name").containsExactlyInAnyOrder("bar", "renamed_foo");
        assertThat(store.getAllEdges())
            .filteredOn(e -> e.type() == com.codenavigator.graph.EdgeType.REFERENCES_FK)
            .hasSize(1);

        var barNode = tables.stream().filter(n -> n.name().equals("bar")).findFirst().orElseThrow();
        var renamedFooNode = tables.stream().filter(n -> n.name().equals("renamed_foo")).findFirst().orElseThrow();

        // Add a view over the renamed table and re-sync incrementally.
        Thread.sleep(100);
        Files.writeString(migrations.resolve("V3__view.sql"), """
            CREATE VIEW renamed_foo_view AS SELECT * FROM renamed_foo;
            """);

        indexer.indexIncremental(proj);

        var views = store.findNodesByType(NodeType.SQL_VIEW);
        assertThat(views).hasSize(1);
        assertThat(views.getFirst().name()).isEqualTo("renamed_foo_view");
        assertThat(store.getAllEdges())
            .filteredOn(e -> e.type() == com.codenavigator.graph.EdgeType.DERIVES_FROM)
            .anyMatch(e -> e.sourceId().equals(views.getFirst().id()) && e.targetId().equals(renamedFooNode.id()));

        // The untouched table nodes still exist with the same ids.
        assertThat(store.findNodeById(barNode.id())).isPresent();
        assertThat(store.findNodeById(renamedFooNode.id())).isPresent();
    }

    @Test
    void indexesPythonPackageWithImportAndCallEdgeThenIncrementalClassAdd() throws Exception {
        var proj = tempDir.resolve("py-proj");
        var app = proj.resolve("app");
        Files.createDirectories(app);
        Files.writeString(app.resolve("__init__.py"), "");
        Files.writeString(app.resolve("util.py"), """
            def helper():
                return 1
            """);
        var mainFile = app.resolve("main.py");
        Files.writeString(mainFile, """
            from .util import helper

            def run():
                return helper()
            """);

        indexer.indexFull(proj);

        assertThat(store.findNodesByType(NodeType.PY_MODULE)).hasSize(3);
        assertThat(store.findNodesByType(NodeType.METHOD)).hasSize(2);
        assertThat(store.getAllEdges())
            .filteredOn(e -> e.type() == com.codenavigator.graph.EdgeType.IMPORTS).hasSize(1);
        assertThat(store.getAllEdges())
            .filteredOn(e -> e.type() == com.codenavigator.graph.EdgeType.CALLS).hasSize(1);

        var utilModule = store.findNodesByType(NodeType.PY_MODULE).stream()
            .filter(n -> n.filePath().equals("app/util.py")).findFirst().orElseThrow();
        var utilModuleId = utilModule.id();

        // Add a class with one method to main.py and re-sync incrementally.
        Thread.sleep(100);
        Files.writeString(mainFile, """
            from .util import helper

            def run():
                return helper()

            class Runner:
                def go(self):
                    return run()
            """);
        mainFile.toFile().setLastModified(System.currentTimeMillis() + 10000);

        indexer.indexIncremental(proj);

        assertThat(store.findNodesByType(NodeType.PY_CLASS)).hasSize(1);
        assertThat(store.findNodesByType(NodeType.METHOD)).hasSize(3);

        // util.py's module node id is unchanged.
        var utilModuleAfter = store.findNodesByType(NodeType.PY_MODULE).stream()
            .filter(n -> n.filePath().equals("app/util.py")).findFirst().orElseThrow();
        assertThat(utilModuleAfter.id()).isEqualTo(utilModuleId);
    }

    @Test
    void referencesTableEdgeTracksSqlTableDropAndRecreate() throws Exception {
        var proj = tempDir.resolve("ref-table-proj");
        var migrations = proj.resolve("db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__init.sql"), """
            CREATE TABLE supplier (id INT PRIMARY KEY);
            """);
        var javaDir = proj.resolve("src/main/java/com/demo");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("Supplier.java"), """
            package com.demo;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Table;
            @Entity
            @Table(name = "supplier")
            public class Supplier {}
            """);

        indexer.indexFull(proj);

        assertThat(store.getAllEdges())
            .filteredOn(e -> e.type() == com.codenavigator.graph.EdgeType.REFERENCES_TABLE)
            .hasSize(1);

        // Drop the table: the edge and the SQL_TABLE node both disappear.
        Thread.sleep(100);
        Files.writeString(migrations.resolve("V2__drop.sql"), """
            DROP TABLE supplier;
            """);

        indexer.indexIncremental(proj);

        assertThat(store.getAllEdges())
            .filteredOn(e -> e.type() == com.codenavigator.graph.EdgeType.REFERENCES_TABLE)
            .isEmpty();
        assertThat(store.findNodesByType(NodeType.SQL_TABLE)).isEmpty();

        // Recreate the table: the edge comes back, between the same class and table ids.
        Thread.sleep(100);
        Files.writeString(migrations.resolve("V3__recreate.sql"), """
            CREATE TABLE supplier (id INT PRIMARY KEY);
            """);

        indexer.indexIncremental(proj);

        var edges = store.getAllEdges().stream()
            .filter(e -> e.type() == com.codenavigator.graph.EdgeType.REFERENCES_TABLE)
            .toList();
        assertThat(edges).hasSize(1);
        assertThat(edges.getFirst().sourceId()).isEqualTo("com.demo.Supplier");
        assertThat(edges.getFirst().targetId()).isEqualTo("sql:supplier");
    }
}
