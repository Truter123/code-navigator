package com.agentmemory.brain;

import com.agentmemory.model.Anomaly;
import com.agentmemory.store.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class LoopDetectorTest {

    @TempDir
    Path tempDir;

    MemoryStore store;
    LoopDetector detector;

    @BeforeEach
    void setUp() {
        store = new MemoryStore(tempDir.resolve("test.db"));
        detector = new LoopDetector(store);
    }

    @Test
    void detectsRepeatedStoresOnSameKey() {
        // 4 stores on same key exceeds threshold of 3
        store.logAudit("claude", "upsert", "config-db", "stored", 5.0);
        store.logAudit("claude", "upsert", "config-db", "stored", 5.0);
        store.logAudit("claude", "upsert", "config-db", "stored", 5.0);
        store.logAudit("claude", "upsert", "config-db", "stored", 5.0);

        List<Anomaly> anomalies = detector.detect("claude", "myproject");

        assertThat(anomalies).isNotEmpty();
        assertThat(anomalies).anyMatch(a ->
                a.type().equals("loop") && a.affectedKeys().contains("config-db"));
    }

    @Test
    void noLoopWhenBelowThreshold() {
        // Only 2 stores — below threshold of 3
        store.logAudit("claude", "upsert", "config-db", "stored", 5.0);
        store.logAudit("claude", "upsert", "config-db", "stored", 5.0);

        List<Anomaly> anomalies = detector.detect("claude", "myproject");

        assertThat(anomalies).isEmpty();
    }

    @Test
    void detectsRepeatedSearchQueries() {
        // 4 identical search queries exceeds threshold
        store.logAudit("claude", "search", null, "query:find auth module", 3.0);
        store.logAudit("claude", "search", null, "query:find auth module", 3.0);
        store.logAudit("claude", "search", null, "query:find auth module", 3.0);
        store.logAudit("claude", "search", null, "query:find auth module", 3.0);

        List<Anomaly> anomalies = detector.detect("claude", "myproject");

        assertThat(anomalies).isNotEmpty();
        assertThat(anomalies).anyMatch(a ->
                a.type().equals("loop") && a.description().toLowerCase().contains("search"));
    }
}
