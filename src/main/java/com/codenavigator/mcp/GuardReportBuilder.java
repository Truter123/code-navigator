package com.codenavigator.mcp;

import com.codenavigator.graph.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds a markdown guard report for a symbol before an edit.
 * Combines blast-radius graph traversal with DDD node-type grouping,
 * bounded-context lookup, and matching business rules.
 */
public class GuardReportBuilder {

    // Node types that carry DDD significance — called out with a warning marker.
    private static final Set<NodeType> DDD_HIGHLIGHT_TYPES = Set.of(
        NodeType.AGGREGATE,
        NodeType.DOMAIN_EVENT,
        NodeType.COMMAND,
        NodeType.COMMAND_HANDLER,
        NodeType.PROJECTION_HANDLER
    );

    private final GraphStore graphStore;
    private final GraphTraversal traversal;

    public GuardReportBuilder(GraphStore graphStore, GraphTraversal traversal) {
        this.graphStore = graphStore;
        this.traversal = traversal;
    }

    /**
     * Build the guard report for the given node id and traversal depth.
     *
     * @param nodeId resolved node id (not a display name)
     * @param depth  blast-radius traversal depth
     * @return markdown string
     */
    public String build(String nodeId, int depth) {
        var startNode = graphStore.findNodeById(nodeId);
        String symbolName = startNode.map(Node::name).orElse(nodeId);

        // impact() excludes the start node itself. The blast-radius count reflects
        // only the affected neighbours, but the DDD grouping and domain lookups
        // include the start node — the symbol you are about to edit is the most
        // important thing the guard should describe.
        List<Node> impacted = traversal.impact(nodeId, depth);
        List<Node> analyzed = new ArrayList<>();
        startNode.ifPresent(analyzed::add);
        analyzed.addAll(impacted);

        var sb = new StringBuilder();
        sb.append("## Guard: `")
          .append(startNode.map(n -> MethodIds.shortLabel(n.id())).orElse(symbolName)).append("`\n");
        startNode.ifPresent(n ->
            sb.append(n.type()).append(" — ").append(n.filePath()).append(":").append(n.lineNumber()).append("\n"));
        sb.append("\n");

        String tier = graphStore.getConfig("tier");
        boolean isDdd = "DDD".equalsIgnoreCase(tier);

        // Blast radius, summarised. Listing every node made this report 135 lines on a single
        // method, most of them test cases; the DDD-significant nodes are named and the rest are
        // counted, with cg_related one call away when the full list is actually wanted.
        sb.append("### Blast radius (depth ").append(depth).append("): ")
          .append(impacted.size()).append(" node(s)\n");

        var production = impacted.stream().filter(n -> !isTestSource(n)).toList();
        var significant = production.stream()
            .filter(n -> DDD_HIGHLIGHT_TYPES.contains(n.type()))
            .toList();

        if (!significant.isEmpty()) {
            sb.append("DDD-significant (").append(significant.size()).append("):\n");
            // Same rows as every other list tool: the directory once, then File.java:line Name TYPE.
            // The path already carries the package, so repeating the fqn spends tokens on nothing.
            DirectoryRows.appendNodes(sb, significant.stream().limit(12).toList(), true);
            if (significant.size() > 12) {
                sb.append("- … ").append(significant.size() - 12).append(" more\n");
            }
        }

        String otherCounts = production.stream()
            .filter(n -> !DDD_HIGHLIGHT_TYPES.contains(n.type()))
            .collect(Collectors.groupingBy(Node::type, LinkedHashMap::new, Collectors.counting()))
            .entrySet().stream()
            .sorted(Map.Entry.<NodeType, Long>comparingByValue().reversed())
            .map(e -> e.getKey() + " " + e.getValue())
            .collect(Collectors.joining(", "));
        if (!otherCounts.isEmpty()) sb.append("Other: ").append(otherCounts).append("\n");

        long testCount = impacted.size() - production.size();
        if (testCount > 0) {
            sb.append("Test sources: ").append(testCount).append("\n");
        }
        // cg_related lists a class's blast radius as types, without the methods the count above includes,
        // so the limit that shows every row is the number of those rows. It shows 10 by default and no
        // reply holds more than the output cap, so this points at the rows and does not promise them all.
        if (!impacted.isEmpty()) {
            int rows = CodeNavigatorMcpServer.applyGranularity(impacted, Map.of(), startNode.orElse(null)).size();
            boolean methodsOnly = rows == 0;
            sb.append("\nMore: cg_related(symbol, depth ").append(depth);
            if (methodsOnly) sb.append(", granularity all");
            sb.append(", limit ").append(methodsOnly ? impacted.size() : rows).append(")");
            if (!methodsOnly && rows < impacted.size()) sb.append(", methods left out");
            sb.append(".\n");
        }

        if (!isDdd) {
            sb.append("\n> _Non-DDD tier (`").append(tier != null ? tier : "unknown")
              .append("`) — domain context and rules sections skipped._\n");
        }
        return sb.toString();
    }

    /**
     * Source that only tests reach is rarely what a guard is warning about. Node paths are
     * repo-relative, so a directory rule cannot require a leading slash.
     */
    private static boolean isTestSource(Node node) {
        String path = node.filePath();
        if (path == null) return false;
        String p = path.replace('\\', '/');
        return TestPaths.isUnderSrcTest(p)
            || p.endsWith("Test.java") || p.endsWith(".spec.ts") || p.startsWith("e2e/") || p.contains("/e2e/");
    }




}
