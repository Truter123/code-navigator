package com.agentmemory.brain;

import com.agentmemory.model.Anomaly;
import com.agentmemory.model.AuditEntry;
import com.agentmemory.model.Goal;
import com.agentmemory.store.MemoryStore;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

public class DriftDetector {

    private static final double DRIFT_THRESHOLD = 0.7;
    private static final int WINDOW_MINUTES = 30;

    private final MemoryStore store;

    public DriftDetector(MemoryStore store) {
        this.store = store;
    }

    public List<Anomaly> detect(String agent, String project) {
        List<Goal> activeGoals = store.getGoals(agent, "active");
        if (activeGoals.isEmpty()) {
            return List.of();
        }

        // Extract keywords from goals
        Set<String> keywords = activeGoals.stream()
                .flatMap(g -> Arrays.stream(g.description().split("[\\s:_\\-]+")))
                .filter(w -> w.length() > 2)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        // Get recent audit entries
        String from = Instant.now().minus(WINDOW_MINUTES, ChronoUnit.MINUTES).toString();
        List<AuditEntry> entries = store.getAuditLog(agent, null, from, null, 1000, 0);

        if (entries.isEmpty()) {
            return List.of();
        }

        // Count operations where memory_key has no keyword overlap with goals
        int totalWithKey = 0;
        int unrelated = 0;
        for (AuditEntry e : entries) {
            if (e.memoryKey() == null) continue;
            totalWithKey++;

            String[] keyParts = e.memoryKey().toLowerCase().split("[\\s:_\\-]+");
            boolean related = false;
            for (String part : keyParts) {
                if (part.length() > 2 && keywords.contains(part)) {
                    related = true;
                    break;
                }
            }
            if (!related) {
                unrelated++;
            }
        }

        if (totalWithKey == 0) {
            return List.of();
        }

        double driftRatio = (double) unrelated / totalWithKey;
        if (driftRatio > DRIFT_THRESHOLD) {
            String goalSummary = activeGoals.stream()
                    .map(Goal::description)
                    .collect(Collectors.joining(", "));
            return List.of(new Anomaly(
                    "drift",
                    "warning",
                    String.format("%.0f%% of recent operations are unrelated to active goals: %s",
                            driftRatio * 100, goalSummary),
                    List.of(),
                    Instant.now().toString()
            ));
        }

        return List.of();
    }
}
