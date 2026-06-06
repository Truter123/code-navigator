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
