package com.codenavigator.mcp;

import com.codenavigator.cli.ProjectPaths;
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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class CodeNavigatorMcpServer {

    /** About 1,500 tokens: the per-answer ceiling CLAUDE.md "Token discipline" sets. */
    private static final int MAX_OUTPUT_LENGTH = 6_000;

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
    private final ExportService exportService = new ExportService();
    private final Map<String, ProjectScope> scopes = new HashMap<>();

    public CodeNavigatorMcpServer(GraphStore store, GraphTraversal traversal, SearchService searchService) {
        var env = System.getenv("CODE_NAVIGATOR_PROJECT");
        this.defaultScope = new ProjectScope(store, traversal, searchService,
            Path.of(env != null ? env : "."));
    }

    public void start() {
        run(System.in, System.out);
    }

    /** Serves one client over the given streams and returns once the client has hung up. */
    void run(InputStream in, OutputStream out) {
        var inputEnded = new CountDownLatch(1);
        var transportProvider = new StdioServerTransportProvider(
            new JacksonMcpJsonMapper(JsonMapper.builder().build()), signalingEnd(in, inputEnded), out);

        var server = McpServer.sync(transportProvider)
            .serverInfo("code-navigator", "0.1.0")
            .toolCall(
                Tool.builder()
                    .name("cg_map")
                    .description("Project overview: tier, counts, main node types, root aggregates or controllers. Start here.")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of("limit", propInt("Max roots (default 40)"))),
                        List.of()))
                    .build(),
                (exchange, request) -> guarded("cg_map", request.arguments())
            )
            .toolCall(
                Tool.builder()
                    .name("cg_search")
                    .description("Find symbols by name or text; files=true searches file paths instead.")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of("query", propString("Name, text or file pattern"),
                               "files", propString("true: match file paths"),
                               "limit", propInt("Max results (default 10)"))),
                        List.of("query")))
                    .build(),
                (exchange, request) -> guarded("cg_search", request.arguments())
            )
            .toolCall(
                Tool.builder()
                    .name("cg_node")
                    .description("One symbol in detail: type, file:line, signature, direct edges.")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of("symbol", propString("Class, fqn, or Class#method"),
                               "includeCode", propString("true adds the declaration"))),
                        List.of("symbol")))
                    .build(),
                (exchange, request) -> guarded("cg_node", request.arguments())
            )
            .toolCall(
                Tool.builder()
                    .name("cg_related")
                    .description("What connects to a symbol: in = callers, out = callees, both = blast radius.")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of("symbol", propString("Class, fqn, or Class#method"),
                               "direction", propString("in | out | both (default both)"),
                               "depth", propInt("Depth (default 2; 5 for in|out)"),
                               "granularity", propString("class | method | all (default: matches the symbol)"),
                               "limit", propInt("Max results (default 10)"))),
                        List.of("symbol")))
                    .build(),
                (exchange, request) -> guarded("cg_related", request.arguments())
            )
            .toolCall(
                Tool.builder()
                    .name("cg_guard")
                    .description("Before editing a symbol: blast radius, DDD-significant nodes in range, test-only share of the fallout.")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of("symbol", propString("Class, fqn, or Class#method"),
                               "depth", propInt("Depth (default 2)"))),
                        List.of("symbol")))
                    .build(),
                (exchange, request) -> guarded("cg_guard", request.arguments())
            )
            .toolCall(
                Tool.builder()
                    .name("cg_context")
                    .description("Files and symbols likely to matter for a task. Use when you know what to do but not where.")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of("task", propString("Task, e.g. 'modify order creation'"),
                               "limit", propInt("Max files (default 10)"))),
                        List.of("task")))
                    .build(),
                (exchange, request) -> guarded("cg_context", request.arguments())
            )
            .toolCall(
                Tool.builder()
                    .name("cg_health")
                    .description("Structural analysis: hotspots (most depended-on), dead (unreferenced candidates), packages (deps, cycles), coupling (files that change together).")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of("kind", propString("hotspots | dead | packages | coupling"),
                               "symbol", propString("Symbol or file, for kind=coupling"),
                               "type", propString("NodeType filter, for kind=dead"),
                               "package", propString("Package prefix, for kind=hotspots|packages"),
                               "limit", propInt("Max results (default 10); packages: caps cycles and package rows"))),
                        List.of("kind")))
                    .build(),
                (exchange, request) -> guarded("cg_health", request.arguments())
            )
            .toolCall(
                Tool.builder()
                    .name("cg_deps")
                    .description("Declared build dependencies and how many internal types use each.")
                    .inputSchema(jsonSchema(withProjectPath(Map.of()), List.of()))
                    .build(),
                (exchange, request) -> guarded("cg_deps", request.arguments())
            )
            .toolCall(
                Tool.builder()
                    .name("cg_export")
                    .description("Export the graph as json, mermaid or plantuml. Scope with symbol unless you want it all.")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of("format", propString("json | mermaid | plantuml"),
                               "symbol", propString("Scope to this symbol's chain"),
                               "depth", propInt("With symbol: edges to follow (default all)"))),
                        List.of("format")))
                    .build(),
                (exchange, request) -> guarded("cg_export", request.arguments())
            )
            .build();

        // The transport reads stdin on its own thread and never reports the end of it, so the
        // wrapped stream is the only signal that the client hung up. Parking here forever left one
        // JVM running for every client that closed the pipe.
        try {
            inputEnded.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Only secondary scopes are owned here; the default scope's stores belong to the caller.
        scopes.values().forEach(s -> {
            try { s.store().close(); } catch (Exception ignored) {}
        });
        server.close();
    }

    /** Counts {@code ended} down when the stream reaches its end, fails or is closed. */
    private static InputStream signalingEnd(InputStream in, CountDownLatch ended) {
        return new FilterInputStream(in) {
            @Override public int read() throws IOException {
                try {
                    int b = super.read();
                    if (b < 0) ended.countDown();
                    return b;
                } catch (IOException e) {
                    ended.countDown();
                    throw e;
                }
            }

            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                try {
                    int n = super.read(buffer, offset, length);
                    if (n < 0) ended.countDown();
                    return n;
                } catch (IOException e) {
                    ended.countDown();
                    throw e;
                }
            }

            @Override public void close() throws IOException {
                ended.countDown();
                super.close();
            }
        };
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
        int limit = getIntArg(args, "limit", 10);

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
        int limit = getIntArg(args, "limit", 10);
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
        DirectoryRows.append(sb, nodes.stream().limit(limit).toList(), Node::filePath, (node, file) -> {
            String row = DirectoryRows.nodeRow(node, file, true);
            int coCount = coChangeByFile.getOrDefault(node.filePath(), 0);
            return coCount > 0 ? row + " _(co-change: " + coCount + " commit(s))_" : row;
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
        int limit = getIntArg(args, "limit", 10);
        var nodes = scope.search().contextSearch(task);
        if (nodes.isEmpty()) return "No relevant nodes found for task: " + task;

        var byFile = nodes.stream().collect(Collectors.groupingBy(
            Node::filePath, LinkedHashMap::new, Collectors.toList()));

        // One line per file with its symbols inline, under its directory line. The old shape spent
        // a heading and a bullet per node — ~3,800 tokens for a starting point the caller reads
        // and then narrows.
        var sb = new StringBuilder();
        sb.append("## Context for: ").append(task).append("\n");
        sb.append(nodes.size()).append(" node(s) across ").append(byFile.size()).append(" file(s)");
        if (byFile.size() > limit) sb.append(" (showing ").append(limit).append(" files)");
        sb.append("\n\n");

        DirectoryRows.append(sb, byFile.entrySet().stream().limit(limit).toList(), Map.Entry::getKey,
            (entry, file) -> file + " — "
                + entry.getValue().stream()
                    .map(n -> n.type() + " " + n.name())
                    .limit(6)
                    .collect(Collectors.joining(", "))
                + (entry.getValue().size() > 6 ? ", +" + (entry.getValue().size() - 6) : ""));
        if (byFile.size() > limit) {
            sb.append("\n… ").append(byFile.size() - limit)
              .append(" more file(s). Raise `limit` or describe the task more narrowly.\n");
        }
        return sb.toString();
    }

    String handleCgSearch(Map<String, Object> args) {
        var scope = resolveScope(args);
        String query = (String) args.get("query");
        int limit = getIntArg(args, "limit", 10);
        var nodes = scope.search().search(query);
        if (nodes.isEmpty()) return "No results for query: " + query;

        // A broad term matches hundreds of nodes; returning all of them cost ~3,700 tokens for a
        // question the caller usually answers from the first few.
        var sb = new StringBuilder();
        sb.append("## ").append(nodes.size()).append(" match(es) for: ").append(query);
        if (nodes.size() > limit) sb.append(" (showing ").append(limit).append(")");
        sb.append("\n\n");
        DirectoryRows.appendNodes(sb, nodes.stream().limit(limit).toList(), true);
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

    /**
     * A controller's handler methods as {@code VERB /path → methodName}, one line each, sorted by
     * path. The class-level {@code @RequestMapping} base path is already folded into
     * {@code methods.annotations} by {@link com.codenavigator.indexer.MethodExtractor}, so no
     * further joining happens here. A method without an HTTP annotation (a helper, a constructor)
     * is not an endpoint and is omitted.
     */
    private static void appendEndpoints(StringBuilder sb, GraphStore store, String nodeId) {
        var endpoints = store.findMethodsByNodeId(nodeId).stream()
            .filter(m -> m.annotations() != null && !m.annotations().isBlank())
            .sorted(Comparator.comparing(m -> endpointPath(m.annotations())))
            .toList();
        if (endpoints.isEmpty()) return;

        sb.append("\n### Endpoints\n");
        for (var m : endpoints) {
            sb.append("- ").append(m.annotations()).append(" → ").append(m.name()).append("\n");
        }
    }

    private static String endpointPath(String annotation) {
        int space = annotation.indexOf(' ');
        return space < 0 ? annotation : annotation.substring(space + 1);
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

        List<Node> found = "DDD".equals(project)
            ? store.findNodesByType(NodeType.AGGREGATE) : store.findNodesByType(NodeType.CONTROLLER);
        if (found.isEmpty()) found = store.findNodesByType(NodeType.CONTROLLER);

        // A TestAggregate fixture under src/test/ is not something the project hangs off: leave test
        // roots out and say how many, rather than list them as peers of the real ones.
        var roots = found.stream().filter(root -> !TestPaths.isUnderSrcTest(root.filePath())).toList();
        int testRoots = found.size() - roots.size();

        var shown = roots.stream().limit(limit).toList();
        var reach = shown.stream().map(root -> scope.traversal().traceChain(root.id()).size()).toList();
        Integer common = commonReach(reach);

        var sb = new StringBuilder();
        sb.append("### Roots (").append(roots.size()).append(")\n");
        // One line per root. In a connected codebase nearly every root reaches the same component
        // (14 of nlp-be's 15, all "10615 connected"), so that number is stated once and only a root
        // that differs carries its own. The path is left to cg_node; the per-type breakdown under
        // every root ran to ~1,800 tokens on nlp, and cg_related gives that detail on demand.
        if (common != null) {
            boolean uniform = reach.stream().allMatch(common::equals);
            sb.append(uniform ? "All roots reach " : "Roots reach ").append(common)
              .append(" connected nodes").append(uniform ? ".\n" : " unless marked.\n");
        }
        for (int i = 0; i < shown.size(); i++) {
            sb.append("- `").append(shown.get(i).name()).append("`");
            if (!reach.get(i).equals(common)) sb.append(" — ").append(reach.get(i)).append(" connected");
            sb.append("\n");
        }
        if (roots.size() > limit) {
            sb.append("- … ").append(roots.size() - limit).append(" more. Raise `limit`.\n");
        }
        if (testRoots > 0) {
            sb.append("Left out: ").append(testRoots).append(" root(s) under src/test/.\n");
        }
        return sb.toString();
    }

    /**
     * The reach shared by more than half of the roots (and at least two of them), or null when no
     * value is shared that widely. A value most roots would print is stated once above the list;
     * with no such value every root keeps its own.
     */
    private static Integer commonReach(List<Integer> reach) {
        return reach.stream()
            .collect(Collectors.groupingBy(r -> r, Collectors.counting()))
            .entrySet().stream()
            .filter(e -> e.getValue() >= 2 && e.getValue() * 2 > reach.size())
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
    }

    String handleCgCallers(Map<String, Object> args) {
        return handleCallGraph(args, true);
    }

    String handleCgCallees(Map<String, Object> args) {
        return handleCallGraph(args, false);
    }

    private String handleCallGraph(Map<String, Object> args, boolean incoming) {
        var scope = resolveScope(args);
        String symbol = (String) args.get("symbol");
        int depth = getIntArg(args, "depth", 5);
        int limit = getIntArg(args, "limit", 10);
        String nodeId = resolveSymbol(scope.store(), symbol);
        if (nodeId == null) return "Symbol '" + symbol + "' not found.";

        var start = scope.store().findNodeById(nodeId).orElse(null);
        var nodes = applyGranularity(
            incoming ? scope.traversal().callers(nodeId, depth)
                     : scope.traversal().callees(nodeId, depth),
            args, start);
        // "Callers"/"Callees" reads right for a method; a non-method node (SQL_TABLE, SHELL_SCRIPT,
        // ...) has no callers, only things that connect to it — "Incoming to"/"Outgoing from" is
        // the smallest heading change that still reads right there.
        boolean methodLike = start != null && start.type() == NodeType.METHOD;
        String heading = methodLike
            ? (incoming ? "Callers of " : "Callees of ") + symbol
            : (incoming ? "Incoming to " : "Outgoing from ") + symbol;
        String noun = methodLike ? (incoming ? "callers" : "callees") : (incoming ? "incoming" : "outgoing");
        var sb = new StringBuilder();
        sb.append("## ").append(heading).append("\n\n");
        sb.append(ambiguityNote(candidatesFor(scope.store(), symbol)));
        sb.append(nodes.size()).append(" ").append(noun).append(" found");
        if (nodes.size() > limit) sb.append(" (showing first ").append(limit).append(")");
        sb.append(":\n\n");
        DirectoryRows.appendNodes(sb, nodes.stream().limit(limit).toList(), true);
        if (nodes.size() > limit) {
            sb.append("\n… ").append(nodes.size() - limit)
              .append(" more. Raise `limit` or narrow the `depth`.\n");
        }
        return sb.toString();
    }

    /**
     * Names listed per edge-type line in {@code cg_node}; the rest collapse into {@code … +N (more: cg_related …)}.
     * Eight is enough to recognise a node's neighbourhood, and it holds a 365-caller hub to a few
     * hundred bytes; {@code cg_related} lists the rest.
     */
    private static final int EDGE_NAMES_PER_LINE = 8;

    String handleCgNode(Map<String, Object> args) {
        var scope = resolveScope(args);
        var store = scope.store();
        String symbol = (String) args.get("symbol");
        boolean includeCode = Boolean.parseBoolean(String.valueOf(args.get("includeCode")));
        String nodeId = resolveSymbol(store, symbol);
        if (nodeId == null) return "Symbol '" + symbol + "' not found.";

        var node = store.findNodeById(nodeId).orElseThrow();
        var outgoing = store.findEdgesFrom(nodeId);
        var sb = new StringBuilder();
        sb.append("## ").append(node.name()).append("\n\n");
        sb.append(ambiguityNote(candidatesFor(store, symbol)));
        sb.append("- **Type:** ").append(node.type()).append(supertypes(store, node, outgoing)).append("\n");
        sb.append("- **Qualified name:** `").append(node.qualifiedName()).append("`\n");
        sb.append("- **File:** ").append(node.filePath()).append(":").append(node.lineNumber()).append("\n");

        if (node.type() == NodeType.CONTROLLER) {
            appendEndpoints(sb, store, nodeId);
        }

        if (includeCode) {
            String declaration = declarationOf(node.codeSnippet(), node.filePath());
            if (!declaration.isEmpty()) {
                sb.append("\n### Code\n```").append(fenceLanguage(node.filePath()))
                  .append("\n").append(declaration).append("\n```\n");
            }
        }

        var edgeLines = new StringBuilder();
        appendEdgeLines(edgeLines, store, node, "out", outgoing, true);
        appendEdgeLines(edgeLines, store, node, "in", store.findEdgesTo(nodeId), false);
        if (!edgeLines.isEmpty()) sb.append("\n").append(edgeLines);

        return sb.toString();
    }

    /**
     * {@code " extends A implements B, C"}: from the node's own EXTENDS and IMPLEMENTS edges and, for a
     * kind with none, from that clause of its stored declaration. The index has no edge for a supertype
     * it could not resolve, and a Type line that then read a bare {@code AGGREGATE} hid the one thing
     * asked of an aggregate: what it extends. Empty when the type has no supertypes.
     */
    private static String supertypes(GraphStore store, Node node, List<Edge> outgoing) {
        var declared = supertypeClauses(node);
        var sb = new StringBuilder();
        for (var type : new EdgeType[] {EdgeType.EXTENDS, EdgeType.IMPLEMENTS}) {
            String keyword = type == EdgeType.EXTENDS ? "extends" : "implements";
            var names = outgoing.stream().filter(e -> e.type() == type)
                .map(e -> store.findNodeById(e.targetId()).map(Node::name).orElse(e.targetId()))
                .sorted().toList();
            if (names.isEmpty()) names = declared.getOrDefault(keyword, List.of());
            if (!names.isEmpty()) sb.append(' ').append(keyword).append(' ').append(String.join(", ", names));
        }
        return sb.toString();
    }

    private static final Pattern TYPE_HEADER =
        Pattern.compile("\\b(?:class|interface|record|enum|trait|mixin)\\s+\\w+");
    private static final Pattern HEADER_KEYWORD = Pattern.compile("\\b(extends|implements|permits|with)\\b");

    /**
     * The {@code extends} and {@code implements} clauses of a type's own declaration, keyed by keyword,
     * each name as written (type arguments and all) in source order. Only the top level of the header
     * counts: a type parameter's bound ({@code <T extends Money>}) and a record's components sit inside
     * brackets, and a method has no supertypes. Python, shell and SQL snippets are whole bodies, not
     * declarations, so a stray "extends" in one is not read.
     */
    private static Map<String, List<String>> supertypeClauses(Node node) {
        String path = node.filePath() == null ? "" : node.filePath();
        if (node.type() == NodeType.METHOD || Set.of("bash", "sql", "python").contains(fenceLanguage(path))) {
            return Map.of();
        }
        String declaration = declarationOf(node.codeSnippet(), path);
        var header = TYPE_HEADER.matcher(declaration);
        if (!header.find()) return Map.of();
        String tail = declaration.substring(header.end());

        // Blank out everything inside <...> and (...): a keyword or comma left in "masked" is top level.
        var masked = new StringBuilder(tail.length());
        int depth = 0;
        for (char c : tail.toCharArray()) {
            if (c == '<' || c == '(') depth++;
            masked.append(depth == 0 ? c : '_');
            if (c == '>' || c == ')') depth = Math.max(0, depth - 1);
        }

        var clauses = new LinkedHashMap<String, List<String>>();
        var keyword = HEADER_KEYWORD.matcher(masked);
        boolean more = keyword.find();
        while (more) {
            String word = keyword.group(1);
            int from = keyword.end();
            more = keyword.find();
            int to = more ? keyword.start() : tail.length();
            if (word.equals("extends") || word.equals("implements")) {
                clauses.putIfAbsent(word, namesIn(tail, masked, from, to));
            }
        }
        return clauses;
    }

    /** The comma-separated names in {@code text[from, to)}, split only at commas still present in {@code masked}. */
    private static List<String> namesIn(String text, CharSequence masked, int from, int to) {
        var names = new ArrayList<String>();
        int start = from;
        for (int i = from; i <= to; i++) {
            if (i < to && masked.charAt(i) != ',') continue;
            String name = text.substring(start, i).replaceAll("\\s+", " ").strip();
            if (!name.isEmpty()) names.add(name);
            start = i + 1;
        }
        return names;
    }

    /**
     * One line per edge type — {@code in CALLS (376, 120 in tests): a, b, … +368 (more: cg_related X
     * direction=in depth=1)} — rather than one per edge. A hub's 376 callers cost ~15 KB listed one by
     * one and were still cut off mid-list; the count and the first few names say what a node connects
     * to, and the pointer says where the rest is. Production code is listed before tests, which are
     * most of a hub's callers and the least informative ones. Names are sorted so a node always prints
     * the same line. A method is named {@code Owner#method}, since a bare {@code add} says nothing
     * (the node's own methods drop the owner: the page is the owner); a class sharing its name with
     * another neighbour carries its qualified name.
     */
    private static void appendEdgeLines(StringBuilder sb, GraphStore store, Node self, String direction,
                                        List<Edge> edges, boolean outgoing) {
        var byType = new EnumMap<EdgeType, List<EdgeEnd>>(EdgeType.class);
        for (var edge : edges) {
            String id = outgoing ? edge.targetId() : edge.sourceId();
            byType.computeIfAbsent(edge.type(), t -> new ArrayList<>()).add(EdgeEnd.of(store, id, self.id()));
        }
        // What cg_related is called with to list them all: a method by its full id, so an overload
        // or a namesake resolves to this node, anything else by its name.
        String handle = self.type() == NodeType.METHOD ? self.id() : self.name();
        byType.forEach((type, ends) -> {
            var perName = ends.stream().collect(Collectors.groupingBy(EdgeEnd::name, Collectors.counting()));
            var rows = ends.stream()
                .map(end -> new EdgeRow(end.label(perName.get(end.name()) > 1), end.isTest(), end.id()))
                .sorted(Comparator.comparing(EdgeRow::test).thenComparing(EdgeRow::label).thenComparing(EdgeRow::id))
                .toList();
            long inTests = rows.stream().filter(EdgeRow::test).count();
            // The cap counts edges. Overloads of one class are still the same label once qualified,
            // so they collapse into "Owner#name ×2" rather than read as a duplicated edge.
            var shown = new LinkedHashMap<String, Integer>();
            rows.stream().limit(EDGE_NAMES_PER_LINE).forEach(row -> shown.merge(row.label(), 1, Integer::sum));
            sb.append(direction).append(' ').append(type).append(" (").append(rows.size())
              .append(inTests > 0 ? ", " + inTests + " in tests" : "").append("): ")
              .append(shown.entrySet().stream()
                  .map(e -> e.getValue() > 1 ? e.getKey() + " ×" + e.getValue() : e.getKey())
                  .collect(Collectors.joining(", ")));
            if (rows.size() > EDGE_NAMES_PER_LINE) {
                sb.append(", … +").append(rows.size() - EDGE_NAMES_PER_LINE)
                  .append(" (more: cg_related ").append(pointer(handle, direction, type)).append(")");
            }
            sb.append("\n");
        });
    }

    /**
     * The {@code cg_related} arguments that list a capped edge line, to be run exactly as printed.
     * {@code cg_related} cannot filter to one edge type, so only two pointers are exact: a class's own
     * methods (a class and its methods are one place, so depth 0 with {@code granularity=method} is the
     * class and nothing a call away; depth 1 would add every callee) and a method's callers. The rest
     * are the closest walk that crosses the edge, a superset where the node has other edges of that
     * kind. A type the directional walk does not follow (RETURNS_TYPE, EMITS_EVENT, EXTENDS, ...) is
     * only in the undirected one, hence {@code both}.
     */
    private static String pointer(String handle, String direction, EdgeType type) {
        if (type == EdgeType.DECLARES_METHOD && direction.equals("out")) {
            return handle + " direction=out depth=0 granularity=method";
        }
        String walk = GraphTraversal.followedDirectionally(type) ? direction : "both";
        return handle + " direction=" + walk + " depth=1";
    }

    /**
     * The node at the far end of an edge; {@code node} is null when the id names nothing indexed.
     * {@code owner} is the simple name of the class that declares a method, {@code ""} when that is the
     * node being shown, and null for anything that is not a method with a known owner.
     */
    private record EdgeEnd(String id, Node node, String owner) {
        static EdgeEnd of(GraphStore store, String id, String selfId) {
            var node = store.findNodeById(id).orElse(null);
            String owner = null;
            if (node != null && node.type() == NodeType.METHOD) {
                var declaring = store.findEdgesToByType(id, EdgeType.DECLARES_METHOD).stream().findFirst();
                if (declaring.isPresent()) {
                    String ownerId = declaring.get().sourceId();
                    owner = ownerId.equals(selfId) ? "" : store.findNodeById(ownerId).map(Node::name).orElse(null);
                }
            }
            return new EdgeEnd(id, node, owner);
        }

        String name() { return node == null ? id : node.name(); }

        boolean isTest() { return node != null && TestPaths.isUnderSrcTest(node.filePath()); }

        /** {@code Owner#method}; else the name, or the qualified name when another neighbour shares it. */
        String label(boolean nameShared) {
            if (owner != null) return owner.isEmpty() ? node.name() : owner + "#" + node.name();
            return nameShared && node != null ? node.qualifiedName() : name();
        }
    }

    private record EdgeRow(String label, boolean test, String id) {}

    /**
     * The declaration part of a stored snippet: annotations and signature, up to the opening brace
     * (or the semicolon of a bodiless one). Comments and Javadoc before it and the body after it
     * are dropped. Done at render time so an index built before this needs no rebuild. Python,
     * shell and SQL have no brace-delimited body and are shown as stored.
     */
    static String declarationOf(String snippet, String filePath) {
        if (snippet == null) return "";
        if (Set.of("bash", "sql", "python").contains(fenceLanguage(filePath))) return snippet;

        var code = new StringBuilder();
        boolean inBlockComment = false;
        for (String line : snippet.lines().toList()) {
            String trimmed = line.strip();
            if (inBlockComment || trimmed.startsWith("/*")) {
                inBlockComment = !trimmed.contains("*/");
                continue;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("//")) continue;
            code.append(line.stripTrailing()).append('\n');
        }

        // The first '{' or ';' outside parentheses and string literals ends the declaration; one
        // inside an annotation such as @RequestMapping(value = { "/a", "/b" }) does not.
        String text = code.toString();
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                int close = i + 1;
                while (close < text.length() && text.charAt(close) != c) close += text.charAt(close) == '\\' ? 2 : 1;
                i = close;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth <= 0 && c == '{') {
                return text.substring(0, i).strip();
            } else if (depth <= 0 && c == ';') {
                return text.substring(0, i + 1).strip();
            }
        }
        return text.strip();
    }

    /**
     * Node types {@code cg_map} lists, by count; the rest collapse into {@code +N more types}.
     * nlp-be has 23 types and most are one-off, so the top eight carry the project's shape and the
     * tail is a count rather than a list.
     */
    private static final int TOP_NODE_TYPES = 8;

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
            // Ties break on the type name: the map's iteration order is not stable across runs, and an
            // unstable tie at the cut line would change which types the "+N more" hides.
            var ranked = byType.entrySet().stream()
                .sorted(Map.Entry.<NodeType, Long>comparingByValue().reversed()
                    .thenComparing(e -> e.getKey().name()))
                .toList();
            sb.append("\n### Nodes by type\n");
            ranked.stream().limit(TOP_NODE_TYPES).forEach(e ->
                sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("\n"));
            if (ranked.size() > TOP_NODE_TYPES) {
                sb.append("- +").append(ranked.size() - TOP_NODE_TYPES).append(" more types\n");
            }
        }

        return sb.toString();
    }

    String handleCgHotspots(Map<String, Object> args) {
        var scope = resolveScope(args);
        int limit = getIntArg(args, "limit", 10);

        String within = packageArg(args);
        var fanIn = scope.store().findTopFanIn(limit, within);
        var fanOut = scope.store().findTopFanOut(limit, within);
        if (within != null && fanIn.isEmpty() && fanOut.isEmpty()) return nothingUnder(within);

        var sb = new StringBuilder();
        sb.append("## Hotspots").append(within == null ? "" : " in " + within)
          .append(" (top ").append(limit).append(")\n\n");

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

    /**
     * Names listed on the "no used type" line of {@code cg_deps}; the rest collapse into a count.
     * Eight, as in {@code cg_node}'s edge lines: enough to show what kind of build it is, and
     * bounded for a build that declares hundreds of libraries.
     */
    private static final int UNUSED_DEPENDENCIES_LISTED = 8;

    private static String dependencies(int count) {
        return count == 1 ? "dependency" : "dependencies";
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
        sb.append(coordsWithVersion.size()).append(" declared ")
          .append(dependencies(coordsWithVersion.size())).append(":\n\n");

        // A dependency no indexed type touches (a starter, a JDBC driver, a test library) was one
        // line of noise each — 22 of nlp-be's 35 — but its name still says what the build is made
        // of, so those names share one line instead of vanishing into a bare count.
        List<String> unused = new ArrayList<>();
        for (String gav : coordsWithVersion) {
            // gav is "g:a:v" — strip version for display and lookup
            String[] parts = gav.split(":");
            String coordinate = parts[0] + ":" + parts[1];  // g:a
            long usedCount    = countByCoordinate.getOrDefault(coordinate, 0L);
            if (usedCount == 0) {
                unused.add(coordinate);
                continue;
            }
            sb.append("- **").append(coordinate).append("**");
            // A "(?)" for a dependency declared without a version says nothing; leave the marker out.
            if (parts.length > 2) sb.append(" (").append(parts[2]).append(")");
            sb.append(" — ").append(usedCount).append(" type(s) used\n");
        }
        if (!unused.isEmpty()) {
            int listed = Math.min(unused.size(), UNUSED_DEPENDENCIES_LISTED);
            sb.append("- ").append(unused.size()).append(" declared ").append(dependencies(unused.size()))
              .append(" with no used type: ").append(String.join(", ", unused.subList(0, listed)));
            if (unused.size() > listed) {
                sb.append(", … +").append(unused.size() - listed).append(" more (see the build file)");
            }
            sb.append("\n");
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
                sb.append(entry.getKey()).append(" (").append(entry.getValue().size()).append(")\n");
                var visible = entry.getValue().stream()
                    .limit(Math.max(0, limit - shown))
                    .map(GraphStore.DeadCodeCandidate::node)
                    .toList();
                DirectoryRows.appendNodes(sb, visible, false);
                shown += visible.size();
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
        int limit = getIntArg(args, "limit", 10);

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
        DirectoryRows.append(sb, files.stream().limit(limit).toList(), file -> file,
            (file, name) -> name + " " + store.findNodesByFilePath(file).size() + " nodes");
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
            if (args.containsKey("depth")) {
                // impact() leaves the start node out, and a diagram of a symbol has to draw it.
                var around = new ArrayList<Node>();
                store.findNodeById(nodeId).ifPresent(around::add);
                around.addAll(scope.traversal().impact(nodeId, getIntArg(args, "depth", 0)));
                nodes = around;
            } else {
                nodes = scope.traversal().traceChain(nodeId);
            }
            Set<String> nodeIds = nodes.stream().map(Node::id).collect(Collectors.toSet());
            edges = store.getAllEdges().stream()
                .filter(e -> nodeIds.contains(e.sourceId()) && nodeIds.contains(e.targetId()))
                .toList();
        } else {
            nodes = store.getAllNodes();
            edges = store.getAllEdges();
        }

        String tier = store.getConfig("tier");

        String type = format.toLowerCase(Locale.ROOT);
        String document = switch (type) {
            case "json" -> exportService.toJson(nodes, edges, tier);
            case "mermaid" -> exportService.toMermaid(nodes, edges);
            case "plantuml" -> exportService.toPlantUml(nodes, edges);
            default -> null;
        };
        if (document == null) return "Unknown format: " + format + ". Supported: json, mermaid, plantuml";
        if (document.length() <= MAX_OUTPUT_LENGTH) return document;
        return exportRefusal(type, document.length(), args.containsKey("symbol"), args.containsKey("depth"));
    }

    /**
     * An export is one document: cut at the cap it is invalid JSON or a diagram missing its last
     * edges, and it reads as complete. So an oversized one is refused whole, with the size it would
     * have had and the parameters that shrink it.
     */
    private static String exportRefusal(String format, int size, boolean scoped, boolean hasDepth) {
        String narrow = !scoped
            ? "Pass `symbol` to export one chain instead of the whole project, then `depth` to follow fewer edges from it (0 is its own class and methods)."
            : hasDepth
                ? "Lower `depth` (0 is its own class and methods) or pick another `symbol`."
                : "Pass `depth` to follow fewer edges from `symbol`; start at 0, its own class and methods.";
        return "Export refused: the " + format + " export would be " + String.format(Locale.ROOT, "%,d", size)
            + " chars, over the " + String.format(Locale.ROOT, "%,d", MAX_OUTPUT_LENGTH)
            + "-char output cap, and a cut " + format + " export is not valid. " + narrow + "\n";
    }


    /**
     * Package coupling at a glance: circular pairs first, since they are what to act on, then one
     * summary row per package, most coupled first. Each list honours {@code limit}. A row used to
     * expand into every neighbouring package with its edge types; on {@code nlp} one package alone
     * was 5.6 KB and the whole listing 7,893 lines, so the cycles printed after it were cut off.
     */
    String handleCgPackages(Map<String, Object> args) {
        var scope = resolveScope(args);
        int limit = getIntArg(args, "limit", 10);
        String within = packageArg(args);
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

        // Aggregate inter-package edges: source package -> target package -> edge count
        Map<String, Map<String, Integer>> pkgEdges = new HashMap<>();
        for (var edge : allEdges) {
            String srcPkg = nodePackage.get(edge.sourceId());
            String tgtPkg = nodePackage.get(edge.targetId());
            if (srcPkg == null || tgtPkg == null || srcPkg.equals(tgtPkg)) continue;
            pkgEdges.computeIfAbsent(srcPkg, k -> new HashMap<>()).merge(tgtPkg, 1, Integer::sum);
        }

        // Circular dependencies: package pairs with edges both ways, most edges first
        List<PackageCycle> cycles = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (var src : pkgEdges.entrySet()) {
            for (String tgt : src.getValue().keySet()) {
                String lo = src.getKey().compareTo(tgt) < 0 ? src.getKey() : tgt;
                String hi = lo.equals(src.getKey()) ? tgt : src.getKey();
                if (!seen.add(lo + "|" + hi)) continue;
                int forward = pkgEdges.getOrDefault(lo, Map.of()).getOrDefault(hi, 0);
                int backward = pkgEdges.getOrDefault(hi, Map.of()).getOrDefault(lo, 0);
                if (forward > 0 && backward > 0) cycles.add(new PackageCycle(lo, hi, forward, backward));
            }
        }
        cycles.sort(Comparator.comparingInt(PackageCycle::edges).reversed()
            .thenComparing(PackageCycle::left).thenComparing(PackageCycle::right));

        // One summary row per package, most coupled first
        List<PackageRow> rows = new ArrayList<>();
        for (var entry : byPackage.entrySet()) {
            String pkg = entry.getKey();
            Map<String, Integer> outgoing = pkgEdges.getOrDefault(pkg, Map.of());
            int inPackages = 0;
            int inEdges = 0;
            for (var targets : pkgEdges.values()) {
                Integer n = targets.get(pkg);
                if (n != null) {
                    inPackages++;
                    inEdges += n;
                }
            }
            rows.add(new PackageRow(pkg, entry.getValue().size(), outgoing.size(),
                outgoing.values().stream().mapToInt(Integer::intValue).sum(), inPackages, inEdges));
        }
        rows.sort(Comparator.comparingInt(PackageRow::edges).reversed().thenComparing(PackageRow::pkg));
        if (within != null) {
            cycles.removeIf(c -> !inPackage(c.left(), within) && !inPackage(c.right(), within));
            rows.removeIf(row -> !inPackage(row.pkg(), within));
            if (cycles.isEmpty() && rows.isEmpty()) return nothingUnder(within);
        }

        var sb = new StringBuilder();
        if (!cycles.isEmpty()) {
            sb.append("## Circular Dependencies (").append(cycles.size()).append("), edges left->right / right->left\n");
            for (var c : cycles.stream().limit(limit).toList()) {
                sb.append("- ").append(c.pair()).append(" (").append(c.forward()).append(" / ")
                  .append(c.backward()).append(" edges)\n");
            }
            appendMore(sb, cycles.size(), limit);
            sb.append("\n");
        }

        sb.append("## Package Dependencies (").append(rows.size()).append(" packages)\n");
        for (var row : rows.stream().limit(limit).toList()) {
            sb.append(row.line()).append("\n");
        }
        appendMore(sb, rows.size(), limit);
        if (within != null) appendTopTypes(sb, scope.store(), within);
        sb.append("\nTypes inside a package: `cg_health kind=hotspots package=<name>`.\n");
        return sb.toString();
    }

    private record PackageCycle(String left, String right, int forward, int backward) {
        int edges() {
            return forward + backward;
        }

        /**
         * {@code a <-> b}, each name once. The dotted prefix both share is written once, as
         * {@code com.acme.{a <-> b}}, when that leaves both a tail; names that share none stay whole.
         */
        String pair() {
            int shared = 0;
            for (int i = 0; i < Math.min(left.length(), right.length()) && left.charAt(i) == right.charAt(i); i++) {
                if (left.charAt(i) == '.') shared = i + 1;
            }
            if (shared == 0 || shared >= left.length() || shared >= right.length()) return left + " <-> " + right;
            return left.substring(0, shared) + "{" + left.substring(shared) + " <-> " + right.substring(shared) + "}";
        }
    }

    private record PackageRow(String pkg, int nodes, int outPackages, int outEdges,
                              int inPackages, int inEdges) {
        int edges() {
            return outEdges + inEdges;
        }

        /** One line: the package, its node count, and what it depends on and is used by; an empty side is left out. */
        String line() {
            var sb = new StringBuilder("- ").append(pkg).append(" (").append(nodes).append(" nodes)");
            String sep = ": ";
            if (outPackages > 0) {
                sb.append(sep).append("-> ").append(outPackages).append(" packages (").append(outEdges).append(" edges)");
                sep = ", ";
            }
            if (inPackages > 0) {
                sb.append(sep).append("<- ").append(inPackages).append(" packages (").append(inEdges).append(" edges)");
            }
            return sb.toString();
        }
    }

    private static void appendMore(StringBuilder sb, int total, int limit) {
        if (total > limit) {
            sb.append("\n… ").append(total - limit).append(" more. Raise `limit`.\n");
        }
    }

    /** The {@code package} argument, or null when the call has none. */
    private static String packageArg(Map<String, Object> args) {
        return args.get("package") instanceof String p && !p.isBlank() ? p.strip() : null;
    }

    /** True for {@code prefix} itself and any package below it; "com.acme" does not reach "com.acmeco". */
    private static boolean inPackage(String pkg, String prefix) {
        return pkg.equals(prefix) || pkg.startsWith(prefix + ".");
    }

    private static String nothingUnder(String pkg) {
        return "Nothing under package `" + pkg + "`. Give a package as in a qualified name "
            + "(e.g. com.acme.orders); its sub-packages count too.\n";
    }

    /** Types a scoped {@code packages} reply lists, and how many fan-out candidates are weighed for them. */
    private static final int TOP_TYPES = 3;
    private static final int TOP_TYPE_POOL = 40;

    /**
     * The production types under {@code within} with the most outgoing edges, after the packages: the
     * answer to "what is rotten in this package" that a caller who stops after one call would
     * otherwise never reach. Methods and types under src/test are left out, and a package none of
     * whose types has an outgoing edge gets no section.
     */
    private static void appendTopTypes(StringBuilder sb, GraphStore store, String within) {
        var top = store.findTopFanOutTypes(TOP_TYPE_POOL, within).stream()
            .filter(nc -> nc.node().filePath() == null || !TestPaths.isUnderSrcTest(nc.node().filePath()))
            .limit(TOP_TYPES)
            .toList();
        if (top.isEmpty()) return;
        sb.append("\n## Top types by fan-out (outgoing edges)\n");
        DirectoryRows.append(sb, top, nc -> nc.node().filePath(),
            (nc, file) -> DirectoryRows.nodeRow(nc.node(), file, true) + " — " + nc.count() + " out");
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
        var search = new SearchService(graphStore, graphTraversal);

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
            .filter(n -> matchesOwner(store, n, wantedClass))
            .filter(n -> {
                if (wantedParams == null) return true;
                int o = n.id().indexOf('('), c = n.id().lastIndexOf(')');
                return o >= 0 && c > o && n.id().substring(o + 1, c).equalsIgnoreCase(wantedParams);
            })
            .toList();
    }

    /**
     * True when {@code wanted} names the owner of {@code method} — a Java class (simple or fqn),
     * a PY_MODULE/PY_CLASS (simple or dotted-qualified), or a SHELL_SCRIPT. Resolved through the
     * method's own {@code DECLARES_METHOD} edge rather than string-splitting the method id: the id
     * carries a Java-shaped {@code declaringClass#name}, but a Python/shell owner id is a file
     * path (e.g. {@code tools/build.py}), so splitting it never recovers the module name
     * {@code build} that {@code build#build_or_update_graph} was written against.
     */
    private static boolean matchesOwner(GraphStore store, Node method, String wanted) {
        if (wanted == null || wanted.isBlank()) return true;
        String ownerId = store.findEdgesTo(method.id()).stream()
            .filter(e -> e.type() == EdgeType.DECLARES_METHOD)
            .map(Edge::sourceId)
            .findFirst()
            .orElseGet(() -> MethodIds.declaringClass(method.id()));
        if (matchesClass(ownerId, wanted)) return true;
        return store.findNodeById(ownerId)
            .filter(owner -> owner.name().equalsIgnoreCase(wanted)
                || (owner.qualifiedName() != null && owner.qualifiedName().equalsIgnoreCase(wanted)))
            .isPresent();
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
    static List<Node> applyGranularity(List<Node> nodes, Map<String, Object> args, Node start) {
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
            DirectoryRows.appendNodes(sb, entry.getValue(), false);
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * The Markdown code-fence language for a source file, picked from its extension. A snippet
     * from a shell script or a SQL migration rendered as ```java``` misled a reader into parsing
     * it with the wrong grammar; extension is a cheap, reliable signal since every indexed
     * language owns a distinct one.
     */
    static String fenceLanguage(String filePath) {
        if (filePath == null) return "java";
        int dot = filePath.lastIndexOf('.');
        String ext = dot >= 0 ? filePath.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        return switch (ext) {
            case "sh" -> "bash";
            case "sql" -> "sql";
            case "py" -> "python";
            case "ts" -> "typescript";
            case "groovy" -> "groovy";
            case "dart" -> "dart";
            default -> "java";
        };
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
    static String truncateOutput(String text, String tool) {
        if (text.length() <= MAX_OUTPUT_LENGTH) return text;

        int cut = text.lastIndexOf('\n', MAX_OUTPUT_LENGTH);
        if (cut < MAX_OUTPUT_LENGTH / 2) cut = MAX_OUTPUT_LENGTH;   // one enormous line
        String kept = text.substring(0, cut);

        long shown = kept.lines().count();
        long total = text.lines().count();
        return kept + "\n\n… " + (total - shown) + " more line(s) not shown ("
            + shown + " of " + total + ")." + narrowing(tool) + "\n";
    }

    /**
     * Tools whose {@code limit} the reply cap may lower, with the default each applies when a call
     * passes none. The other tools have no list to shorten.
     */
    private static final Map<String, Integer> LIMIT_DEFAULTS = Map.of(
        "cg_map", 40, "cg_search", 10, "cg_related", 10, "cg_context", 10, "cg_health", 10);

    /**
     * What narrows a tool's reply besides {@code limit}, worded for the cap notes. Every name in it is
     * a parameter of that tool's schema (a test holds the two together): a note that sent the caller
     * to a parameter the tool lacks cost a wasted call and taught it nothing.
     */
    private static final Map<String, String> NARROWING = Map.of(
        "cg_search", "a more specific `query`",
        "cg_node", "a more specific `symbol`",
        "cg_related", "`depth`, `granularity` or a more specific `symbol`",
        "cg_guard", "`depth` or a more specific `symbol`",
        "cg_context", "a narrower `task`",
        "cg_health", "`package` (hotspots, packages) or `type` (dead)");

    /** Characters kept free below the cap for the line that says a limit was lowered. */
    private static final int CAP_NOTE_ROOM = 250;

    /** The closing sentence of a cut note: only parameters {@code tool} has, and nothing when none narrows it. */
    private static String narrowing(String tool) {
        String also = NARROWING.get(tool);
        if (LIMIT_DEFAULTS.containsKey(tool)) {
            return " Lower `limit`" + (also == null ? "." : " or narrow with " + also + ".");
        }
        return also == null ? "" : " Narrow with " + also + ".";
    }

    /**
     * A tool call as a client sees it: the handler's reply held under the cap, or an unindexed
     * {@code projectPath} explained instead of raised as a protocol error.
     */
    private CallToolResult guarded(String tool, Map<String, Object> args) {
        return new CallToolResult(List.of(new TextContent(reply(tool, args))), false, null, null);
    }

    String reply(String tool, Map<String, Object> args) {
        try {
            String text = handle(tool, args);
            if (text.length() <= MAX_OUTPUT_LENGTH || !LIMIT_DEFAULTS.containsKey(tool)) {
                return truncateOutput(text, tool);
            }
            return withinCap(tool, args, text);
        } catch (ProjectNotIndexedException e) {
            return truncateOutput(e.getMessage(), tool);
        }
    }

    /**
     * A list tool's reply when its {@code limit} does not fit the cap: the handler re-run with the
     * largest {@code limit} that does, so every list ends on its own "N more" footer instead of being
     * cut mid-row, then one line saying the limit was lowered and what narrows the query instead.
     * Raising {@code limit} again cannot help, which the footer alone would not say.
     *
     * <p>The largest limit is found by bisection. A list is complete from some row count on, and a
     * reply stops changing as {@code limit} grows past it, so shrinking the limit by a ratio (as this
     * once did, a dozen passes of it) moves nothing until it drops below that count, and ran out of
     * passes first when the limit asked for was twice the rows that exist. A row is a line, so the
     * lines of the first reply bound the rows it holds and start the search.
     */
    private String withinCap(String tool, Map<String, Object> args, String text) {
        int asked = getIntArg(args, "limit", LIMIT_DEFAULTS.get(tool));
        int room = MAX_OUTPUT_LENGTH - CAP_NOTE_ROOM;
        int fits = 0;                                              // largest limit measured to fit
        int over = (int) Math.min(asked, text.lines().count());    // smallest limit known not to fit
        String best = null;
        String smallestOver = text;
        while (over - fits > 1) {
            int probe = (fits + over) >>> 1;
            var lowered = new HashMap<>(args);
            lowered.put("limit", probe);
            String reply = handle(tool, lowered);
            if (reply.length() <= room) {
                fits = probe;
                best = reply;
            } else {
                over = probe;
                smallestOver = reply;
            }
        }
        if (best == null) return truncateOutput(smallestOver, tool);
        String also = NARROWING.get(tool);
        String note = "\n(`limit` lowered from " + asked + " to " + fits + ": a reply is capped at "
            + String.format(Locale.ROOT, "%,d", MAX_OUTPUT_LENGTH) + " chars."
            + (also == null ? "" : " Narrow with " + also + " instead.") + ")\n";
        return best.length() + note.length() <= MAX_OUTPUT_LENGTH ? best + note : truncateOutput(best, tool);
    }

    private String handle(String tool, Map<String, Object> args) {
        return switch (tool) {
            case "cg_map" -> handleCgMapTool(args);
            case "cg_search" -> handleCgSearchTool(args);
            case "cg_node" -> handleCgNodeTool(args);
            case "cg_related" -> handleCgRelated(args);
            case "cg_guard" -> handleCgGuard(args);
            case "cg_context" -> handleCgContext(args);
            case "cg_health" -> handleCgHealth(args);
            case "cg_deps" -> handleCgDeps(args);
            case "cg_export" -> handleCgExport(args);
            default -> throw new IllegalArgumentException("Unknown tool: " + tool);
        };
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
        result.put("projectPath", propString("Other project's absolute root"));
        return result;
    }
}
