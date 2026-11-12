package com.codenavigator.benchmark;

import com.codenavigator.export.ExportService;
import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.GraphTraversal;
import com.codenavigator.graph.MethodIds;
import com.codenavigator.graph.Node;
import com.codenavigator.graph.NodeType;
import com.codenavigator.mcp.ContextReport;
import com.codenavigator.mcp.GuardReportBuilder;
import com.codenavigator.mcp.TestPaths;
import com.codenavigator.search.SearchService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Runs the fixed benchmark scenarios and returns one {@link BenchmarkRow} per scenario.
 *
 * <p>Scenarios:
 * <ol>
 *   <li><b>symbol-impact</b> — the files of a symbol's dependents read whole vs the cg_guard reply</li>
 *   <li><b>compact-export</b> — all raw source files vs compact JSON export</li>
 * </ol>
 */
public class BenchmarkRunner {

    private final GraphStore store;
    private final GraphTraversal traversal;
    private final Path tempDir;
    private final TokenEstimator estimator;
    private final Path projectRoot;
    private final ExportService exportService = new ExportService();
    /** cg_context default {@code limit}. */
    private static final int DEFAULT_CONTEXT_LIMIT = 10;
    private static final int SYMBOL_IMPACT_DEPTH = 2;
    private String lastSymbolImpactNodeId = "";
    private String lastSymbolImpactText = "";
    private String lastSymbolImpactNote = "";
    private ContextReport.Reply lastSemanticReply;
    private List<String> lastSemanticBaselineFiles = List.of();
    private String lastSemanticNote = "";

    /**
     * @param store       open {@link GraphStore} for the target project
     * @param traversal   {@link GraphTraversal} over that store
     * @param tempDir     writable temporary directory for briefing file generation
     * @param estimator   token estimator to use
     * @param projectRoot project root used to resolve the relative file paths stored in the graph
     *                    (the indexer stores paths via {@code projectPath.relativize(file)})
     */
    public BenchmarkRunner(GraphStore store, GraphTraversal traversal, Path tempDir,
                           TokenEstimator estimator, Path projectRoot) {
        this.store = store;
        this.traversal = traversal;
        this.tempDir = tempDir;
        this.estimator = estimator;
        this.projectRoot = projectRoot;
    }

    /**
     * Run all scenarios and return results in insertion order.
     */
    public List<BenchmarkRow> run() {
        List<BenchmarkRow> rows = new ArrayList<>();
        rows.add(runSymbolImpact());
        rows.add(runCompactExport());
        return rows;
    }

    /**
     * Optional 4th scenario: tokens to answer a natural-language query.
     * Navigator = the exact cg_context reply for the query at the default limit.
     * Baseline = what an agent reads without it: every file the keyword search hits (same keywords
     * and stems as cg_context) plus every file the reply names, each read whole once.
     */
    public BenchmarkRow runSemanticContext(SearchService hybridSearch, String query) {
        var reply = semanticContextReply(hybridSearch, query);

        var files = new LinkedHashSet<String>();
        for (String stem : hybridSearch.keywordStems(query)) {
            for (Node n : store.searchFts(stem + "*")) files.add(n.filePath());
        }
        files.removeIf(f -> f == null || f.isEmpty() || TestPaths.isUnderSrcTest(f));
        int fromKeywordHits = files.size();
        files.addAll(reply.files());
        files.removeIf(f -> f == null || f.isEmpty() || TestPaths.isUnderSrcTest(f));

        int baselineTokens = estimator.estimate(readFiles(new ArrayList<>(files)));
        int navigatorTokens = estimator.estimate(reply.text());
        lastSemanticReply = reply;
        lastSemanticBaselineFiles = List.copyOf(files);
        if (!hybridSearch.hasKeywordSeed(query)) {
            lastSemanticNote = "semantic-context: the query \"" + query
                + "\" matches nothing in this project; pass --query \"<a task that fits it>\".";
            return new BenchmarkRow("semantic-context", baselineTokens, navigatorTokens, 0.0, false);
        }
        lastSemanticNote = "semantic-context baseline: " + fromKeywordHits + " file(s) from keyword hits, "
            + (files.size() - fromKeywordHits) + " more named by the reply.";
        return makeRow("semantic-context", baselineTokens, navigatorTokens);
    }

    /** The cg_context reply the semantic-context scenario measures, at the default limit. */
    private static ContextReport.Reply semanticContextReply(SearchService search, String query) {
        return ContextReport.forTask(search, query, DEFAULT_CONTEXT_LIMIT);
    }

    /** Navigator text of the last {@link #runSemanticContext} call. */
    public String lastSemanticNavigatorText() {
        return lastSemanticReply == null ? "" : lastSemanticReply.text();
    }

    /** The line to print under the table for the last {@link #runSemanticContext} call. */
    public String lastSemanticNote() {
        return lastSemanticNote;
    }

