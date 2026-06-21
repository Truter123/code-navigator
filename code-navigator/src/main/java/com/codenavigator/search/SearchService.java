package com.codenavigator.search;

import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.GraphTraversal;
import com.codenavigator.graph.Node;

import java.util.*;
import java.util.stream.Collectors;

public class SearchService {

    private static final Set<String> STOP_WORDS = Set.of(
        "a", "an", "the", "to", "from", "in", "on", "at", "by", "for", "with",
        "of", "and", "or", "is", "it", "that", "this", "be", "as", "are", "was",
        "add", "remove", "update", "fix", "change", "modify", "create", "delete",
        "field", "method", "class", "file", "when", "if", "not", "all", "new"
    );

    private final GraphStore store;
    private final GraphTraversal traversal;

    public SearchService(GraphStore store, GraphTraversal traversal) {
        this.store = store;
        this.traversal = traversal;
    }

    /** FTS5 search by query string, with LIKE fallback for substring matches */
    public List<Node> search(String query) {
        String ftsQuery = query.endsWith("*") ? query : query + "*";
        var seen = new LinkedHashMap<String, Node>();
        for (var node : store.searchFts(ftsQuery)) {
            seen.put(node.id(), node);
        }
        for (var node : store.searchLike(query)) {
            seen.putIfAbsent(node.id(), node);
        }
        return new ArrayList<>(seen.values());
    }

    /**
     * Context search for a task description:
     * 1. Extract keywords
     * 2. FTS5 search each keyword with prefix matching
     * 3. Expand hits via chain tracing
     * 4. Deduplicate, return
     */
    public List<Node> contextSearch(String taskDescription) {
        var keywords = extractKeywords(taskDescription);
        if (keywords.isEmpty()) return List.of();

        var directHits = new LinkedHashSet<Node>();
        for (var keyword : keywords) {
            directHits.addAll(store.searchFts(keyword + "*"));
        }

        var expanded = new LinkedHashSet<Node>(directHits);
        for (var hit : directHits) {
            expanded.addAll(traversal.traceChain(hit.id()));
        }

        return new ArrayList<>(expanded);
    }

    /** Extract meaningful keywords from text, filtering stop words */
    public List<String> extractKeywords(String text) {
        return Arrays.stream(text.toLowerCase().split("\\s+"))
            .map(w -> w.replaceAll("[^a-z0-9]", ""))
            .filter(w -> !w.isEmpty() && w.length() > 2)
            .filter(w -> !STOP_WORDS.contains(w))
            .distinct()
            .collect(Collectors.toList());
    }

    /**
     * Cosine similarity between two float vectors.
     * Returns 0.0 if either is empty or they have different dimensions.
     */
    static float cosine(float[] a, float[] b) {
        if (a.length == 0 || b.length == 0 || a.length != b.length) return 0.0f;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot   += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0.0 ? 0.0f : (float) (dot / denom);
    }
}
