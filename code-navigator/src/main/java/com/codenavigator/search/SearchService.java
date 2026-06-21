package com.codenavigator.search;

import com.codenavigator.embedding.EmbeddingProvider;
import com.codenavigator.embedding.NoopEmbeddingProvider;
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
    private final EmbeddingProvider embeddingProvider;

    /** Legacy constructor — embeddings disabled (Noop). */
    public SearchService(GraphStore store, GraphTraversal traversal) {
        this(store, traversal, new NoopEmbeddingProvider());
    }

    /** Full constructor with injectable EmbeddingProvider. */
    public SearchService(GraphStore store, GraphTraversal traversal, EmbeddingProvider embeddingProvider) {
        this.store = store;
        this.traversal = traversal;
        this.embeddingProvider = embeddingProvider;
    }

    /**
     * Hybrid search: FTS5 (with LIKE fallback) and, when embeddings are available,
     * cosine-rank stored vectors against the query embedding, fused via RRF.
     * When the provider returns an empty vector, this degrades to the original FTS5 path.
     */
    public List<Node> search(String query) {
        String ftsQuery = query.endsWith("*") ? query : query + "*";
        var ftsOrdered = new LinkedHashMap<String, Node>();
        for (var node : store.searchFts(ftsQuery)) {
            ftsOrdered.put(node.id(), node);
        }
        for (var node : store.searchLike(query)) {
            ftsOrdered.putIfAbsent(node.id(), node);
        }

        float[] queryVec = embeddingProvider.embed(query);
        if (queryVec.length == 0) {
            return new ArrayList<>(ftsOrdered.values()); // original behaviour
        }

        List<String> vecRanked = vectorRank(queryVec);
        List<String> ftsRanked = new ArrayList<>(ftsOrdered.keySet());
        List<String> fused = rrf(60, ftsRanked, vecRanked);

        Map<String, Node> allKnown = new LinkedHashMap<>(ftsOrdered);
        for (String id : vecRanked) {
            allKnown.computeIfAbsent(id, k -> store.findNodeById(k).orElse(null));
        }
        allKnown.values().removeIf(Objects::isNull);

        return fused.stream()
            .filter(allKnown::containsKey)
            .map(allKnown::get)
            .collect(Collectors.toList());
    }

    /**
     * Context search for a task description:
     * 1. Extract keywords -> FTS5 prefix search
     * 2. Expand hits via chain tracing
     * 3. Deduplicate, return
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

    /** Cosine-rank all stored embeddings against a query vector (desc, sim>0). */
    private List<String> vectorRank(float[] queryVec) {
        List<Map.Entry<String, Float>> scored = new ArrayList<>();
        store.streamAllEmbeddings((nodeId, vector) -> {
            float sim = cosine(queryVec, vector);
            if (sim > 0.0f) scored.add(Map.entry(nodeId, sim));
        });
        scored.sort(Map.Entry.<String, Float>comparingByValue().reversed());
        return scored.stream().map(Map.Entry::getKey).collect(Collectors.toList());
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

    /**
     * Reciprocal Rank Fusion over ranked ID lists. Score = sum( 1 / (k + rank_1based) ).
     */
    @SafeVarargs
    static List<String> rrf(int k, List<String>... lists) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (List<String> list : lists) {
            for (int i = 0; i < list.size(); i++) {
                scores.merge(list.get(i), 1.0 / (k + i + 1), Double::sum);
            }
        }
        return scores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }
}
