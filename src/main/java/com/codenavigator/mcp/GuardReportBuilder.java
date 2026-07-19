package com.codenavigator.mcp;

import com.codenavigator.domain.DomainSqliteStore;
import com.codenavigator.domain.model.BoundedContext;
import com.codenavigator.domain.model.BusinessRule;
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
    private final DomainSqliteStore domainStore;

    public GuardReportBuilder(GraphStore graphStore, GraphTraversal traversal, DomainSqliteStore domainStore) {
        this.graphStore = graphStore;
        this.traversal = traversal;
        this.domainStore = domainStore;
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
        sb.append("## Guard Report: `").append(symbolName).append("`\n\n");

        // (a) Blast radius
        sb.append("### Blast Radius (depth ").append(depth).append(")\n\n");
        sb.append(impacted.size()).append(" node(s) affected:\n\n");
        for (Node n : impacted) {
            String marker = DDD_HIGHLIGHT_TYPES.contains(n.type()) ? " ⚠" : "";
            sb.append("- **").append(n.type()).append("**").append(marker)
              .append(" `").append(n.name()).append("`")
              .append(" (").append(n.filePath()).append(":").append(n.lineNumber()).append(")\n");
        }
        sb.append("\n");

        // Check whether this is a DDD-tier project
        String tier = graphStore.getConfig("tier");
        boolean isDdd = "DDD".equalsIgnoreCase(tier);

        if (!isDdd) {
            sb.append("> _Non-DDD tier (`").append(tier != null ? tier : "unknown")
              .append("`) — domain context and rules sections skipped._\n");
            return sb.toString();
        }

        // (b) DDD node-type grouping (includes the start node so the edited symbol shows)
        sb.append("### DDD Node Types Touched\n\n");
        Map<NodeType, List<Node>> byType = analyzed.stream()
            .collect(Collectors.groupingBy(Node::type, LinkedHashMap::new, Collectors.toList()));

        // DDD highlight types first, then the rest
        List<NodeType> orderedTypes = new ArrayList<>(byType.keySet());
        orderedTypes.sort(Comparator.comparingInt(
            t -> DDD_HIGHLIGHT_TYPES.contains(t) ? 0 : 1));

        for (NodeType type : orderedTypes) {
            String callout = DDD_HIGHLIGHT_TYPES.contains(type) ? " ⚠ DDD-significant" : "";
            sb.append("#### ").append(type).append(callout).append("\n");
            for (Node n : byType.get(type)) {
                sb.append("- `").append(n.name()).append("` (")
                  .append(n.filePath()).append(":").append(n.lineNumber()).append(")\n");
            }
        }
        sb.append("\n");

        // (c) Bounded contexts touched
        Set<String> nodeNames = analyzed.stream().map(Node::name).collect(Collectors.toSet());

        List<BoundedContext> contexts = domainStore.findContextsContaining(nodeNames);
        sb.append("### Bounded Contexts Touched\n\n");
        if (contexts.isEmpty()) {
            sb.append("_No bounded context registered for these symbols._\n\n");
        } else {
            for (BoundedContext ctx : contexts) {
                sb.append("- **").append(ctx.name()).append("**");
                if (ctx.owner() != null) sb.append(" (owner: ").append(ctx.owner()).append(")");
                sb.append("\n");
            }
            sb.append("\n");
        }

        // (d) Matching domain rules
        List<BusinessRule> rules = domainStore.findRulesMentioning(nodeNames);
        sb.append("### Matching Domain Rules\n\n");
        if (rules.isEmpty()) {
            sb.append("_No domain rules registered for these symbols._\n\n");
        } else {
            for (BusinessRule rule : rules) {
                sb.append("- **[").append(rule.severity()).append("]** `").append(rule.name()).append("`");
                if (rule.entity() != null) sb.append(" — entity: `").append(rule.entity()).append("`");
                sb.append("\n");
                sb.append("  > ").append(rule.description()).append("\n");
                sb.append("  > Invariant: `").append(rule.invariant()).append("`\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
