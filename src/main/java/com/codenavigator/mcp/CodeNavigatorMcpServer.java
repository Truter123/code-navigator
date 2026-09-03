package com.codenavigator.mcp;

import com.codenavigator.cli.ProjectPaths;
import com.codenavigator.embedding.EmbeddingProvider;
import com.codenavigator.export.ExportService;
import com.codenavigator.graph.*;
import com.codenavigator.search.SearchService;
import tools.jackson.databind.json.JsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class CodeNavigatorMcpServer {

    private static final int MAX_OUTPUT_LENGTH = 15_000;

    /**
     * Everything a tool call needs, bound to one project root. Every handler works through a
     * scope rather than a field, so a single server process can answer for any indexed project
     * the caller names via {@code projectPath}.
     */
    record ProjectScope(GraphStore store, GraphTraversal traversal, SearchService search, Path root) {}

    /** Thrown when a {@code projectPath} argument names a directory with no code-navigator index. */
    static class ProjectNotIndexedException extends RuntimeException {
        ProjectNotIndexedException(String message) { super(message); }
    }

    private final ProjectScope defaultScope;
    private final EmbeddingProvider embeddingProvider;
    private final ExportService exportService = new ExportService();
    private final Map<String, ProjectScope> scopes = new HashMap<>();

    public CodeNavigatorMcpServer(GraphStore store, GraphTraversal traversal, SearchService searchService,
                                  EmbeddingProvider embeddingProvider) {
        var env = System.getenv("CODE_NAVIGATOR_PROJECT");
        this.defaultScope = new ProjectScope(store, traversal, searchService,
            Path.of(env != null ? env : "."));
        this.embeddingProvider = embeddingProvider;
    }

    public void start() {
        var transportProvider = new StdioServerTransportProvider(new JacksonMcpJsonMapper(JsonMapper.builder().build()));

        var server = McpServer.sync(transportProvider)
            .serverInfo("code-navigator", "0.1.0")
            .toolCall(
                Tool.builder()
                    .name("cg_map")
                    .description("Project shape: tier, node/edge/file counts, node types, and the aggregates or controllers everything hangs off. Start here.")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of("limit", propInt("Max roots to list (default 40)"))),
                        List.of()))
                    .build(),
                (exchange, request) -> guarded(() -> handleCgMapTool(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("cg_search")
                    .description("Find symbols by name or text. Pass files=true to list indexed files matching a pattern instead.")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of("query", propString("Symbol name, text, or file pattern"),
                               "files", propString("true to search file paths instead of symbols"),
                               "limit", propInt("Max results (default 30)"))),
                        List.of("query")))
                    .build(),
                (exchange, request) -> guarded(() -> handleCgSearchTool(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("cg_node")
                    .description("One symbol in detail: type, file:line, signature, and its direct edges. Takes a class, an fqn, or Class#method (e.g. Andon#resolve).")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of("symbol", propString("Class, fqn, or Class#method"),
                               "includeCode", propString("Include the code snippet (default true)"))),
                        List.of("symbol")))
                    .build(),
                (exchange, request) -> guarded(() -> handleCgNodeTool(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("cg_related")
                    .description("What connects to a symbol. direction=in (callers), out (callees), both (blast radius). Raise depth for a wider sweep; granularity picks class or method level.")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of("symbol", propString("Class, fqn, or Class#method"),
                               "direction", propString("in | out | both (default both)"),
                               "depth", propInt("Traversal depth (default 2)"),
                               "granularity", propString("class | method | all (default: matches the symbol)"),
                               "limit", propInt("Max results (default 30)"))),
                        List.of("symbol")))
                    .build(),
                (exchange, request) -> guarded(() -> handleCgRelated(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("cg_guard")
                    .description("Before editing a symbol: what it touches, which DDD-significant nodes are in range, and how much of the fallout is test-only.")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of("symbol", propString("Class, fqn, or Class#method"),
                               "depth", propInt("Blast-radius depth (default 2)"))),
                        List.of("symbol")))
                    .build(),
                (exchange, request) -> guarded(() -> handleCgGuard(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("cg_context")
                    .description("Given a task description, the files and symbols likely to matter. Use when you know what you want to do but not where it lives.")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of("task", propString("Task description, e.g. 'modify order creation'"),
                               "limit", propInt("Max files to list (default 20)"))),
                        List.of("task")))
                    .build(),
                (exchange, request) -> guarded(() -> handleCgContext(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("cg_health")
                    .description("Structural analysis. kind=hotspots (most depended-on), dead (unreferenced candidates), packages (inter-package deps and cycles), coupling (files that change together).")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of("kind", propString("hotspots | dead | packages | coupling"),
                               "symbol", propString("Symbol or file, for kind=coupling"),
                               "type", propString("NodeType filter, for kind=dead"),
                               "limit", propInt("Max results (default 10)"))),
                        List.of("kind")))
                    .build(),
                (exchange, request) -> guarded(() -> handleCgHealth(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("cg_deps")
                    .description("Declared build dependencies with a count of internal types touching each.")
                    .inputSchema(jsonSchema(withProjectPath(Map.of()), List.of()))
                    .build(),
                (exchange, request) -> guarded(() -> handleCgDeps(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("cg_export")
                    .description("Serialise the graph as json, mermaid or plantuml. Scope to a symbol's chain unless you want the whole project.")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of("format", propString("json | mermaid | plantuml"),
                               "symbol", propString("Scope to this symbol's chain (optional)"))),
                        List.of("format")))
                    .build(),
                (exchange, request) -> guarded(() -> handleCgExport(request.arguments()))
            )
            .build();

        // Block to keep stdio server alive
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            // Only secondary scopes are owned here; the default scope's stores belong to the caller.
            scopes.values().forEach(s -> {
                try { s.store().close(); } catch (Exception ignored) {}
            });
            server.close();
        }
    }

    // ---- Tool dispatchers ----
    // Nine tools over the handlers below. The split used to be twenty, which cost ~536 tokens of
    // standing context before a single call and made an agent choose between doors onto the same
    // room — cg_chain and cg_overview were literally the same traversal with different titles.

    String handleCgMapTool(Map<String, Object> args) {
        return handleCgStatus(args) + "\n" + handleCgMap(args);
    }

    String handleCgSearchTool(Map<String, Object> args) {
        boolean files = "true".equalsIgnoreCase(String.valueOf(args.get("files")));
        if (!files) return handleCgSearch(args);
        var forwarded = new HashMap<>(args);
        forwarded.put("pattern", args.get("query"));
        return handleCgFiles(forwarded);
    }

    /** A method reference gets the method view; anything else gets the type view. */
    String handleCgNodeTool(Map<String, Object> args) {
        String symbol = (String) args.get("symbol");
        if (MethodIds.isMethodId(symbol)) return handleCgMethod(args);
        return handleCgNode(args);
    }

    /**
     * One traversal tool. {@code in} and {@code out} follow call edges; {@code both} is the
     * undirected blast radius. Depth beyond 20 means "the whole connected component", which is what
     * the old cg_chain and cg_overview returned.
     */
    String handleCgRelated(Map<String, Object> args) {
        String direction = args.containsKey("direction")
            ? String.valueOf(args.get("direction")).toLowerCase(Locale.ROOT) : "both";
        int depth = getIntArg(args, "depth", 2);

        if (depth > 20) return handleCgChain(args);
        return switch (direction) {
            case "in" -> handleCgCallers(args);
            case "out" -> handleCgCallees(args);
            default -> handleCgImpact(args);
        };
    }

    String handleCgHealth(Map<String, Object> args) {
        String kind = args.containsKey("kind")
            ? String.valueOf(args.get("kind")).toLowerCase(Locale.ROOT) : "hotspots";
        return switch (kind) {
            case "dead" -> handleCgDead(args);
            case "packages" -> handleCgPackages(args);
            case "coupling" -> handleCgCoupling(args);
            case "hotspots" -> handleCgHotspots(args);
            default -> "Unknown kind: " + kind + ". Use hotspots, dead, packages or coupling.";
        };
    }


    // ---- Tool handlers (package-visible for testing) ----

    String handleCgChain(Map<String, Object> args) {
        var scope = resolveScope(args);
        String symbol = (String) args.get("symbol");
        String nodeId = resolveSymbol(scope.store(), symbol);
        if (nodeId == null) return "Symbol '" + symbol + "' not found.";
        int limit = getIntArg(args, "limit", 30);

        var start = scope.store().findNodeById(nodeId).orElse(null);
        var nodes = applyGranularity(scope.traversal().traceChain(nodeId), args, start);
        int total = nodes.size();
        var shown = total > limit ? nodes.subList(0, limit) : nodes;
        String title = "Chain for " + symbol + (total > limit ? " (showing " + limit + " of " + total + ")" : "");

        var sb = new StringBuilder();
        sb.append(ambiguityNote(candidatesFor(scope.store(), symbol)));
        sb.append(formatGroupedByType(title, shown));
        if (total > limit) {
            sb.append("\n… ").append(total - limit)
              .append(" more. Raise `limit` or a narrower `granularity`/`depth`.\n");
        }
        return sb.toString();
    }

    String handleCgImpact(Map<String, Object> args) {
        var scope = resolveScope(args);
        var store = scope.store();
        String symbol = (String) args.get("symbol");
        int depth = getIntArg(args, "depth", 2);
        int limit = getIntArg(args, "limit", 30);
        String nodeId = resolveSymbol(store, symbol);
        if (nodeId == null) return "Symbol '" + symbol + "' not found.";

        var originNode = store.findNodeById(nodeId).orElseThrow();
        String originFile = originNode.filePath();

        // Pre-fetch co-change counts for the origin file (up to 200 entries as ranking signal)
        var coupled = store.topCoupled(originFile, 200);
        Map<String, Integer> coChangeByFile = new java.util.HashMap<>();
        for (var pair : coupled) {
            coChangeByFile.put(pair.otherFile(), pair.count());
        }

        var nodes = applyGranularity(scope.traversal().impact(nodeId, depth), args, originNode);
        var sb = new StringBuilder();
        sb.append("## Impact of ").append(symbol).append(" (depth ").append(depth).append(")\n\n");
        sb.append(ambiguityNote(candidatesFor(store, symbol)));
        sb.append(nodes.size()).append(" affected node(s)");
        if (nodes.size() > limit) sb.append(" (showing ").append(limit).append(")");
        sb.append(":\n\n");
        nodes.stream().limit(limit).forEach(node -> {
            String line = formatNodeLine(node);
            int coCount = coChangeByFile.getOrDefault(node.filePath(), 0);
            if (coCount > 0) {
                line += " _(co-change: " + coCount + " commit(s))_";
            }
            sb.append(line).append("\n");
        });
        if (nodes.size() > limit) {
            sb.append("\n… ").append(nodes.size() - limit)
              .append(" more. Raise `limit` or narrow the `depth`.\n");
        }
        return sb.toString();
    }

    String handleCgGuard(Map<String, Object> args) {
        var scope = resolveScope(args);
        String symbol = (String) args.get("symbol");
        int depth = getIntArg(args, "depth", 2);
        String nodeId = resolveSymbol(scope.store(), symbol);
        if (nodeId == null) return "Symbol '" + symbol + "' not found.";

        var builder = new GuardReportBuilder(scope.store(), scope.traversal());
        return ambiguityNote(candidatesFor(scope.store(), symbol)) + builder.build(nodeId, depth);
    }

    String handleCgContext(Map<String, Object> args) {
        var scope = resolveScope(args);
        String task = (String) args.get("task");
        int limit = getIntArg(args, "limit", 20);
        var nodes = scope.search().contextSearch(task);
        if (nodes.isEmpty()) return "No relevant nodes found for task: " + task;

        var byFile = nodes.stream().collect(Collectors.groupingBy(
            Node::filePath, LinkedHashMap::new, Collectors.toList()));

        // One line per file with its symbols inline. The old shape spent a heading and a bullet
        // per node — ~3,800 tokens for a starting point the caller reads and then narrows.
        var sb = new StringBuilder();
        sb.append("## Context for: ").append(task).append("\n");
        sb.append(nodes.size()).append(" node(s) across ").append(byFile.size()).append(" file(s)");
        if (byFile.size() > limit) sb.append(" (showing ").append(limit).append(" files)");
        sb.append("\n\n");

        byFile.entrySet().stream().limit(limit).forEach(entry -> {
            sb.append("- ").append(entry.getKey()).append(" — ")
              .append(entry.getValue().stream()
                  .map(n -> n.type() + " " + n.name())
                  .limit(6)
                  .collect(Collectors.joining(", ")));
            if (entry.getValue().size() > 6) sb.append(", +").append(entry.getValue().size() - 6);
            sb.append("\n");
        });
        if (byFile.size() > limit) {
            sb.append("\n… ").append(byFile.size() - limit)
              .append(" more file(s). Raise `limit` or describe the task more narrowly.\n");
        }
        return sb.toString();
    }

    String handleCgSearch(Map<String, Object> args) {
        var scope = resolveScope(args);
        String query = (String) args.get("query");
        int limit = getIntArg(args, "limit", 30);
        var nodes = scope.search().search(query);
        if (nodes.isEmpty()) return "No results for query: " + query;

        // A broad term matches hundreds of nodes; returning all of them cost ~3,700 tokens for a
        // question the caller usually answers from the first few.
        var sb = new StringBuilder();
        sb.append("## ").append(nodes.size()).append(" match(es) for: ").append(query);
        if (nodes.size() > limit) sb.append(" (showing ").append(limit).append(")");
        sb.append("\n\n");
        nodes.stream().limit(limit).forEach(node -> sb.append(formatNodeLine(node)).append("\n"));
        if (nodes.size() > limit) {
            sb.append("\n… ").append(nodes.size() - limit)
              .append(" more. Raise `limit` or use a more specific query.\n");
        }
        return sb.toString();
    }

    String handleCgOverview(Map<String, Object> args) {
        var scope = resolveScope(args);
        String name = (String) args.get("name");
        String nodeId = resolveSymbol(scope.store(), name);
        if (nodeId == null) return "Symbol '" + name + "' not found.";

        var start = scope.store().findNodeById(nodeId).orElse(null);
        var nodes = applyGranularity(scope.traversal().traceChain(nodeId), args, start);
        return ambiguityNote(candidatesFor(scope.store(), name))
            + formatGroupedByType("Overview: " + name, nodes);
    }

    /**
     * Everything about one method: signature, where it lives, what it calls, who calls it, and how
     * it relates to a supertype. The answer an agent needs before editing a method body.
     */
    String handleCgMethod(Map<String, Object> args) {
        var scope = resolveScope(args);
        var store = scope.store();
        String symbol = (String) args.get("symbol");
        if (symbol == null || symbol.isBlank()) return "Missing required parameter: symbol";

        List<Node> matches = MethodIds.isMethodId(symbol)
            ? resolveMethodSymbol(store, symbol)
            : store.findNodesByName(symbol).stream().filter(n -> n.type() == NodeType.METHOD).toList();

        if (matches.isEmpty()) {
            return "No method matching '" + symbol + "'. Use Class#method, e.g. Andon#resolve.";
        }
        if (matches.size() > 1) {
            var sb = new StringBuilder("## " + matches.size() + " methods match '" + symbol + "'\n\n");
            matches.stream().limit(25).forEach(n -> sb.append("- `").append(n.id()).append("` (")
                .append(n.filePath()).append(":").append(n.lineNumber()).append(")\n"));
            if (matches.size() > 25) sb.append("- … ").append(matches.size() - 25).append(" more\n");
            sb.append("\nRe-run with the full id to pick one.\n");
            return sb.toString();
        }

        Node method = matches.get(0);
        String declaringClass = MethodIds.declaringClass(method.id());
        var sb = new StringBuilder();
        sb.append("## ").append(MethodIds.shortLabel(method.id())).append("\n\n");
        sb.append("- **Id:** `").append(method.id()).append("`\n");
        sb.append("- **Declared by:** `").append(declaringClass).append("`");
        store.findNodeById(declaringClass).ifPresent(c -> sb.append(" (").append(c.type()).append(")"));
        sb.append("\n");
        sb.append("- **Source:** ").append(method.filePath()).append(":").append(method.lineNumber()).append("\n");
        if (method.codeSnippet() != null && !method.codeSnippet().isBlank()) {
            sb.append("- **Signature:** `").append(method.codeSnippet()).append("`\n");
        }
        store.findMethodsByNodeId(declaringClass).stream()
            .filter(m -> m.name().equals(method.name()) && m.annotations() != null)
            .findFirst()
            .ifPresent(m -> sb.append("- **Endpoint:** ").append(m.annotations()).append("\n"));

        appendMethodEdges(sb, store, method.id(), "Calls", EdgeType.CALLS, true);
        appendMethodEdges(sb, store, method.id(), "Called by", EdgeType.CALLS, false);
        appendMethodEdges(sb, store, method.id(), "Overrides", EdgeType.OVERRIDES, true);
        appendMethodEdges(sb, store, method.id(), "Overridden by", EdgeType.OVERRIDES, false);
        return sb.toString();
    }

    private static void appendMethodEdges(StringBuilder sb, GraphStore store, String methodId,
                                          String heading, EdgeType type, boolean outgoing) {
        var edges = outgoing ? store.findEdgesFrom(methodId) : store.findEdgesTo(methodId);
        var related = edges.stream()
            .filter(e -> e.type() == type)
            .map(e -> outgoing ? e.targetId() : e.sourceId())
            .distinct()
            .toList();
        if (related.isEmpty()) return;

        sb.append("\n### ").append(heading).append(" (").append(related.size()).append(")\n");
        related.stream().limit(30).forEach(id -> {
            sb.append("- `").append(MethodIds.shortLabel(id)).append("`");
            store.findNodeById(id).ifPresent(n ->
                sb.append(" (").append(n.filePath()).append(":").append(n.lineNumber()).append(")"));
            sb.append("\n");
        });
        if (related.size() > 30) sb.append("- … ").append(related.size() - 30).append(" more\n");
    }

    String handleCgMap(Map<String, Object> args) {
        var scope = resolveScope(args);
        var store = scope.store();
        int limit = getIntArg(args, "limit", 40);
        String project = store.getConfig("tier");

        List<Node> roots = "DDD".equals(project)
            ? store.findNodesByType(NodeType.AGGREGATE) : store.findNodesByType(NodeType.CONTROLLER);
        if (roots.isEmpty()) roots = store.findNodesByType(NodeType.CONTROLLER);

        var sb = new StringBuilder();
        sb.append("### Roots (").append(roots.size()).append(")\n");
        // One line per root with a reach count. The per-type breakdown under every root ran to
        // ~1,800 tokens on nlp for an orientation answer; cg_related gives the detail on demand.
        roots.stream().limit(limit).forEach(root -> {
            int reach = scope.traversal().traceChain(root.id()).size();
            sb.append("- `").append(root.name()).append("` (").append(root.filePath())
              .append(") — ").append(reach).append(" connected\n");
        });
        if (roots.size() > limit) {
            sb.append("- … ").append(roots.size() - limit).append(" more\n");
        }
        return sb.toString();
    }

    String handleCgCallers(Map<String, Object> args) {
        return handleCallGraph(args, "Callers", true);
    }

    String handleCgCallees(Map<String, Object> args) {
        return handleCallGraph(args, "Callees", false);
    }

    private String handleCallGraph(Map<String, Object> args, String label, boolean incoming) {
        var scope = resolveScope(args);
        String symbol = (String) args.get("symbol");
        int depth = getIntArg(args, "depth", 5);
        int limit = getIntArg(args, "limit", 20);
        String nodeId = resolveSymbol(scope.store(), symbol);
        if (nodeId == null) return "Symbol '" + symbol + "' not found.";

        var start = scope.store().findNodeById(nodeId).orElse(null);
        var nodes = applyGranularity(
            incoming ? scope.traversal().callers(nodeId, depth)
                     : scope.traversal().callees(nodeId, depth),
            args, start);
        var sb = new StringBuilder();
        sb.append("## ").append(label).append(" of ").append(symbol).append("\n\n");
        sb.append(ambiguityNote(candidatesFor(scope.store(), symbol)));
        sb.append(nodes.size()).append(" ").append(label.toLowerCase()).append(" found");
        if (nodes.size() > limit) sb.append(" (showing first ").append(limit).append(")");
        sb.append(":\n\n");
        nodes.stream().limit(limit).forEach(n -> sb.append(formatNodeLine(n)).append("\n"));
        return sb.toString();
    }

    String handleCgNode(Map<String, Object> args) {
        var scope = resolveScope(args);
        var store = scope.store();
        String symbol = (String) args.get("symbol");
        boolean includeCode = !args.containsKey("includeCode") || !"false".equals(String.valueOf(args.get("includeCode")));
        String nodeId = resolveSymbol(store, symbol);
        if (nodeId == null) return "Symbol '" + symbol + "' not found.";

        var node = store.findNodeById(nodeId).orElseThrow();
        var sb = new StringBuilder();
        sb.append("## ").append(node.name()).append("\n\n");
        sb.append(ambiguityNote(candidatesFor(store, symbol)));
        sb.append("- **Type:** ").append(node.type()).append("\n");
        sb.append("- **Qualified name:** `").append(node.qualifiedName()).append("`\n");
        sb.append("- **File:** ").append(node.filePath()).append(":").append(node.lineNumber()).append("\n");

        if (includeCode && node.codeSnippet() != null && !node.codeSnippet().isEmpty()) {
            sb.append("\n### Code\n```java\n").append(node.codeSnippet()).append("\n```\n");
        }

        var outgoing = store.findEdgesFrom(nodeId);
        if (!outgoing.isEmpty()) {
            sb.append("\n### Outgoing edges\n");
            for (var edge : outgoing) {
                var target = store.findNodeById(edge.targetId());
                String targetName = target.map(Node::name).orElse(edge.targetId());
                sb.append("- --[").append(edge.type()).append("]--> `").append(targetName).append("`\n");
            }
        }

        var incoming = store.findEdgesTo(nodeId);
        if (!incoming.isEmpty()) {
            sb.append("\n### Incoming edges\n");
            for (var edge : incoming) {
                var source = store.findNodeById(edge.sourceId());
                String sourceName = source.map(Node::name).orElse(edge.sourceId());
                sb.append("- <--[").append(edge.type()).append("]-- `").append(sourceName).append("`\n");
            }
        }

        return sb.toString();
    }

    String handleCgStatus(Map<String, Object> args) {
        var scope = resolveScope(args);
        var store = scope.store();
        String project = store.getConfig("tier");
        int nodeCount = store.getNodeCount();
        int edgeCount = store.getEdgeCount();
        int fileCount = store.getFileCount();

        var sb = new StringBuilder();
        sb.append("## Code Navigator Status\n\n");
        sb.append("- **Project:** ").append(scope.root()).append("\n");
        sb.append("- **Tier:** ").append(project != null ? project : "unknown").append("\n");
        sb.append("- **Nodes:** ").append(nodeCount).append(" nodes\n");
        sb.append("- **Edges:** ").append(edgeCount).append(" edges\n");
        sb.append("- **Files:** ").append(fileCount).append(" files\n");

        var allNodes = store.getAllNodes();
        var byType = allNodes.stream().collect(Collectors.groupingBy(Node::type, Collectors.counting()));
        if (!byType.isEmpty()) {
            sb.append("\n### Nodes by type\n");
            byType.entrySet().stream()
                .sorted(Map.Entry.<NodeType, Long>comparingByValue().reversed())
                .forEach(e -> sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("\n"));
        }

        return sb.toString();
    }

    String handleCgHotspots(Map<String, Object> args) {
        var scope = resolveScope(args);
        int limit = getIntArg(args, "limit", 10);

        var fanIn = scope.store().findTopFanIn(limit);
        var fanOut = scope.store().findTopFanOut(limit);

        var sb = new StringBuilder();
        sb.append("## Hotspots (top ").append(limit).append(")\n\n");

        sb.append("### Highest Fan-In (change risk — many things depend on these)\n");
        for (int i = 0; i < fanIn.size(); i++) {
            var nc = fanIn.get(i);
            sb.append(String.format(" %d. %s (%s) — %d incoming edges%n",
                i + 1, nc.node().name(), nc.node().type(), nc.count()));
        }

        sb.append("\n### Highest Fan-Out (complexity risk — these depend on many things)\n");
        for (int i = 0; i < fanOut.size(); i++) {
            var nc = fanOut.get(i);
            sb.append(String.format(" %d. %s (%s) — %d outgoing edges%n",
                i + 1, nc.node().name(), nc.node().type(), nc.count()));
        }

        return sb.toString();
    }

    String handleCgCoupling(Map<String, Object> args) {
        var scope = resolveScope(args);
        var store = scope.store();
        int limit = getIntArg(args, "limit", 10);

        String filePath = null;

        if (args.containsKey("symbol")) {
            String symbol = (String) args.get("symbol");
            String nodeId = resolveSymbol(store, symbol);
            if (nodeId == null) return "Symbol '" + symbol + "' not found.";
            var node = store.findNodeById(nodeId).orElseThrow();
            filePath = node.filePath();
        } else if (args.containsKey("file")) {
            filePath = (String) args.get("file");
        }

        if (filePath == null) return "Provide either 'symbol' or 'file'.";

        var coupled = store.topCoupled(filePath, limit + 1);
        if (coupled.isEmpty()) return "No co-change data for: " + filePath;

        boolean hasMore = coupled.size() > limit;
        var shown = hasMore ? coupled.subList(0, limit) : coupled;

        var sb = new StringBuilder();
        sb.append("## Co-change coupling for `").append(filePath).append("`\n\n");
        sb.append("Top ").append(shown.size()).append(" historically co-changed file(s):\n\n");
        for (int i = 0; i < shown.size(); i++) {
            var pair = shown.get(i);
            sb.append(String.format(" %d. `%s` — %d commit(s)%n", i + 1, pair.otherFile(), pair.count()));
        }
        if (hasMore) {
            sb.append("\n… more co-changed file(s). Raise `limit`.\n");
        }
        return sb.toString();
    }

    String handleCgDeps(Map<String, Object> args) {
        var scope = resolveScope(args);
        var store = scope.store();
        String depsJson = store.getConfig("dependencies");
        if (depsJson == null || depsJson.isBlank()) {
            return "No dependency information stored. Re-index the project to populate dependency data.";
        }

        // Parse the stored JSON array "g:a:v" strings
        List<String> coordsWithVersion = new ArrayList<>();
        String stripped = depsJson.strip();
        if (stripped.startsWith("[")) stripped = stripped.substring(1);
        if (stripped.endsWith("]")) stripped = stripped.substring(0, stripped.length() - 1);
        for (String token : stripped.split(",")) {
            String s = token.strip().replaceAll("^\"|\"$", "");
            if (!s.isBlank()) coordsWithVersion.add(s);
        }

        if (coordsWithVersion.isEmpty()) {
            return "No dependency information stored. Re-index the project to populate dependency data.";
        }

        // Count LIBRARY nodes per artifact coordinate (codeSnippet stores "g:a")
        var libraryNodes = store.findNodesByType(NodeType.LIBRARY);
        Map<String, Long> countByCoordinate = libraryNodes.stream()
            .filter(n -> n.codeSnippet() != null && !n.codeSnippet().isBlank())
            .collect(Collectors.groupingBy(Node::codeSnippet, Collectors.counting()));

        var sb = new StringBuilder();
        sb.append("## Declared Dependencies\n\n");
        sb.append(coordsWithVersion.size()).append(" declared dependenc")
          .append(coordsWithVersion.size() == 1 ? "y" : "ies").append(":\n\n");

        for (String gav : coordsWithVersion) {
            // gav is "g:a:v" — strip version for display and lookup
            String[] parts = gav.split(":");
            String coordinate = parts[0] + ":" + parts[1];  // g:a
            String version    = parts.length > 2 ? parts[2] : "?";
            long usedCount    = countByCoordinate.getOrDefault(coordinate, 0L);
            sb.append("- **").append(coordinate).append("** (").append(version).append(") — ")
              .append(usedCount).append(" type(s) used\n");
        }

        long totalLibraryNodes = libraryNodes.size();
        if (totalLibraryNodes > 0) {
            sb.append("\n").append(totalLibraryNodes)
              .append(" total library type(s) indexed across all dependencies.\n");
        }

        return sb.toString();
    }

    private static final List<DeadCodeConfidence> DEAD_CODE_TIER_ORDER = List.of(
        DeadCodeConfidence.HIGH, DeadCodeConfidence.MEDIUM, DeadCodeConfidence.LOW,
        DeadCodeConfidence.USED_ONLY_BY_TESTS, DeadCodeConfidence.TEST_SOURCE);

    private static final Map<DeadCodeConfidence, String> DEAD_CODE_TIER_HEADING = Map.of(
        DeadCodeConfidence.HIGH, "HIGH confidence — verify then likely safe to remove",
        DeadCodeConfidence.MEDIUM, "MEDIUM confidence — plausibly reached via reflection/serialization; check manually",
        DeadCodeConfidence.LOW, "LOW confidence — framework entry points; absence of edges is expected, not a signal",
        DeadCodeConfidence.USED_ONLY_BY_TESTS, "Used only by tests — has edges, but every caller is test code",
        DeadCodeConfidence.TEST_SOURCE, "Test source — informational only, never dead code");

    String handleCgDead(Map<String, Object> args) {
        NodeType typeFilter = null;
        if (args.containsKey("type")) {
            try {
                typeFilter = NodeType.valueOf(((String) args.get("type")).toUpperCase());
            } catch (IllegalArgumentException e) {
                return "Unknown type: " + args.get("type");
            }
        }

        int limit = getIntArg(args, "limit", 10);

        var candidates = resolveScope(args).store().findDeadNodeCandidates(typeFilter);
        if (candidates.isEmpty()) return "No dead-code candidates found.";

        var byTier = candidates.stream().collect(Collectors.groupingBy(
            GraphStore.DeadCodeCandidate::confidence, () -> new EnumMap<>(DeadCodeConfidence.class), Collectors.toList()));

        var sb = new StringBuilder();
        sb.append("## Dead-Code Candidates (ranked, not a delete list — confirm manually)\n\n");
        int shown = 0;
        for (var tier : DEAD_CODE_TIER_ORDER) {
            var inTier = byTier.get(tier);
            if (inTier == null || inTier.isEmpty()) continue;

            sb.append("### ").append(DEAD_CODE_TIER_HEADING.get(tier)).append(" (").append(inTier.size()).append(")\n");
            var byType = inTier.stream().collect(Collectors.groupingBy(
                c -> c.node().type(), LinkedHashMap::new, Collectors.toList()));
            for (var entry : byType.entrySet()) {
                sb.append("**").append(entry.getKey()).append("** (").append(entry.getValue().size()).append(")\n");
                for (var candidate : entry.getValue()) {
                    if (shown >= limit) continue;
                    var node = candidate.node();
                    sb.append("- `").append(node.name()).append("` (").append(node.filePath()).append(":").append(node.lineNumber()).append(")\n");
                    shown++;
                }
            }
            sb.append("\n");
        }
        sb.append("Total: ").append(candidates.size()).append(" candidates. ")
          .append("Nodes referenced only through a live IMPLEMENTS/EXTENDS supertype are excluded entirely — ")
          .append("they're not dead, callers just depend on the interface.\n");
        if (candidates.size() > shown) {
            sb.append("\n… ").append(candidates.size() - shown)
              .append(" more candidate line(s) not shown. Raise `limit`.\n");
        }
        return sb.toString();
    }

    String handleCgFiles(Map<String, Object> args) {
        var scope = resolveScope(args);
        var store = scope.store();
        String pattern = args.containsKey("pattern") ? (String) args.get("pattern") : null;
        int limit = getIntArg(args, "limit", 30);

        var files = store.getAllIndexedFiles();
        if (pattern != null && !pattern.isEmpty()) {
            String lowerPattern = pattern.toLowerCase();
            files = files.stream().filter(f -> f.toLowerCase().contains(lowerPattern)).toList();
        }

        var sb = new StringBuilder();
        sb.append("## Indexed Files\n\n");
        sb.append(files.size()).append(" file(s)");
        if (files.size() > limit) sb.append(" (showing ").append(limit).append(")");
        sb.append(":\n\n");
        files.stream().limit(limit).forEach(file -> {
            var nodes = store.findNodesByFilePath(file);
            sb.append("- ").append(file).append(" (").append(nodes.size()).append(" nodes)\n");
        });
        if (files.size() > limit) {
            sb.append("\n… ").append(files.size() - limit)
              .append(" more. Raise `limit` or narrow `pattern`.\n");
        }
        return sb.toString();
    }

    String handleCgExport(Map<String, Object> args) {
        var scope = resolveScope(args);
        var store = scope.store();
        String format = (String) args.get("format");
        if (format == null) return "Missing required parameter: format";

        List<Node> nodes;
        List<Edge> edges;

        if (args.containsKey("symbol")) {
            String symbol = (String) args.get("symbol");
            String nodeId = resolveSymbol(store, symbol);
            if (nodeId == null) return "Symbol '" + symbol + "' not found.";
            nodes = scope.traversal().traceChain(nodeId);
            Set<String> nodeIds = nodes.stream().map(Node::id).collect(Collectors.toSet());
            edges = store.getAllEdges().stream()
                .filter(e -> nodeIds.contains(e.sourceId()) && nodeIds.contains(e.targetId()))
                .toList();
        } else {
            nodes = store.getAllNodes();
            edges = store.getAllEdges();
        }

        String tier = store.getConfig("tier");

        return switch (format.toLowerCase()) {
            case "json" -> exportService.toJson(nodes, edges, tier);
            case "mermaid" -> exportService.toMermaid(nodes, edges);
            case "plantuml" -> exportService.toPlantUml(nodes, edges);
            default -> "Unknown format: " + format + ". Supported: json, mermaid, plantuml";
        };
    }


    String handleCgPackages(Map<String, Object> args) {
        var scope = resolveScope(args);
        var allNodes = scope.store().getAllNodes();
        var allEdges = scope.store().getAllEdges();

        // Build node-id -> package map
        Map<String, String> nodePackage = new HashMap<>();
        Map<String, List<Node>> byPackage = new LinkedHashMap<>();
        for (var node : allNodes) {
            String pkg = extractPackage(node.qualifiedName());
            nodePackage.put(node.id(), pkg);
            byPackage.computeIfAbsent(pkg, k -> new ArrayList<>()).add(node);
        }

        // Aggregate inter-package edges
        Map<String, Map<String, Map<EdgeType, Integer>>> pkgEdges = new LinkedHashMap<>();
        for (var edge : allEdges) {
            String srcPkg = nodePackage.get(edge.sourceId());
            String tgtPkg = nodePackage.get(edge.targetId());
            if (srcPkg == null || tgtPkg == null || srcPkg.equals(tgtPkg)) continue;
            pkgEdges.computeIfAbsent(srcPkg, k -> new LinkedHashMap<>())
                    .computeIfAbsent(tgtPkg, k -> new LinkedHashMap<>())
                    .merge(edge.type(), 1, Integer::sum);
        }

        var sb = new StringBuilder();
        sb.append("## Package Dependencies\n\n");

        for (var entry : byPackage.entrySet()) {
            String pkg = entry.getKey();
            sb.append("### ").append(pkg).append(" (").append(entry.getValue().size()).append(" nodes)\n");

            var outgoing = pkgEdges.getOrDefault(pkg, Map.of());
            for (var target : outgoing.entrySet()) {
                int total = target.getValue().values().stream().mapToInt(Integer::intValue).sum();
                String detail = target.getValue().entrySet().stream()
                    .map(e -> e.getKey() + " x" + e.getValue())
                    .collect(Collectors.joining(", "));
                sb.append("  -> ").append(target.getKey()).append(" (").append(total).append(" edges: ").append(detail).append(")\n");
            }

            for (var src : pkgEdges.entrySet()) {
                if (src.getValue().containsKey(pkg)) {
                    int total = src.getValue().get(pkg).values().stream().mapToInt(Integer::intValue).sum();
                    String detail = src.getValue().get(pkg).entrySet().stream()
                        .map(e -> e.getKey() + " x" + e.getValue())
                        .collect(Collectors.joining(", "));
                    sb.append("  <- ").append(src.getKey()).append(" (").append(total).append(" edges: ").append(detail).append(")\n");
                }
            }
            sb.append("\n");
        }

        // Circular dependency detection
        List<String> circulars = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (var src : pkgEdges.entrySet()) {
            for (var tgt : src.getValue().keySet()) {
                String pair = src.getKey().compareTo(tgt) < 0 ? src.getKey() + "|" + tgt : tgt + "|" + src.getKey();
                if (!seen.add(pair)) continue;
                if (pkgEdges.containsKey(tgt) && pkgEdges.get(tgt).containsKey(src.getKey())) {
                    int fwd = src.getValue().get(tgt).values().stream().mapToInt(Integer::intValue).sum();
                    int rev = pkgEdges.get(tgt).get(src.getKey()).values().stream().mapToInt(Integer::intValue).sum();
                    circulars.add(String.format("- %s <-> %s (%s->%s: %d edges, %s->%s: %d edges)",
                        src.getKey(), tgt, src.getKey(), tgt, fwd, tgt, src.getKey(), rev));
                }
            }
        }

        if (!circulars.isEmpty()) {
            sb.append("## Circular Dependencies\n");
            circulars.forEach(c -> sb.append(c).append("\n"));
        }

        return sb.toString();
    }











    private static String extractPackage(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot > 0 ? qualifiedName.substring(0, lastDot) : qualifiedName;
    }

    // ---- Private helpers ----

    /**
     * Resolve the scope a tool call should run against.
     *
     * <p>With no {@code projectPath} argument this is the scope the server was started for.
     * With one, the named root must carry its own index — an unindexed path is an error rather
     * than a silent fall-back to the default project, because falling back makes a typo
     * indistinguishable from an empty project.
     */
    ProjectScope resolveScope(Map<String, Object> args) {
        String projectPath = args.containsKey("projectPath") ? (String) args.get("projectPath") : null;
        if (projectPath == null || projectPath.isBlank()) return defaultScope;

        var root = Path.of(projectPath).toAbsolutePath().normalize();
        if (!ProjectPaths.hasIndex(root)) {
            throw new ProjectNotIndexedException(
                "No code-navigator index at " + root + "\n"
                    + "Expected: " + ProjectPaths.graphDb(root) + "\n"
                    + "Run: java -jar code-navigator.jar init " + root);
        }
        return scopes.computeIfAbsent(root.toString(), key -> openScope(root));
    }

    /**
     * Open a scope for a secondary project, reading its graph from {@code navigators/code/}.
     */
    private ProjectScope openScope(Path root) {
        var graphStore = new GraphStore(ProjectPaths.graphDb(root));
        var graphTraversal = new GraphTraversal(graphStore);
        var search = new SearchService(graphStore, graphTraversal, embeddingProvider);

        return new ProjectScope(graphStore, graphTraversal, search, root);
    }

    private static String resolveSymbol(GraphStore store, String symbol) {
        var candidates = candidatesFor(store, symbol);
        return candidates.isEmpty() ? null : candidates.get(0).id();
    }

    /**
     * Every node a symbol could mean, best match first.
     *
     * <p>Ranked by how connected each candidate is. A bare name is often shared — {@code Andon} is
     * both a backend AGGREGATE and an Angular FE_MODEL — and answering from whichever row the
     * database returned first silently analysed the wrong one: {@code cg_impact Andon} reported
     * zero affected nodes because the frontend model has no edges. The most-referenced node is
     * nearly always the one meant, and callers surface the alternatives rather than hide the choice.
     */
    private static List<Node> candidatesFor(GraphStore store, String symbol) {
        if (symbol == null || symbol.isBlank()) return List.of();

        // An exact id wins outright only when the id is qualified. The TypeScript indexer uses bare
        // simple names as ids, so "Andon" is the id of the Angular model *and* the name of the
        // backend aggregate — short-circuiting on the id match answered every question about the
        // aggregate with the frontend model, which has no edges at all.
        boolean qualified = symbol.indexOf('.') >= 0 || MethodIds.isMethodId(symbol);
        var exact = store.findNodeById(symbol);
        if (exact.isPresent() && qualified) return List.of(exact.get());

        // "Andon#resolve" / "com.nlp...Andon#resolve(UUID)" — a method, written without the exact
        // parameter list the id carries.
        if (MethodIds.isMethodId(symbol)) return resolveMethodSymbol(store, symbol);

        var all = store.getAllNodes().stream()
            .filter(n -> n.name().equalsIgnoreCase(symbol)
                || n.qualifiedName().equalsIgnoreCase(symbol)
                || n.id().equalsIgnoreCase(symbol))
            .toList();
        // A bare name means the type, not one of its methods — otherwise cg_node("save") would
        // answer with whichever repository method happened to sort first.
        var types = all.stream().filter(n -> n.type() != NodeType.METHOD).toList();
        var pool = types.isEmpty() ? all : types;

        return pool.stream()
            .sorted(Comparator
                .comparingInt((Node n) -> -(store.findEdgesFrom(n.id()).size() + store.findEdgesTo(n.id()).size()))
                .thenComparing(Node::id))
            .toList();
    }

    /**
     * A one-line note naming the node actually used and the runners-up, or empty when the symbol
     * was unambiguous. Cheap to read and it makes a wrong pick correctable in one more call.
     */
    private static String ambiguityNote(List<Node> candidates) {
        if (candidates.size() <= 1) return "";
        var chosen = candidates.get(0);
        var others = candidates.stream().skip(1).limit(4)
            .map(n -> n.type() + " " + n.id())
            .collect(Collectors.joining(", "));
        return "_Resolved to " + chosen.type() + " `" + chosen.id() + "`; also matched: " + others
            + (candidates.size() > 5 ? ", …" : "") + "._\n\n";
    }

    /**
     * Find the METHOD nodes matching a {@code Class#method} reference. The class part may be a
     * simple name or an fqn, and the parameter list may be omitted or partial; all overloads that
     * fit are returned so the caller can report an ambiguity rather than silently pick one.
     */
    private static List<Node> resolveMethodSymbol(GraphStore store, String symbol) {
        String wantedClass = MethodIds.declaringClass(symbol);
        String wantedName = MethodIds.methodName(symbol);
        if (wantedName == null || wantedName.isBlank()) return List.of();

        int open = symbol.indexOf('(');
        int close = symbol.lastIndexOf(')');
        String wantedParams = (open >= 0 && close > open) ? symbol.substring(open + 1, close).trim() : null;

        return store.findNodesByName(wantedName).stream()
            .filter(n -> n.type() == NodeType.METHOD)
            .filter(n -> matchesClass(MethodIds.declaringClass(n.id()), wantedClass))
            .filter(n -> {
                if (wantedParams == null) return true;
                int o = n.id().indexOf('('), c = n.id().lastIndexOf(')');
                return o >= 0 && c > o && n.id().substring(o + 1, c).equalsIgnoreCase(wantedParams);
            })
            .toList();
    }

    private static boolean matchesClass(String declaring, String wanted) {
        if (declaring == null || wanted == null || wanted.isBlank()) return true;
        if (declaring.equalsIgnoreCase(wanted)) return true;
        int dot = declaring.lastIndexOf('.');
        String simple = dot >= 0 ? declaring.substring(dot + 1) : declaring;
        return simple.equalsIgnoreCase(wanted);
    }

    /**
     * Decide whether METHOD nodes belong in a result set.
     *
     * <p>Traversal always runs through methods; this only controls what is rendered. Asking about a
     * class gets a class-level answer, asking about a method gets a method-level one, and
     * {@code granularity} overrides both.
     */
    private static List<Node> applyGranularity(List<Node> nodes, Map<String, Object> args, Node start) {
        String requested = args.containsKey("granularity")
            ? String.valueOf(args.get("granularity")).toLowerCase(Locale.ROOT) : "auto";
        boolean includeMethods = switch (requested) {
            case "method", "all" -> true;
            case "class" -> false;
            default -> start != null && start.type() == NodeType.METHOD;
        };
        if (includeMethods) return nodes;
        return nodes.stream().filter(n -> n.type() != NodeType.METHOD).toList();
    }

    private String formatGroupedByType(String title, List<Node> nodes) {
        var byType = nodes.stream().collect(Collectors.groupingBy(
            Node::type, LinkedHashMap::new, Collectors.toList()));

        var sb = new StringBuilder();
        sb.append("## ").append(title).append("\n\n");
        for (var entry : byType.entrySet()) {
            sb.append("### ").append(entry.getKey()).append("\n");
            for (var node : entry.getValue()) {
                sb.append("- `").append(node.name()).append("` (").append(node.filePath()).append(":").append(node.lineNumber()).append(")\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String formatNodeLine(Node node) {
        return "- **" + node.type() + "** `" + node.name() + "` (" + node.filePath() + ":" + node.lineNumber() + ")";
    }

    private static int getIntArg(Map<String, Object> args, String key, int defaultValue) {
        return args.containsKey(key) ? ((Number) args.get(key)).intValue() : defaultValue;
    }

    /**
     * Trim an over-long response at a line boundary and say what was dropped.
     *
     * <p>This used to cut mid-character with {@code substring}, which severed a list partway
     * through an entry and gave no hint that anything was missing — the reader could not tell a
     * complete answer from a beheaded one. Cutting on whole lines and naming the count left makes
     * the gap visible and tells the caller how to close it.
     */
    static String truncateOutput(String text) {
        if (text.length() <= MAX_OUTPUT_LENGTH) return text;

        int cut = text.lastIndexOf('\n', MAX_OUTPUT_LENGTH);
        if (cut < MAX_OUTPUT_LENGTH / 2) cut = MAX_OUTPUT_LENGTH;   // one enormous line
        String kept = text.substring(0, cut);

        long shown = kept.lines().count();
        long total = text.lines().count();
        return kept + "\n\n… " + (total - shown) + " more line(s) not shown ("
            + shown + " of " + total + "). Narrow with `limit`, a `granularity` of class, "
            + "or a more specific symbol.\n";
    }

    private static CallToolResult textResult(String text) {
        return new CallToolResult(List.of(new TextContent(truncateOutput(text))), false, null, null);
    }

    /**
     * Run a handler, turning an unresolvable {@code projectPath} into a readable tool response
     * instead of a protocol-level error.
     */
    private CallToolResult guarded(java.util.function.Supplier<String> handler) {
        try {
            return textResult(handler.get());
        } catch (ProjectNotIndexedException e) {
            return textResult(e.getMessage());
        }
    }

    private static JsonSchema jsonSchema(Map<String, Object> properties, List<String> required) {
        return new JsonSchema("object", properties, required, null, null, null);
    }

    private static Map<String, Object> propString(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> propInt(String description) {
        return Map.of("type", "integer", "description", description);
    }

    private static Map<String, Object> withProjectPath(Map<String, Object> props) {
        var result = new HashMap<>(props);
        result.put("projectPath", propString("Optional absolute path to another project root"));
        return result;
    }
}
