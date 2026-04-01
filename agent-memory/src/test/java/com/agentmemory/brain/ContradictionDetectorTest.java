package com.agentmemory.brain;

import com.agentmemory.model.Anomaly;
import com.agentmemory.store.GraphStore;
import com.agentmemory.store.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ContradictionDetectorTest {

    @TempDir
    Path tempDir;

    MemoryStore store;
    GraphStore graphStore;
    ContradictionDetector detector;

    @BeforeEach
    void setUp() {
        store = new MemoryStore(tempDir.resolve("test.db"));
        graphStore = new GraphStore(store);
        detector = new ContradictionDetector(store, graphStore);
    }

    @Test
    void detectsOpposingSentiment() {
        // Store initial value with "healthy"
        store.upsert("service-status", "service is healthy and running", "claude", "proj",
                List.of(), 0.5, false);

        // Now check contradiction when updating to "at-risk"
        List<Anomaly> anomalies = detector.checkForContradictions(
                "service-status", "service is at-risk and degraded", "claude", "proj");

        assertThat(anomalies).isNotEmpty();
        assertThat(anomalies).anyMatch(a ->
                a.type().equals("contradiction") && a.affectedKeys().contains("service-status"));
    }

    @Test
    void noContradictionWhenContentIsSimilar() {
        store.upsert("service-status", "service is healthy and running well", "claude", "proj",
                List.of(), 0.5, false);

        List<Anomaly> anomalies = detector.checkForContradictions(
                "service-status", "service is healthy and stable", "claude", "proj");

        assertThat(anomalies).isEmpty();
    }
}