    /** Files the baseline of the last {@link #runSemanticContext} call read. */
    public List<String> lastSemanticBaselineFiles() {
        return lastSemanticBaselineFiles;
    }

    // ---- Scenario 1: whole-project ----


    // ---- Scenario 2: symbol-impact ----

    private BenchmarkRow runSymbolImpact() {
        // The symbol with the most incoming edges that an agent would edit: a LIBRARY node has the
        // highest fan-in on most projects, and nobody asks whether a library is safe to change.
        for (GraphStore.NodeCount candidate : store.findTopFanIn(20)) {
            Node node = candidate.node();
            if (node.type() != NodeType.LIBRARY && node.filePath() != null) return runSymbolImpactFor(node.id());
        }
        for (Node node : store.getAllNodes()) {
            if (node.type() != NodeType.LIBRARY && node.filePath() != null) return runSymbolImpactFor(node.id());
        }
        return makeRow("symbol-impact", 0, 0);
    }

    /**
     * Navigator = the exact {@code cg_guard} reply for the symbol at the default depth, cap included.
     * Baseline = what answers "what depends on this" without the graph: the symbol's own file and
     * every file one of its dependents sits in, each read whole once. It used to print one line per
     * node of the both-direction walk through a formatter no tool has: 228,230 "navigator" tokens on
     * nlp-be for a question the real tool answers in a few hundred.
     */
    private BenchmarkRow runSymbolImpactFor(String nodeId) {
        var guard = new GuardReportBuilder(store, traversal);
        Node focusNode = store.findNodeById(nodeId).orElseThrow();
        List<Node> dependents = guard.dependents(nodeId, SYMBOL_IMPACT_DEPTH);

        var files = new LinkedHashSet<String>();
        files.add(focusNode.filePath());
        for (Node node : dependents) {
            if (node.filePath() != null) files.add(node.filePath());
        }
        int baselineTokens = estimator.estimate(readFiles(new ArrayList<>(files)));

        lastSymbolImpactNodeId = nodeId;
        lastSymbolImpactText = guard.cappedReply(nodeId, SYMBOL_IMPACT_DEPTH);
        int navigatorTokens = estimator.estimate(lastSymbolImpactText);
        lastSymbolImpactNote = "symbol-impact: cg_guard on `" + MethodIds.shortLabel(nodeId) + "`, "
            + dependents.size() + " dependent node(s); baseline reads " + files.size() + " file(s) whole.";
        return makeRow("symbol-impact", baselineTokens, navigatorTokens);
    }

    /** Id of the symbol the last symbol-impact run measured ("" before the first run). */
    public String lastSymbolImpactNodeId() {
        return lastSymbolImpactNodeId;
    }

    /** Navigator text of the last symbol-impact run: the cg_guard reply. */
    public String lastSymbolImpactText() {
        return lastSymbolImpactText;
    }

    /** The line to print under the table for the last symbol-impact run. */
    public String lastSymbolImpactNote() {
        return lastSymbolImpactNote;
    }

    // ---- Scenario 3: compact-export ----

    private BenchmarkRow runCompactExport() {
        // Baseline: same as whole-project — all raw source files
        int baselineTokens = estimator.estimate(readAllSourceFiles());

        // Navigator: JSON export of the full graph
        var allNodes = store.getAllNodes();
        var allEdges = store.getAllEdges();
        String tier = store.getConfig("tier");
        String jsonExport = exportService.toJson(allNodes, allEdges, tier);
        int navigatorTokens = estimator.estimate(jsonExport);

        return makeRow("compact-export", baselineTokens, navigatorTokens);
    }

    // ---- Helpers ----

    /** Read and concatenate the content of every indexed source file. */
    private String readAllSourceFiles() {
        return readFiles(store.getAllIndexedFiles());
    }

    /** Read and concatenate the content of a list of file paths. */
    private String readFiles(List<String> filePaths) {
        StringBuilder sb = new StringBuilder();
        for (String filePath : filePaths) {
            try {
                // Indexer stores paths relative to the project root; resolve() leaves
                // absolute paths unchanged, so this works for both forms.
                Path p = projectRoot.resolve(filePath);
                if (Files.isRegularFile(p)) {
                    sb.append(Files.readString(p));
                }
            } catch (IOException e) {
                // Skip unreadable files silently — they don't contribute to token count
            }
        }
        return sb.toString();
    }

    /** Read and concatenate all files written into a directory (non-recursive). */
    private String readDirectoryContents(Path dir) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (var stream = Files.list(dir)) {
            for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                sb.append(Files.readString(file));
            }
        }
        return sb.toString();
    }

    private BenchmarkRow makeRow(String scenario, int baselineTokens, int navigatorTokens) {
        double pct = baselineTokens == 0 ? 0.0
            : Math.round((1.0 - (double) navigatorTokens / baselineTokens) * 1000.0) / 10.0;
        return new BenchmarkRow(scenario, baselineTokens, navigatorTokens, pct);
    }
}
