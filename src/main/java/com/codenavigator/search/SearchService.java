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

    /**
     * FTS5 search (with LIKE fallback).
     */
    public List<Node> search(String query) {
        String ftsQuery = buildFtsQuery(query);
        var ftsOrdered = new LinkedHashMap<String, Node>();
        for (var node : store.searchFts(ftsQuery)) {
            ftsOrdered.put(node.id(), node);
        }
        for (var node : store.searchLike(query)) {
            ftsOrdered.putIfAbsent(node.id(), node);
        }
        var results = new ArrayList<>(ftsOrdered.values());
        // An exact case-insensitive name match first: FTS5 rank alone buried a table node named
        // exactly "supplier" behind 140 partial matches (SupplierEntity, SupplierRepository, ...).
        // Stable sort keeps everything else in its FTS/LIKE order.
        results.sort(Comparator.comparing(n -> !n.name().equalsIgnoreCase(query)));
        return results;
    }

    /**
     * Builds the FTS5 MATCH expression for a raw user query. FTS5 treats {@code - " * ( ) : ^ +}
     * and the bare words {@code NOT AND OR} as operators, so a name like {@code clean-restart}
     * (routine for shell scripts and Python modules) threw a syntax error when appended with a
     * bare {@code *}. Quoting every token neutralises the operators; a quoted token followed by
     * {@code *} is still a valid FTS5 prefix query, so {@code Supplier} still matches
     * {@code SupplierEntity}.
     */
    static String buildFtsQuery(String query) {
        String q = query.trim();
        if (q.endsWith("*")) q = q.substring(0, q.length() - 1).trim();
        String[] tokens = q.isEmpty() ? new String[0] : q.split("\\s+");
        var sb = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append('"').append(tokens[i].replace("\"", "\"\"")).append('"');
        }
        sb.append('*');
        return sb.toString();
    }

    /**
     * Context search for a task description:
     * 1. Extract keywords -> FTS5 prefix search (seed set)
     * 2. Expand seeds via chain tracing
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

    /** Extract meaningful keywords from text, filtering stop words */
    public List<String> extractKeywords(String text) {
        return Arrays.stream(text.toLowerCase().split("\\s+"))
            .map(w -> w.replaceAll("[^a-z0-9]", ""))
            .filter(w -> !w.isEmpty() && w.length() > 2)
            .filter(w -> !STOP_WORDS.contains(w))
            .distinct()
            .collect(Collectors.toList());
    }
}
