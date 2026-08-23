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
        // counted, with cg_impact available when the full list is actually wanted.
        sb.append("### Blast radius (depth ").append(depth).append("): ")
          .append(impacted.size()).append(" node(s)\n");

        var production = impacted.stream().filter(n -> !isTestSource(n)).toList();
        var significant = production.stream()
            .filter(n -> DDD_HIGHLIGHT_TYPES.contains(n.type()))
            .toList();

        if (!significant.isEmpty()) {
            sb.append("DDD-significant (").append(significant.size()).append("):\n");
            // Short name plus file:line, as every other tool renders a node — the path already
            // carries the package, so repeating the fqn spends tokens on nothing.
            significant.stream().limit(12).forEach(n ->
                sb.append("- **").append(n.type()).append("** `").append(n.name())
                  .append("` (").append(n.filePath()).append(":").append(n.lineNumber()).append(")\n"));
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
            sb.append("Test sources: ").append(testCount).append(" (not counted above)\n");
        }
        sb.append("\nFull list: cg_impact(symbol, depth ").append(depth).append(").\n");

        if (!isDdd) {
            sb.append("\n> _Non-DDD tier (`").append(tier != null ? tier : "unknown")
              .append("`) — domain context and rules sections skipped._\n");
        }
        return sb.toString();
    }

    /** Source that only tests reach is rarely what a guard is warning about. */
    private static boolean isTestSource(Node node) {
        String path = node.filePath();
        if (path == null) return false;
        return path.contains("/src/test/") || path.contains("\\src\\test\\")
            || path.endsWith("Test.java") || path.endsWith(".spec.ts") || path.contains("/e2e/");
    }




}
