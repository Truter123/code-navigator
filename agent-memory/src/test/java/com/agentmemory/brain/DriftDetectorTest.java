package com.agentmemory.brain;

import com.agentmemory.model.Anomaly;
import com.agentmemory.store.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class DriftDetectorTest {

    @TempDir
    Path tempDir;

    MemoryStore store;
    DriftDetector detector;

    @BeforeEach
    void setUp() {
        store = new MemoryStore(tempDir.resolve("test.db"));
        detector = new DriftDetector(store);
    }

    @Test
    void detectsDriftWhenOpsUnrelatedToGoals() {
        // Goals are about auth
        store.setGoals("claude", List.of("implement auth module", "add login flow"), "proj");

        // But all operations are on billing keys — unrelated
        store.logAudit("claude", "upsert", "billing-config", "stored", 5.0);
        store.logAudit("claude", "upsert", "invoice-template", "stored", 5.0);
        store.logAudit("claude", "upsert", "payment-gateway", "stored", 5.0);
        store.logAudit("claude", "upsert", "subscription-plan", "stored", 5.0);
        store.logAudit("claude", "recall", "pricing-tier", "found", 3.0);

        List<Anomaly> anomalies = detector.detect("claude", "proj");

        assertThat(anomalies).isNotEmpty();
        assertThat(anomalies).anyMatch(a -> a.type().equals("drift"));
    }

    @Test
    void noDriftWhenOpsAlignWithGoals() {
        store.setGoals("claude", List.of("implement auth module", "add login flow"), "proj");

        // Operations align with auth goals
        store.logAudit("claude", "upsert", "auth-config", "stored", 5.0);
        store.logAudit("claude", "upsert", "login-handler", "stored", 5.0);
        store.logAudit("claude", "recall", "auth-token", "found", 3.0);
        store.logAudit("claude", "upsert", "module-settings", "stored", 5.0);

        List<Anomaly> anomalies = detector.detect("claude", "proj");

        assertThat(anomalies).isEmpty();
    }

    @Test
    void noDriftWhenNoGoalsRegistered() {
        // No goals set
        store.logAudit("claude", "upsert", "billing-config", "stored", 5.0);
        store.logAudit("claude", "upsert", "random-stuff", "stored", 5.0);

        List<Anomaly> anomalies = detector.detect("claude", "proj");

        assertThat(anomalies).isEmpty();
    }
}
