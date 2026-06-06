package com.codenavigator.mcp;

import com.codenavigator.briefing.BriefingGenerator;
import com.codenavigator.cli.ProjectPaths;
import com.codenavigator.domain.DomainToolHandlers;
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

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class CodeNavigatorMcpServer {

    private static final int MAX_OUTPUT_LENGTH = 15_000;

    private final GraphStore store;
    private final GraphTraversal traversal;
    private final SearchService searchService;
    private final DomainToolHandlers domainHandlers;
    private final ExportService exportService = new ExportService();
    private final Map<String, GraphStore> projectStores = new HashMap<>();

    public CodeNavigatorMcpServer(GraphStore store, GraphTraversal traversal, SearchService searchService, DomainToolHandlers domainHandlers) {
        this.store = store;
        this.traversal = traversal;
        this.searchService = searchService;
        this.domainHandlers = domainHandlers;
    }

    public void start() {
        var transportProvider = new StdioServerTransportProvider(new JacksonMcpJsonMapper(JsonMapper.builder().build()));

        var server = McpServer.sync(transportProvider)
            .serverInfo("code-navigator", "0.1.0")
            .toolCall(
                Tool.builder()
                    .name("cg_chain")
                    .description("Trace full CQRS/DDD chain from any symbol. Shows all connected nodes (controller->command->handler->aggregate->event->projection->view).")
                    .inputSchema(jsonSchema(withProjectPath(Map.of("symbol", propString("Symbol name or qualified name to trace"))), List.of("symbol")))
                    .build(),
                (exchange, request) -> textResult(handleCgChain(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("cg_impact")
                    .description("Change blast radius - find all nodes within N edges of a symbol.")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of("symbol", propString("Symbol name or qualified name"),
                               "depth", propInt("Max traversal depth (default 2)"))),
                        List.of("symbol")))
                    .build(),
                (exchange, request) -> textResult(handleCgImpact(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("cg_guard")
                    .description("Domain-aware modification guard — before an edit, reports: blast radius, DDD node types touched (calls out AGGREGATE/COMMAND/DOMAIN_EVENT/COMMAND_HANDLER/PROJECTION_HANDLER), bounded contexts touched, and matching domain business rules.")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of(
                            "symbol", propString("Symbol name or qualified name to guard"),
                            "depth", propInt("Blast-radius traversal depth (default 2)"))),
                        List.of("symbol")))
                    .build(),
                (exchange, request) -> textResult(handleCgGuard(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("cg_context")
                    .description("Smart context builder - given a task description, finds all relevant files and their chain connections.")
                    .inputSchema(jsonSchema(withProjectPath(Map.of("task", propString("Task description (e.g. 'modify order creation')"))), List.of("task")))
                    .build(),
                (exchange, request) -> textResult(handleCgContext(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("cg_search")
                    .description("Full-text search across all indexed symbols.")
                    .inputSchema(jsonSchema(withProjectPath(Map.of("query", propString("Search query"))), List.of("query")))
                    .build(),
                (exchange, request) -> textResult(handleCgSearch(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("cg_overview")
                    .description("Aggregate/service overview - shows full structure grouped by node type.")
                    .inputSchema(jsonSchema(withProjectPath(Map.of("name", propString("Name of aggregate, service, or controller"))), List.of("name")))
                    .build(),
                (exchange, request) -> textResult(handleCgOverview(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("cg_map")
                    .description("System-wide map - lists all aggregates/controllers with counts of related nodes.")
                    .inputSchema(jsonSchema(withProjectPath(Map.of()), List.of()))
                    .build(),
                (exchange, request) -> textResult(handleCgMap(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("cg_callers")
                    .description("Find all callers of a symbol (who calls this?). Traces incoming call edges transitively.")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of("symbol", propString("Symbol name or qualified name"),
                               "depth", propInt("Max traversal depth (default 5)"),
                               "limit", propInt("Max results to return (default 20)"))),
                        List.of("symbol")))
                    .build(),
                (exchange, request) -> textResult(handleCgCallers(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("cg_callees")
                    .description("Find all callees of a symbol (what does this call?). Traces outgoing call edges transitively.")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of("symbol", propString("Symbol name or qualified name"),
                               "depth", propInt("Max traversal depth (default 5)"),
                               "limit", propInt("Max results to return (default 20)"))),
                        List.of("symbol")))
                    .build(),
                (exchange, request) -> textResult(handleCgCallees(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("cg_node")
                    .description("Show detailed info about a single node: type, qualified name, file, line number, code snippet, and all edges.")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of("symbol", propString("Symbol name or qualified name"),
                               "includeCode", propString("Include code snippet (default true)"))),
                        List.of("symbol")))
                    .build(),
                (exchange, request) -> textResult(handleCgNode(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("cg_status")
                    .description("Show graph status: tier, node/edge/file counts, and node type breakdown.")
                    .inputSchema(jsonSchema(withProjectPath(Map.of()), List.of()))
                    .build(),
                (exchange, request) -> textResult(handleCgStatus(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("cg_files")
                    .description("List all indexed files with node counts. Optional pattern filter.")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of("pattern", propString("Optional glob/substring filter on file paths"))),
                        List.of()))
                    .build(),
                (exchange, request) -> textResult(handleCgFiles(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("cg_hotspots")
                    .description("Find architectural hotspots - nodes with highest fan-in (change risk) and fan-out (complexity risk).")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of("limit", propInt("Max results per category (default 10)"))),
                        List.of()))
                    .build(),
                (exchange, request) -> textResult(handleCgHotspots(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("cg_coupling")
                    .description("Show files that historically change together with a given symbol or file path. Uses git co-change mining to surface coupling.")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of(
                            "symbol", propString("Symbol name or qualified name (resolved to its file path)"),
                            "file",   propString("Relative file path (alternative to symbol)"),
                            "limit",  propInt("Max results to return (default 10)"))),
                        List.of()))
                    .build(),
                (exchange, request) -> textResult(handleCgCoupling(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("cg_deps")
                    .description("List declared build dependencies and the count of internal nodes that touch each one (USES_LIBRARY edges). Shows what the project actually uses from each dependency.")
                    .inputSchema(jsonSchema(withProjectPath(Map.of()), List.of()))
                    .build(),
                (exchange, request) -> textResult(handleCgDeps(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("cg_dead")
                    .description("Find potentially dead code - nodes with no incoming edges (nothing references them).")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of("type", propString("Optional NodeType filter (e.g. COMMAND, SERVICE)"))),
                        List.of()))
                    .build(),
                (exchange, request) -> textResult(handleCgDead(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("cg_packages")
                    .description("Show package-level dependency graph with inter-package edges and circular dependency detection.")
                    .inputSchema(jsonSchema(withProjectPath(Map.of()), List.of()))
                    .build(),
                (exchange, request) -> textResult(handleCgPackages(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("cg_export")
                    .description("Export graph as JSON, Mermaid diagram, or PlantUML. Optionally scope to a symbol's chain.")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of(
                            "format", propString("Output format: json, mermaid, plantuml"),
                            "symbol", propString("Optional: scope export to this symbol's chain"))),
                        List.of("format")))
                    .build(),
                (exchange, request) -> textResult(handleCgExport(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("cg_briefing")
                    .description("Generate compact codebase index files (.ai-briefing/) for AI assistants. Reads both code graph and domain knowledge.")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of("output", propString("Output directory (default: .ai-briefing)"))),
                        List.of()))
                    .build(),
                (exchange, request) -> textResult(handleCgBriefing(request.arguments()))
            )
            .toolCall(
                Tool.builder().name("dm_context")
                    .description("List bounded contexts — shows domains, their responsibilities, owners, and how they communicate.")
                    .inputSchema(jsonSchema(Map.of(), List.of()))
                    .build(),
                (exchange, request) -> textResult(domainHandlers.handleDmContext(request.arguments())))
            .toolCall(
                Tool.builder().name("dm_glossary")
                    .description("Query business terms — definitions, aliases, related entities.")
                    .inputSchema(jsonSchema(
                        Map.of("term", propString("Term name to look up (optional)"),
                               "context", propString("Filter by bounded context (optional)")),
                        List.of()))
                    .build(),
                (exchange, request) -> textResult(domainHandlers.handleDmGlossary(request.arguments())))
            .toolCall(
                Tool.builder().name("dm_flow")
                    .description("Trace business flows — step-by-step processes with actors and failure handling.")
                    .inputSchema(jsonSchema(
                        Map.of("name", propString("Flow name (optional)"),
                               "context", propString("Filter by bounded context (optional)")),
                        List.of()))
                    .build(),
                (exchange, request) -> textResult(domainHandlers.handleDmFlow(request.arguments())))
            .toolCall(
                Tool.builder().name("dm_rules")
                    .description("Query business rules — invariants with severity levels.")
                    .inputSchema(jsonSchema(
                        Map.of("entity", propString("Filter by entity name (optional)"),
                               "context", propString("Filter by bounded context (optional)"),
                               "severity", propString("Filter by severity: ERROR, WARNING, INFO (optional)")),
                        List.of()))
                    .build(),
                (exchange, request) -> textResult(domainHandlers.handleDmRules(request.arguments())))
            .toolCall(
                Tool.builder().name("dm_entity")
                    .description("Describe domain entities — fields, state transitions, code mappings.")
                    .inputSchema(jsonSchema(
                        Map.of("name", propString("Entity name (optional)"),
                               "context", propString("Filter by bounded context (optional)"),
                               "type", propString("Filter by type: aggregate, entity, value-object, event, command (optional)")),
                        List.of()))
                    .build(),
                (exchange, request) -> textResult(domainHandlers.handleDmEntity(request.arguments())))
            .toolCall(
                Tool.builder().name("dm_explain")
                    .description("Answer a business question by searching across all domain knowledge.")
                    .inputSchema(jsonSchema(
                        Map.of("question", propString("Business question to answer")),
                        List.of("question")))
                    .build(),
                (exchange, request) -> textResult(domainHandlers.handleDmExplain(request.arguments())))
            .build();

        // Block to keep stdio server alive
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            projectStores.values().forEach(s -> { try { s.close(); } catch (Exception ignored) {} });
            server.close();
        }
    }

    // ---- Tool handlers (package-visible for testing) ----

    String handleCgChain(Map<String, Object> args) {
        String symbol = (String) args.get("symbol");
        String nodeId = resolveSymbol(symbol);
        if (nodeId == null) return "Symbol '" + symbol + "' not found.";

        var nodes = traversal.traceChain(nodeId);
        return formatGroupedByType("Chain for " + symbol, nodes);
    }

    String handleCgImpact(Map<String, Object> args) {
        String symbol = (String) args.get("symbol");
        int depth = getIntArg(args, "depth", 2);
        String nodeId = resolveSymbol(symbol);
        if (nodeId == null) return "Symbol '" + symbol + "' not found.";

        var originNode = store.findNodeById(nodeId).orElseThrow();
        String originFile = originNode.filePath();

        // Pre-fetch co-change counts for the origin file (up to 200 entries as ranking signal)
        var coupled = store.topCoupled(originFile, 200);
        Map<String, Integer> coChangeByFile = new java.util.HashMap<>();
        for (var pair : coupled) {
            coChangeByFile.put(pair.otherFile(), pair.count());
        }

        var nodes = traversal.impact(nodeId, depth);
        var sb = new StringBuilder();
        sb.append("## Impact of ").append(symbol).append(" (depth ").append(depth).append(")\n\n");
        sb.append(nodes.size()).append(" affected node(s):\n\n");
        for (var node : nodes) {
            String line = formatNodeLine(node);
            int coCount = coChangeByFile.getOrDefault(node.filePath(), 0);
            if (coCount > 0) {
                line += " _(co-change: " + coCount + " commit(s))_";
            }
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    String handleCgGuard(Map<String, Object> args) {
        String symbol = (String) args.get("symbol");
        int depth = getIntArg(args, "depth", 2);
        String nodeId = resolveSymbol(symbol);
        if (nodeId == null) return "Symbol '" + symbol + "' not found.";

        var builder = new GuardReportBuilder(store, traversal, domainHandlers.store());
        return builder.build(nodeId, depth);
    }

    String handleCgContext(Map<String, Object> args) {
        String task = (String) args.get("task");
        var nodes = searchService.contextSearch(task);
        if (nodes.isEmpty()) return "No relevant nodes found for task: " + task;

        var byFile = nodes.stream().collect(Collectors.groupingBy(
            Node::filePath, LinkedHashMap::new, Collectors.toList()));

        var sb = new StringBuilder();
        sb.append("## Context for: ").append(task).append("\n\n");
        sb.append(nodes.size()).append(" relevant node(s) across ").append(byFile.size()).append(" file(s):\n\n");
        for (var entry : byFile.entrySet()) {
            sb.append("### ").append(entry.getKey()).append("\n");
            for (var node : entry.getValue()) {
                sb.append("- **").append(node.type()).append("** `").append(node.name()).append("`\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    String handleCgSearch(Map<String, Object> args) {
        var resolvedStore = resolveStore(args);
        var resolvedSearch = resolvedStore == store ? searchService
            : new SearchService(resolvedStore, new GraphTraversal(resolvedStore));
        String query = (String) args.get("query");
        var nodes = resolvedSearch.search(query);
        if (nodes.isEmpty()) return "No results for query: " + query;

        var sb = new StringBuilder();
        sb.append("## Search results for: ").append(query).append("\n\n");
        for (var node : nodes) {
            sb.append(formatNodeLine(node)).append("\n");
        }
        return sb.toString();
    }

    String handleCgOverview(Map<String, Object> args) {
        String name = (String) args.get("name");
        String nodeId = resolveSymbol(name);
        if (nodeId == null) return "Symbol '" + name + "' not found.";

        var nodes = traversal.traceChain(nodeId);
        return formatGroupedByType("Overview: " + name, nodes);
    }

    String handleCgMap(Map<String, Object> args) {
        String project = store.getConfig("tier");
        List<Node> roots;
        if ("DDD".equals(project)) {
            roots = store.findNodesByType(NodeType.AGGREGATE);
        } else {
            roots = store.findNodesByType(NodeType.CONTROLLER);
        }

        if (roots.isEmpty()) {
            roots = store.findNodesByType(NodeType.CONTROLLER);
        }

        var sb = new StringBuilder();
        sb.append("## System Map\n\n");
        sb.append("Tier: ").append(project != null ? project : "unknown").append(" | ");
        sb.append(roots.size()).append(" root(s)\n\n");

        for (var root : roots) {
            var chain = traversal.traceChain(root.id());
            var counts = chain.stream().collect(Collectors.groupingBy(Node::type, Collectors.counting()));
            sb.append("### ").append(root.type()).append(": ").append(root.name()).append("\n");
            for (var entry : counts.entrySet()) {
                sb.append("  - ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
            sb.append("\n");
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
        String symbol = (String) args.get("symbol");
        int depth = getIntArg(args, "depth", 5);
        int limit = getIntArg(args, "limit", 20);
        String nodeId = resolveSymbol(symbol);
        if (nodeId == null) return "Symbol '" + symbol + "' not found.";

        var nodes = incoming ? traversal.callers(nodeId, depth) : traversal.callees(nodeId, depth);
        var sb = new StringBuilder();
        sb.append("## ").append(label).append(" of ").append(symbol).append("\n\n");
        sb.append(nodes.size()).append(" ").append(label.toLowerCase()).append(" found");
        if (nodes.size() > limit) sb.append(" (showing first ").append(limit).append(")");
        sb.append(":\n\n");
        nodes.stream().limit(limit).forEach(n -> sb.append(formatNodeLine(n)).append("\n"));
        return sb.toString();
    }

    String handleCgNode(Map<String, Object> args) {
        String symbol = (String) args.get("symbol");
        boolean includeCode = !args.containsKey("includeCode") || !"false".equals(String.valueOf(args.get("includeCode")));
        String nodeId = resolveSymbol(symbol);
        if (nodeId == null) return "Symbol '" + symbol + "' not found.";

        var node = store.findNodeById(nodeId).orElseThrow();
        var sb = new StringBuilder();
        sb.append("## ").append(node.name()).append("\n\n");
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
        String project = store.getConfig("tier");
        int nodeCount = store.getNodeCount();
        int edgeCount = store.getEdgeCount();
        int fileCount = store.getFileCount();

        var sb = new StringBuilder();
        sb.append("## Code Navigator Status\n\n");
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
        int limit = getIntArg(args, "limit", 10);

        var fanIn = store.findTopFanIn(limit);
        var fanOut = store.findTopFanOut(limit);

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
        int limit = getIntArg(args, "limit", 10);

        String filePath = null;

        if (args.containsKey("symbol")) {
            String symbol = (String) args.get("symbol");
            String nodeId = resolveSymbol(symbol);
            if (nodeId == null) return "Symbol '" + symbol + "' not found.";
            var node = store.findNodeById(nodeId).orElseThrow();
            filePath = node.filePath();
        } else if (args.containsKey("file")) {
            filePath = (String) args.get("file");
        }

        if (filePath == null) return "Provide either 'symbol' or 'file'.";

        var coupled = store.topCoupled(filePath, limit);
        if (coupled.isEmpty()) return "No co-change data for: " + filePath;

        var sb = new StringBuilder();
        sb.append("## Co-change coupling for `").append(filePath).append("`\n\n");
        sb.append("Top ").append(coupled.size()).append(" historically co-changed file(s):\n\n");
        for (int i = 0; i < coupled.size(); i++) {
            var pair = coupled.get(i);
            sb.append(String.format(" %d. `%s` — %d commit(s)%n", i + 1, pair.otherFile(), pair.count()));
        }
        return sb.toString();
    }

    String handleCgDeps(Map<String, Object> args) {
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

    String handleCgDead(Map<String, Object> args) {
        NodeType typeFilter = null;
        if (args.containsKey("type")) {
            try {
                typeFilter = NodeType.valueOf(((String) args.get("type")).toUpperCase());
            } catch (IllegalArgumentException e) {
                return "Unknown type: " + args.get("type");
            }
        }

        var dead = store.findDeadNodes(typeFilter);
        if (dead.isEmpty()) return "No dead code found.";

        var byType = dead.stream().collect(Collectors.groupingBy(
            Node::type, LinkedHashMap::new, Collectors.toList()));

        var sb = new StringBuilder();
        sb.append("## Potentially Dead Code\n\n");
        for (var entry : byType.entrySet()) {
            sb.append("### ").append(entry.getKey()).append(" (").append(entry.getValue().size()).append(")\n");
            for (var node : entry.getValue()) {
                sb.append("- `").append(node.name()).append("` (").append(node.filePath()).append(":").append(node.lineNumber()).append(")\n");
            }
            sb.append("\n");
        }
        sb.append("Total: ").append(dead.size()).append(" unreferenced symbols\n");
        return sb.toString();
    }

    String handleCgFiles(Map<String, Object> args) {
        String pattern = args.containsKey("pattern") ? (String) args.get("pattern") : null;

        var files = store.getAllIndexedFiles();
        if (pattern != null && !pattern.isEmpty()) {
            String lowerPattern = pattern.toLowerCase();
            files = files.stream().filter(f -> f.toLowerCase().contains(lowerPattern)).toList();
        }

        var sb = new StringBuilder();
        sb.append("## Indexed Files\n\n");
        sb.append(files.size()).append(" file(s):\n\n");
        for (var file : files) {
            var nodes = store.findNodesByFilePath(file);
            sb.append("- ").append(file).append(" (").append(nodes.size()).append(" nodes)\n");
        }
        return sb.toString();
    }

    String handleCgExport(Map<String, Object> args) {
        String format = (String) args.get("format");
        if (format == null) return "Missing required parameter: format";

        List<Node> nodes;
        List<Edge> edges;

        if (args.containsKey("symbol")) {
            String symbol = (String) args.get("symbol");
            String nodeId = resolveSymbol(symbol);
            if (nodeId == null) return "Symbol '" + symbol + "' not found.";
            nodes = traversal.traceChain(nodeId);
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

    String handleCgBriefing(Map<String, Object> args) {
        String projectPath = args.containsKey("projectPath") ? (String) args.get("projectPath") : null;
        String outputName = args.containsKey("output") ? (String) args.get("output") : ".ai-briefing";
        var root = projectPath != null ? java.nio.file.Path.of(projectPath)
            : java.nio.file.Path.of(System.getenv("CODE_NAVIGATOR_PROJECT") != null
                ? System.getenv("CODE_NAVIGATOR_PROJECT") : ".");
        var outputDir = root.resolve(outputName);
        try {
            var generator = new BriefingGenerator(store, domainHandlers.store());
            generator.generate(outputDir);
            return "Briefing generated at " + outputDir.toAbsolutePath();
        } catch (Exception e) {
            return "Briefing generation failed: " + e.getMessage();
        }
    }

    String handleCgPackages(Map<String, Object> args) {
        var allNodes = store.getAllNodes();
        var allEdges = store.getAllEdges();

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

    private GraphStore resolveStore(Map<String, Object> args) {
        String projectPath = args.containsKey("projectPath") ? (String) args.get("projectPath") : null;
        if (projectPath == null) return store;
        var root = Path.of(projectPath);
        if (!ProjectPaths.hasIndex(root)) return store;
        return projectStores.computeIfAbsent(projectPath, p -> new GraphStore(ProjectPaths.graphDb(root)));
    }

    private String resolveSymbol(String symbol) {
        if (store.findNodeById(symbol).isPresent()) return symbol;
        var candidates = store.getAllNodes().stream()
            .filter(n -> n.name().equalsIgnoreCase(symbol) || n.qualifiedName().equalsIgnoreCase(symbol))
            .toList();
        return candidates.isEmpty() ? null : candidates.get(0).id();
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

    static String truncateOutput(String text) {
        if (text.length() <= MAX_OUTPUT_LENGTH) return text;
        return text.substring(0, MAX_OUTPUT_LENGTH) + "\n\n... (output truncated at " + MAX_OUTPUT_LENGTH + " chars)\n";
    }

    private static CallToolResult textResult(String text) {
        return new CallToolResult(List.of(new TextContent(truncateOutput(text))), false, null, null);
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
