package com.codenavigator.search;

import com.codenavigator.graph.EdgeType;
import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.GraphTraversal;
import com.codenavigator.graph.Node;
import com.codenavigator.graph.NodeType;
import com.codenavigator.mcp.TestPaths;

import java.util.*;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

public class SearchService {

    private static final Set<String> STOP_WORDS = Set.of(
        "a", "an", "the", "to", "from", "in", "on", "at", "by", "for", "with",
        "of", "and", "or", "is", "it", "that", "this", "be", "as", "are", "was",
        "add", "remove", "update", "fix", "change", "modify", "create", "delete",
        "field", "method", "class", "file", "when", "if", "not", "all", "new",
        // Question and auxiliary words carry no code meaning. get/find/make/use/work/show/need/want are
        // left out on purpose: they are common identifier parts and may be the real target of a task.
        "where", "what", "which", "why", "who", "whom", "whose", "how", "does", "do", "did", "were",
        "been", "can", "could", "should", "would", "will", "happen", "happens", "happening"
    );

    /** A stem keeps at least this many characters, or it would match half the graph ("ord" for "order"). */
    private static final int MIN_STEM = 4;

    /** The endings {@link #stem} takes off, longest first so "cancellation" loses "ation", not "ion". */
    private static final List<String> SUFFIXES = List.of(
        "ations", "ation", "ments", "ment", "ings", "ing", "ions", "ion", "ers", "er", "ies", "ied", "ed", "es", "s"
    );

    static final int DEFAULT_FILE_BUDGET = 10;

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
     * 1. Extract keywords, reduce each to its stem -> FTS5 prefix search per stem (seed set)
     * 2. Rank seeds by how many distinct keywords they match
     * 3. Rank the whole graph by personalised PageRank from the seeds, return the best nodes of
     *    the first {@code fileBudget} files
     */
    public List<Node> contextSearch(String taskDescription) {
        return contextSearch(taskDescription, DEFAULT_FILE_BUDGET);
    }

    /** Distinct keyword stems of a task: the seed queries {@link #contextSearch} runs. */
    public Set<String> keywordStems(String text) {
        var stems = new LinkedHashSet<String>();
        for (var keyword : extractKeywords(text)) stems.add(stem(keyword));
        return stems;
    }

    /** True when a keyword stem of the task matches a node contextSearch can seed from (not a library or test source). */
    public boolean hasKeywordSeed(String text) {
        for (var stem : keywordStems(text)) {
            for (var hit : store.searchFts(stem + "*")) {
                if (hit.type() != NodeType.LIBRARY && !TestPaths.isUnderSrcTest(hit.filePath())) return true;
            }
        }
        return false;
    }

    public List<Node> contextSearch(String taskDescription, int fileBudget) {
        var keywords = extractKeywords(taskDescription);
        if (keywords.isEmpty()) return List.of();

        // FTS5 prefix matching only reaches forward from the start of a token, so "cancellation*"
        // finds neither cancel() nor CancelledEvent: the query asks for the stem, "cancel*". Words
        // that share a stem are one keyword.
        var typedByStem = new LinkedHashMap<String, List<String>>();
        for (var keyword : keywords) {
            typedByStem.computeIfAbsent(stem(keyword), s -> new ArrayList<>()).add(keyword);
        }

        var seeds = new LinkedHashMap<String, Node>();
        var keywordsMatched = new HashMap<String, Integer>();
        var typedMatched = new HashMap<String, Integer>();
        typedByStem.forEach((prefix, typed) -> {
            var stemHits = store.searchFts(prefix + "*");
            for (var hit : stemHits) {
                seeds.putIfAbsent(hit.id(), hit);
                keywordsMatched.merge(hit.id(), 1, Integer::sum);
            }
            // A node that matches a word as it was typed says more than one that matches only its stem.
            var asTyped = new HashSet<String>();
            for (var word : typed) {
                for (var hit : word.equals(prefix) ? stemHits : store.searchFts(word + "*")) {
                    asTyped.add(hit.id());
                }
            }
            asTyped.forEach(id -> typedMatched.merge(id, 1, Integer::sum));
        });

        // A node matching "sales", "order" and "cancellation" says more about the task than one
        // matching only "sales", and one matching the words as typed more than one matching only
        // their stems. The sort is stable, so ties keep keyword order and a single keyword that is
        // its own stem ranks exactly as it did before.
        var ranked = new ArrayList<>(seeds.values());
        ranked.sort(Comparator.comparingInt((Node n) -> keywordsMatched.get(n.id()))
            .thenComparingInt(n -> typedMatched.getOrDefault(n.id(), 0)).reversed());

        return expand(ranked, keywordsMatched, typedMatched, fileBudget);
    }

