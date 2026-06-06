package com.codenavigator.benchmark;

import com.codenavigator.briefing.BriefingGenerator;
import com.codenavigator.export.ExportService;
import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.GraphTraversal;
import com.codenavigator.graph.Node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the three fixed benchmark scenarios and returns one {@link BenchmarkRow} per scenario.
 *
 * <p>Scenarios:
 * <ol>
 *   <li><b>whole-project</b> — all raw source files vs full briefing (.ai-briefing/ dir)</li>
 *   <li><b>symbol-impact</b> — raw files in blast radius vs cg_impact formatted output</li>
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
        rows.add(runWholeProject());
        rows.add(runSymbolImpact());
        rows.add(runCompactExport());
        return rows;
    }

    // ---- Scenario 1: whole-project ----

    private BenchmarkRow runWholeProject() {
        // Baseline: concatenate all raw source files
        int baselineTokens = estimator.estimate(readAllSourceFiles());

        // Navigator: full briefing written to tempDir, then read back
        int navigatorTokens;
        try {
            Path briefingDir = tempDir.resolve("briefing-whole");
            Files.createDirectories(briefingDir);
            new BriefingGenerator(store, null).generate(briefingDir);
            String briefingContent = readDirectoryContents(briefingDir);
            navigatorTokens = estimator.estimate(briefingContent);
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate briefing for whole-project scenario", e);
        }

        return makeRow("whole-project", baselineTokens, navigatorTokens);
    }

    // ---- Scenario 2: symbol-impact ----

    private BenchmarkRow runSymbolImpact() {
        // Pick the node with the highest fan-in as the representative symbol
        List<GraphStore.NodeCount> fanIn = store.findTopFanIn(1);
        if (fanIn.isEmpty()) {
            // Fallback: any node will do
            List<Node> all = store.getAllNodes();
            if (all.isEmpty()) return makeRow("symbol-impact", 0, 0);
            return runSymbolImpactFor(all.get(0).id());
        }
        return runSymbolImpactFor(fanIn.get(0).node().id());
    }

    private BenchmarkRow runSymbolImpactFor(String nodeId) {
        List<Node> impactNodes = traversal.impact(nodeId, 2);

        // Baseline: raw content of all distinct files in the blast radius
        List<String> impactFiles = impactNodes.stream()
            .map(Node::filePath)
            .distinct()
            .toList();
        String baselineContent = readFiles(impactFiles);
        int baselineTokens = estimator.estimate(baselineContent);

        // Navigator: formatted impact report (mirrors CodeNavigatorMcpServer.handleCgImpact)
        Node focusNode = store.findNodeById(nodeId).orElseThrow();
        StringBuilder sb = new StringBuilder();
        sb.append("## Impact of ").append(focusNode.name()).append(" (depth 2)\n\n");
        sb.append(impactNodes.size()).append(" affected node(s):\n\n");
        for (var node : impactNodes) {
            sb.append("- **").append(node.type()).append("** `").append(node.name())
              .append("` (").append(node.filePath()).append(":").append(node.lineNumber()).append(")\n");
        }
        int navigatorTokens = estimator.estimate(sb.toString());

        return makeRow("symbol-impact", baselineTokens, navigatorTokens);
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
