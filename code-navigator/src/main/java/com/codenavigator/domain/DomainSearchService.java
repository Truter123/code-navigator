package com.codenavigator.domain;

import com.codenavigator.domain.DomainSqliteStore;

import java.util.*;
import java.util.stream.Collectors;

public class DomainSearchService {

    private static final Set<String> STOP_WORDS = Set.of(
        "a", "an", "the", "to", "from", "in", "on", "at", "by", "for", "with",
        "of", "and", "or", "is", "it", "that", "this", "be", "as", "are", "was",
        "i", "we", "you", "he", "she", "they", "my", "our", "do", "does", "did",
        "can", "can't", "cannot", "could", "would", "should", "why", "how",
        "what", "when", "where", "which", "who", "not", "no", "if", "but",
        "tell", "me", "about", "have", "has", "had", "get", "give", "show"
    );

    private final DomainSqliteStore store;

    public DomainSearchService(DomainSqliteStore store) {
        this.store = store;
    }

    public List<DomainSqliteStore.SearchResult> search(String query) {
        return store.searchFts(query);
    }

    public String explain(String question) {
        var keywords = extractKeywords(question);
        if (keywords.isEmpty()) return "No relevant domain knowledge found.";

        var allResults = new LinkedHashSet<DomainSqliteStore.SearchResult>();
        for (var keyword : keywords) {
            allResults.addAll(search(keyword));
        }

        if (allResults.isEmpty()) return "No relevant domain knowledge found for: " + question;

        var sb = new StringBuilder();
        sb.append("## Domain context for: ").append(question).append("\n\n");

        var byType = allResults.stream()
            .collect(Collectors.groupingBy(DomainSqliteStore.SearchResult::type,
                LinkedHashMap::new, Collectors.toList()));

        for (var entry : byType.entrySet()) {
            sb.append("### ").append(pluralize(entry.getKey())).append("\n");
            for (var result : entry.getValue()) {
                sb.append("- **").append(result.name()).append("**: ")
                    .append(result.description()).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    public List<String> extractKeywords(String text) {
        return Arrays.stream(text.toLowerCase().split("[\\s,.;:!?'\"()]+"))
            .map(w -> w.replaceAll("[^a-z0-9]", ""))
            .filter(w -> !w.isEmpty() && w.length() > 1)
            .filter(w -> !STOP_WORDS.contains(w))
            .distinct()
            .collect(Collectors.toList());
    }

    private String capitalize(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private String pluralize(String s) {
        var cap = capitalize(s);
        if (cap.endsWith("y")) return cap.substring(0, cap.length() - 1) + "ies";
        return cap + "s";
    }
}
