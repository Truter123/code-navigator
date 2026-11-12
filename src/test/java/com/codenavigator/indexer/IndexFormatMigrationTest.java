package com.codenavigator.indexer;

import com.codenavigator.graph.GraphSnapshot;
import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.Node;
import com.codenavigator.graph.NodeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An index made before nested types got {@code <package>.<Outer>.<Inner>} ids carries no format
 * marker and the flat {@code pkg.Inner} nodes; the next sync rebuilds it whole instead of keeping
 * the old nodes next to the new ones.
 */
class IndexFormatMigrationTest {

    private static final String A = """
        package p;

        public class A {
            public static class Chain {
                public Chain next() { return this; }
            }
        }
        """;

    @TempDir Path tempDir;
    private Path proj;
    private GraphStore store;
    private String aPath;

    @BeforeEach
    void setUp() throws Exception {
        proj = tempDir.resolve("proj");
        Files.createDirectories(proj.resolve("src/main/java/p"));
        Files.writeString(proj.resolve("build.gradle"), "plugins { id 'java' }\n");
        Files.writeString(proj.resolve("src/main/java/p/A.java"), A);
        try (var oracle = new GraphStore(tempDir.resolve("probe.db"))) {
            new ProjectIndexer(oracle).indexFull(proj);
            aPath = oracle.findNodeById("p.A").orElseThrow().filePath();
        }
        store = new GraphStore(tempDir.resolve("graph.db"));
    }

    @AfterEach
    void tearDown() { store.close(); }

    private void addStaleNodes() {
        store.saveNode(new Node("p.Chain", NodeType.CLASS, "Chain", "p.Chain", aPath, 3, "", 1));
        store.saveNode(new Node("p.Chain#next()", NodeType.METHOD, "next", "p.Chain#next()", aPath, 4, "", 1));
    }

    private GraphSnapshot freshFullIndex() {
        try (var fresh = new GraphStore(tempDir.resolve("fresh-" + System.nanoTime() + ".db"))) {
            new ProjectIndexer(fresh).indexFull(proj);
            return GraphSnapshot.of(fresh);
        }
    }

    @Test
    void fullIndexStoresTheFormatMarker() {
        new ProjectIndexer(store).indexFull(proj);

        assertThat(store.getConfig(ProjectIndexer.INDEX_FORMAT_KEY)).isEqualTo(ProjectIndexer.INDEX_FORMAT);
    }

    @Test
    void aStoreWithoutMarkerIsRebuiltWhole() {
        addStaleNodes();

        var result = new ProjectIndexer(store).indexIncremental(proj);

        assertThat(result.fastPath()).isFalse();
        assertThat(result.fallbackReason()).isEqualTo("index format changed");
        assertThat(result.line()).isEqualTo("Sync: full edge rebuild (index format changed).");
        assertThat(store.findNodeById("p.Chain")).isEmpty();
        assertThat(store.findNodeById("p.Chain#next()")).isEmpty();
        assertThat(store.findNodeById("p.A.Chain")).isPresent();
        assertThat(store.findNodeById("p.A.Chain#next()")).isPresent();
        assertThat(store.getConfig(ProjectIndexer.INDEX_FORMAT_KEY)).isEqualTo(ProjectIndexer.INDEX_FORMAT);
        assertThat(GraphSnapshot.of(store).diff(freshFullIndex())).isEmpty();
    }

    @Test
    void aFullyIndexedStoreWithAnOlderMarkerIsRebuiltWhole() {
        new ProjectIndexer(store).indexFull(proj);
        addStaleNodes();
        store.setConfig(ProjectIndexer.INDEX_FORMAT_KEY, "1");

        var result = new ProjectIndexer(store).indexIncremental(proj);

        assertThat(result.fallbackReason()).isEqualTo("index format changed");
        assertThat(store.findNodeById("p.Chain")).isEmpty();
        assertThat(GraphSnapshot.of(store).diff(freshFullIndex())).isEmpty();
    }

    @Test
    void fullIndexRemovesNodesOfAnIdSchemeItNoLongerProduces() {
        addStaleNodes();

        new ProjectIndexer(store).indexFull(proj);

        assertThat(store.findNodeById("p.Chain")).isEmpty();
        assertThat(store.findNodeById("p.Chain#next()")).isEmpty();
        assertThat(store.findNodeById("p.A.Chain")).isPresent();
        assertThat(store.searchFts("Chain")).extracting(Node::id).doesNotContain("p.Chain");
        assertThat(GraphSnapshot.of(store).diff(freshFullIndex())).isEmpty();
    }

    @Test
    void theNextSyncWithNothingChangedDoesNotRebuildAgain() {
        addStaleNodes();
        var indexer = new ProjectIndexer(store);
        indexer.indexIncremental(proj);

        var second = indexer.indexIncremental(proj);

        assertThat(second.fallbackReason()).isNull();
        assertThat(second.fullRebuild()).isFalse();
    }
}
