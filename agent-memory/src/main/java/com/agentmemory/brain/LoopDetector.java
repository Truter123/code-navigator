package com.agentmemory.brain;

import com.agentmemory.model.Anomaly;
import com.agentmemory.model.AuditEntry;
import com.agentmemory.store.MemoryStore;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class LoopDetector {

    private static final int THRESHOLD = 3;
    private static final int WINDOW_MINUTES = 30;

    private final MemoryStore store;

    public LoopDetector(MemoryStore store) {
        this.store = store;
    }

    public List<Anomaly> detect(String agent, String project) {
        String from = Instant.now().minus(WINDOW_MINUTES, ChronoUnit.MINUTES).toString();
        List<AuditEntry> entries = store.getAuditLog(agent, null, from, null, 1000, 0);

        List<Anomaly> anomalies = new ArrayList<>();
        String now = Instant.now().toString();

        // Check: same key stored > THRESHOLD times
        Map<String, Integer> storeCounts = new HashMap<>();
        for (AuditEntry e : entries) {
            if ("upsert".equals(e.operation()) && e.memoryKey() != null) {
                storeCounts.merge(e.memoryKey(), 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> entry : storeCounts.entrySet()) {
            if (entry.getValue() > THRESHOLD) {
                anomalies.add(new Anomaly(
                        "loop",
                        "warning",
                        "Key '" + entry.getKey() + "' was stored " + entry.getValue()
                                + " times in the last " + WINDOW_MINUTES + " minutes",
                        List.of(entry.getKey()),
                        now
                ));
            }
        }

        // Check: recall-store-recall cycles on same key
        Map<String, List<String>> keyOps = new LinkedHashMap<>();
        for (AuditEntry e : entries) {
            if (e.memoryKey() != null && ("upsert".equals(e.operation()) || "recall".equals(e.operation()))) {
                keyOps.computeIfAbsent(e.memoryKey(), k -> new ArrayList<>()).add(e.operation());
            }
        }
        for (Map.Entry<String, List<String>> entry : keyOps.entrySet()) {
            List<String> ops = entry.getValue();
            int cycles = 0;
            for (int i = 0; i < ops.size() - 2; i++) {
                if ("recall".equals(ops.get(i)) && "upsert".equals(ops.get(i + 1)) && "recall".equals(ops.get(i + 2))) {
                    cycles++;
                }
            }
            if (cycles > THRESHOLD) {
                anomalies.add(new Anomaly(
                        "loop",
                        "warning",
                        "Recall-store-recall cycle detected on key '" + entry.getKey()
                                + "' (" + cycles + " cycles)",
                        List.of(entry.getKey()),
                        now
                ));
            }
        }

        // Check: identical search queries repeated > THRESHOLD times
        Map<String, Integer> searchCounts = new HashMap<>();
        for (AuditEntry e : entries) {
            if ("search".equals(e.operation()) && e.details() != null) {
                searchCounts.merge(e.details(), 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> entry : searchCounts.entrySet()) {
            if (entry.getValue() > THRESHOLD) {
                anomalies.add(new Anomaly(
                        "loop",
                        "warning",
                        "Search query '" + entry.getKey() + "' repeated " + entry.getValue()
                                + " times in the last " + WINDOW_MINUTES + " minutes",
                        List.of(),
                        now
                ));
            }
        }

        return anomalies;
    }
}
