package com.agentmemory.brain;

import com.agentmemory.model.Anomaly;
import com.agentmemory.model.Memory;
import com.agentmemory.model.MemoryLink;
import com.agentmemory.store.GraphStore;
import com.agentmemory.store.MemoryStore;

import java.time.Instant;
import java.util.*;

public class ContradictionDetector {

    private static final Map<String, String> OPPOSING_KEYWORDS = new HashMap<>();

    static {
        addOpposites("healthy", "at-risk");
        addOpposites("passing", "failing");
        addOpposites("active", "inactive");
        addOpposites("enabled", "disabled");
        addOpposites("up", "down");
        addOpposites("success", "failure");
        addOpposites("stable", "unstable");
        addOpposites("complete", "incomplete");
        addOpposites("valid", "invalid");
        addOpposites("approved", "rejected");
    }

    private static void addOpposites(String a, String b) {
        OPPOSING_KEYWORDS.put(a, b);
        OPPOSING_KEYWORDS.put(b, a);
    }

    private final MemoryStore memoryStore;
    private final GraphStore graphStore;

    public ContradictionDetector(MemoryStore memoryStore, GraphStore graphStore) {
        this.memoryStore = memoryStore;
        this.graphStore = graphStore;
    }

    public List<Anomaly> checkForContradictions(String key, String newValue, String agent, String project) {
        Optional<Memory> existing = memoryStore.recall(key, agent, project);
        if (existing.isEmpty()) {
            return List.of();
        }

        String oldValue = existing.get().value();
        if (oldValue == null || newValue == null) {
            return List.of();
        }

        Set<String> oldTokens = tokenize(oldValue);
        Set<String> newTokens = tokenize(newValue);

        List<String> contradictions = new ArrayList<>();
        for (String oldToken : oldTokens) {
            String opposite = OPPOSING_KEYWORDS.get(oldToken);
            if (opposite != null && newTokens.contains(opposite)) {
                contradictions.add(oldToken + " -> " + opposite);
            }
        }

        if (!contradictions.isEmpty()) {
            return List.of(new Anomaly(
                    "contradiction",
                    "warning",
                    "Contradicting values for key '" + key + "': " + String.join(", ", contradictions),
                    List.of(key),
                    Instant.now().toString()
            ));
        }

        return List.of();
    }

    public List<Anomaly> detectAll(String agent, String project) {
        List<MemoryLink> links = graphStore.getAllLinks(agent, project);
        List<Anomaly> anomalies = new ArrayList<>();

        for (MemoryLink link : links) {
            if ("contradicts".equals(link.relation())) {
                Optional<Memory> source = memoryStore.findById(link.sourceId());
                Optional<Memory> target = memoryStore.findById(link.targetId());
                if (source.isPresent() && target.isPresent()) {
                    anomalies.add(new Anomaly(
                            "contradiction",
                            "warning",
                            "Contradiction link between '" + source.get().key()
                                    + "' and '" + target.get().key() + "'",
                            List.of(source.get().key(), target.get().key()),
                            Instant.now().toString()
                    ));
                }
            }
        }

        return anomalies;
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        for (String token : text.toLowerCase().split("[\\s,.:;!?()\\[\\]{}\"']+")) {
            String trimmed = token.replace("-", "");
            // Keep the original hyphenated form too
            if (!token.isBlank()) {
                tokens.add(token);
            }
            if (!trimmed.isBlank() && !trimmed.equals(token)) {
                tokens.add(trimmed);
            }
        }
        // Also handle hyphenated compounds as-is (e.g., "at-risk")
        for (String token : text.toLowerCase().split("[\\s,.:;!?()\\[\\]{}\"']+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}
