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

    private static final int DIRECT_CALLER_ROWS = 10;

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
        return build(nodeId, depth, dependents(nodeId, depth));
    }

    /** The nodes the single-symbol guard counts: what depends on the node, the node itself left out. */
    public List<Node> dependents(String nodeId, int depth) {
        return graphStore.findNodeById(nodeId)
            .map(n -> callersOf(n, depth).stream().filter(c -> !c.id().equals(n.id())).toList())
            .orElse(List.of());
    }

    /** The text {@code cg_guard} answers for an exact node id, cap included; the benchmark measures this. */
    public String cappedReply(String nodeId, int depth) {
        return CodeNavigatorMcpServer.truncateOutput(build(nodeId, depth), "cg_guard");
    }

    private String build(String nodeId, int depth, List<Node> impacted) {
        var startNode = graphStore.findNodeById(nodeId);
        String symbolName = startNode.map(Node::name).orElse(nodeId);

        // "Is X safe to change" asks what depends on X, so the count is X's dependents (callersOf, the
        // same walk the diff guard uses), never the both-direction impact() walk: that one added what X
        // itself calls, every sibling method of each class it touched and LIBRARY nodes (cg_guard
        // GraphStore said 1,356 nodes where about 566 really depend on it at depth 2; the tiny TestPaths
        // said 601). The start node is never counted, but the DDD grouping and domain lookups include
        // it — the symbol you are about to edit is the most important thing the guard should describe.
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
        // cg_related direction in lists these same dependents; for a class it shows types, without the methods the count above includes,
        // so the limit that shows every row is the number of those rows. It shows 10 by default and no
        // reply holds more than the output cap, so this points at the rows and does not promise them all.
        if (!impacted.isEmpty()) {
            int rows = CodeNavigatorMcpServer.applyGranularity(impacted, Map.of(), startNode.orElse(null)).size();
            boolean methodsOnly = rows == 0;
            sb.append("\nMore: cg_related(symbol, direction in, depth ").append(depth);
            if (methodsOnly) sb.append(", granularity all");
            // With granularity all the reply also lists the class's own declared methods (the free
            // DECLARES_METHOD hop), so those count toward the limit that shows every row.
            int limit = rows;
            if (methodsOnly) {
                var ids = impacted.stream().map(Node::id).collect(Collectors.toSet());
                long own = startNode.map(n -> graphStore.findEdgesFrom(n.id()).stream()
                    .filter(e -> e.type() == EdgeType.DECLARES_METHOD && !ids.contains(e.targetId()))
                    .map(Edge::targetId).distinct().count()).orElse(0L);
                limit = impacted.size() + (int) own;
            }
            sb.append(", limit ").append(limit).append(")");
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
     * The guard report for a set of changed nodes: what changed, the union of their callers, and the
     * test classes among them. The blast radius follows callers only (the single-symbol guard also
     * walks callees, which would count tests that merely share a dependency with the change).
     *
     * @param limit  most changed rows shown
     * @param budget characters the reply may take; the changed rows shrink to fit and say so
     */
    public String buildForDiff(com.codenavigator.git.DiffSymbols.Result diff, int depth, int limit, int budget) {
        var changed = new ArrayList<Node>();
        for (String id : diff.nodeIds()) graphStore.findNodeById(id).ifPresent(changed::add);
        var changedIds = changed.stream().map(Node::id).collect(Collectors.toSet());

        // Callers of every changed node, plus the class declaring each calling method, so a caller
        // that is an aggregate is named as one and a test class is counted as a class.
        var union = new LinkedHashMap<String, Node>();
        for (var c : changed) {
            for (var n : callersOf(c, depth)) {
                if (changedIds.contains(n.id())) continue;
                union.putIfAbsent(n.id(), n);
                if (MethodIds.isMethodId(n.id())) {
                    String cls = MethodIds.declaringClass(n.id());
                    if (!changedIds.contains(cls)) {
                        graphStore.findNodeById(cls).ifPresent(k -> union.putIfAbsent(k.id(), k));
                    }
                }
            }
        }
        // Direct callers: depth 1 only, production code only. Test callers are what Tests reached is for.
        var direct = new LinkedHashMap<String, Node>();
        for (var c : changed) {
            for (var n : callersOf(c, 1)) {
                if (changedIds.contains(n.id()) || isTestSource(n)) continue;
                direct.putIfAbsent(n.id(), n);
            }
        }
        var directCallers = direct.values().stream().toList();
        var impacted = new ArrayList<>(union.values());
        var production = impacted.stream().filter(n -> !isTestSource(n)).toList();
        var testClasses = new LinkedHashMap<String, Node>();
        for (var n : impacted) {
            if (!isTestSource(n)) continue;
            String id = MethodIds.isMethodId(n.id()) ? MethodIds.declaringClass(n.id()) : n.id();
            graphStore.findNodeById(id).ifPresent(k -> testClasses.putIfAbsent(k.id(), k));
        }

        int rows = Math.min(limit, changed.size());
        int callerRows = Math.min(DIRECT_CALLER_ROWS, directCallers.size());
        // Shrink order over budget: changed rows first, then direct-caller rows.
        while (true) {
            String text = renderDiff(diff, changed, directCallers, impacted, production,
                testClasses.values().stream().toList(), depth, rows, limit, callerRows);
            if (text.length() <= budget || (rows == 0 && callerRows == 0)) return text;
            if (rows > 0) rows--; else callerRows--;
        }
    }

    /**
     * Callers of one changed node. A method keeps its plain caller walk. For a type, the walk from the
     * class would cross the free DECLARES_METHOD hop into every method of each dependent class, so only
     * its class-level rows are kept (the types with an edge into it) and the methods come from walking
     * the callers of the type's own methods, which are the ones that really call into it.
     */
    private List<Node> callersOf(Node changed, int depth) {
        if (MethodIds.isMethodId(changed.id())) return traversal.callers(changed.id(), depth);
        var result = new LinkedHashMap<String, Node>();
        for (var n : traversal.callers(changed.id(), depth)) {
            if (!MethodIds.isMethodId(n.id())) result.putIfAbsent(n.id(), n);
        }
        for (var e : graphStore.findEdgesFrom(changed.id())) {
            if (e.type() != EdgeType.DECLARES_METHOD) continue;
            for (var n : traversal.callers(e.targetId(), depth)) result.putIfAbsent(n.id(), n);
        }
        return new ArrayList<>(result.values());
    }

    private String renderDiff(com.codenavigator.git.DiffSymbols.Result diff, List<Node> changed,
                              List<Node> directCallers, List<Node> impacted, List<Node> production,
                              List<Node> testClasses, int depth, int rows, int limit, int callerRows) {
        var sb = new StringBuilder();
        sb.append("## Guard: changes against ").append(diff.ref()).append("\n");
        sb.append(diff.changedFiles()).append(" file(s) changed, ").append(changed.size())
          .append(" indexed symbol(s) touched");
        if (diff.filesWithoutNode() > 0) {
            sb.append(", ").append(diff.filesWithoutNode()).append(" file(s) with no symbol");
        }
        if (diff.newMethods() > 0) {
            sb.append(", ").append(diff.newMethods()).append(" new method(s) not indexed yet");
        }
        sb.append("\n\n");

        sb.append("### Changed (").append(changed.size()).append(")\n");
        DirectoryRows.append(sb, changed.subList(0, rows), Node::filePath, (node, file) ->
            DirectoryRows.nodeRow(node, file, true)
                + (com.codenavigator.git.DiffSymbols.REMOVED.equals(diff.reasons().get(node.id())) ? " — removed" : ""));
        if (changed.size() > rows) {
            sb.append("… ").append(changed.size() - rows).append(" more changed symbol(s). ")
              .append(rows < Math.min(limit, changed.size())
                  ? "`limit` lowered to fit the reply cap.\n" : "Raise `limit`.\n");
        }

        sb.append("\n### Direct callers (").append(directCallers.size()).append(")\n");
        if (directCallers.isEmpty()) {
            sb.append("No production code calls the changed symbols directly.\n");
        } else {
            DirectoryRows.appendNodes(sb, directCallers.subList(0, callerRows), true);
            if (directCallers.size() > callerRows) {
                sb.append("… ").append(directCallers.size() - callerRows).append(" more direct caller(s).\n");
            }
        }

        sb.append("\n### Blast radius (callers, depth ").append(depth).append("): ")
          .append(impacted.size()).append(" node(s)\n");
        var significant = production.stream().filter(n -> DDD_HIGHLIGHT_TYPES.contains(n.type())).toList();
        if (!significant.isEmpty()) {
            sb.append("DDD-significant (").append(significant.size()).append("):\n");
            DirectoryRows.appendNodes(sb, significant.stream().limit(12).toList(), true);
            if (significant.size() > 12) sb.append("- … ").append(significant.size() - 12).append(" more\n");
        }
        String otherCounts = production.stream()
            .filter(n -> !DDD_HIGHLIGHT_TYPES.contains(n.type()))
            .collect(Collectors.groupingBy(Node::type, LinkedHashMap::new, Collectors.counting()))
            .entrySet().stream()
            .sorted(Map.Entry.<NodeType, Long>comparingByValue().reversed())
            .map(e -> e.getKey() + " " + e.getValue())
            .collect(Collectors.joining(", "));
        if (!otherCounts.isEmpty()) {
            sb.append(significant.isEmpty() ? "Production: " : "Other production: ").append(otherCounts).append("\n");
        }
        long testCount = impacted.size() - production.size();
        if (testCount > 0) sb.append("Test code: ").append(testCount).append(" node(s)\n");

        sb.append("\n### Tests reached (").append(testClasses.size()).append(")\n");
        if (testClasses.isEmpty()) {
            sb.append("No test reaches the changed symbols.\n");
        } else {
            DirectoryRows.appendNodes(sb, testClasses.stream().limit(5).toList(), false);
            if (testClasses.size() > 5) {
                sb.append("… ").append(testClasses.size() - 5).append(" more test class(es).\n");
            }
        }

        if (!changed.isEmpty()) {
            sb.append("\nMore: cg_related(symbol ").append(MethodIds.shortLabel(changed.get(0).id()))
              .append(", direction in, depth ").append(depth).append(")");
            if (changed.size() > 1) sb.append(" (repeat per changed symbol)");
            sb.append(".\n");
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