    /**
     * Personalised PageRank from the seeds over the whole graph, then the best nodes until
     * {@code fileBudget} files are filled. A METHOD is merged into its declaring class for ranking
     * (a class and its methods are one place), so a place's score is its class's. Seed methods are
     * returned as themselves; a non-seed method never is, its class stands for it. Library nodes
     * and test sources are left out of the walk and of the answer.
     */
    private List<Node> expand(List<Node> seedsInKeywordOrder, Map<String, Integer> keywordsMatched,
                              Map<String, Integer> typedMatched, int fileBudget) {
        var all = new ArrayList<Node>();
        for (var node : store.getAllNodes()) {
            if (node.type() == NodeType.LIBRARY || TestPaths.isUnderSrcTest(node.filePath())) continue;
            all.add(node);
        }
        var seedList = new ArrayList<Node>();
        for (var seed : seedsInKeywordOrder) {
            if (seed.type() == NodeType.LIBRARY || TestPaths.isUnderSrcTest(seed.filePath())) continue;
            seedList.add(seed);
        }
        if (seedList.isEmpty()) return List.of();

        var edges = store.getAllEdges();
        var placeOf = new HashMap<String, String>();
        for (var edge : edges) {
            if (edge.type() == EdgeType.DECLARES_METHOD) placeOf.put(edge.targetId(), edge.sourceId());
        }
        var index = new HashMap<String, Integer>();
        for (var node : all) {
            String place = node.type() == NodeType.METHOD ? placeOf.getOrDefault(node.id(), node.id()) : node.id();
            index.putIfAbsent(place, index.size());
        }
        int n = index.size();
        var from = new int[edges.size()];
        var to = new int[edges.size()];
        int m = 0;
        for (var edge : edges) {
            if (edge.type() == EdgeType.DECLARES_METHOD) continue;
            Integer a = index.get(placeOf.getOrDefault(edge.sourceId(), edge.sourceId()));
            Integer b = index.get(placeOf.getOrDefault(edge.targetId(), edge.targetId()));
            if (a == null || b == null) continue;
            from[m] = a;
            to[m++] = b;
        }
        var personalisation = new double[n];
        for (var seed : seedList) {
            Integer place = index.get(placeOf.getOrDefault(seed.id(), seed.id()));
            if (place != null) personalisation[place] += keywordsMatched.get(seed.id());
        }
        var scores = PersonalizedPageRank.rank(n, Arrays.copyOf(from, m), Arrays.copyOf(to, m), personalisation);
        ToDoubleFunction<Node> score = node -> {
            Integer place = index.get(placeOf.getOrDefault(node.id(), node.id()));
            return place == null ? 0 : scores[place];
        };

        // Seeds keep their keyword order; PageRank breaks ties. A non-seed never outranks a seed.
        var ordered = new ArrayList<Node>(seedList);
        ordered.sort(Comparator.comparingInt((Node x) -> keywordsMatched.get(x.id()))
            .thenComparingInt(x -> typedMatched.getOrDefault(x.id(), 0))
            .thenComparingDouble(score).reversed());
        var seedIds = new HashSet<String>();
        for (var seed : seedList) seedIds.add(seed.id());
        var rest = new ArrayList<Node>();
        for (var node : all) {
            if (node.type() != NodeType.METHOD && !seedIds.contains(node.id()) && score.applyAsDouble(node) > 0) rest.add(node);
        }
        rest.sort(Comparator.comparingDouble(score).reversed());
        ordered.addAll(rest);

        // Fill files in rank order; a node of a file already taken adds no file, so it comes along.
        var files = new HashSet<String>();
        var result = new ArrayList<Node>();
        for (var node : ordered) {
            if (!files.contains(node.filePath())) {
                if (files.size() >= fileBudget) continue;
                files.add(node.filePath());
            }
            result.add(node);
        }
        return result;
    }

    /**
     * Reduces a word to the prefix its other forms share: {@code cancellation}, {@code cancelled},
     * {@code cancelling} and {@code cancels} all become {@code cancel}. FTS5 prefix matching only
     * reaches forward from the start of a token, so a prefix is all a query can say about a word's
     * other forms. The stem is always a prefix of the word, so it never matches less than the word
     * did. A suffix comes off only if {@value #MIN_STEM} characters remain; a doubled consonant it
     * leaves behind is undoubled ({@code cancell} becomes {@code cancel}), but only then: "install"
     * keeps both l.
     */
    public static String stem(String word) {
        for (String suffix : SUFFIXES) {
            if (!word.endsWith(suffix)) continue;
            // "class", "status" and "analysis" end in s without being plurals.
            if (suffix.equals("s") && (word.endsWith("ss") || word.endsWith("us") || word.endsWith("is"))) continue;
            String stem = word.substring(0, word.length() - suffix.length());
            if (stem.length() < MIN_STEM) continue;
            return undouble(stem);
        }
        return word;
    }

    private static String undouble(String stem) {
        int last = stem.length() - 1;
        char c = stem.charAt(last);
        boolean doubled = c == stem.charAt(last - 1) && Character.isLetter(c) && "aeious".indexOf(c) < 0;
        return doubled && last >= MIN_STEM ? stem.substring(0, last) : stem;
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
