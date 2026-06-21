package com.codenavigator.indexer;

import com.codenavigator.embedding.EmbeddingProvider;
import com.codenavigator.graph.GraphStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectIndexerEmbeddingTest {

    @TempDir Path tempDir;
    @TempDir Path projectDir;
    private GraphStore store;

    @BeforeEach
    void setUp() { store = new GraphStore(tempDir.resolve("test.db")); }

    @AfterEach
    void tearDown() { store.close(); }

    private void writeDummy() throws Exception {
        Path src = projectDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Dummy.java"),
            "package com.example;\npublic class Dummy {}");
    }

    @Test
    void embeddingProviderCalledAndPersistedWhenEnabled() throws Exception {
        writeDummy();
        AtomicInteger callCount = new AtomicInteger(0);
        EmbeddingProvider counting = text -> { callCount.incrementAndGet(); return new float[]{0.1f, 0.2f}; };

        new ProjectIndexer(store, counting).indexFull(projectDir);

        assertThat(callCount.get()).isGreaterThan(0);
        List<float[]> stored = new ArrayList<>();
        store.streamAllEmbeddings((id, v) -> stored.add(v));
        assertThat(stored).isNotEmpty();
    }

    @Test
    void noopProviderStoresNoEmbeddings() throws Exception {
        writeDummy();
        new ProjectIndexer(store).indexFull(projectDir); // default Noop

        List<float[]> stored = new ArrayList<>();
        store.streamAllEmbeddings((id, v) -> stored.add(v));
        assertThat(stored).isEmpty();
    }
}
