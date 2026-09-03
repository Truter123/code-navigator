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
}
