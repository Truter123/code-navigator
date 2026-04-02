package com.agentmemory.mcp;

import com.agentmemory.brain.BrainEngine;
import com.agentmemory.model.*;
import com.agentmemory.ws.EventBus;
import com.agentmemory.store.GraphStore;
import com.agentmemory.store.MemoryStore;
import tools.jackson.databind.json.JsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class AgentMemoryMcpServer {

    private final MemoryStore memoryStore;
    private final GraphStore graphStore;
    private final BrainEngine brain;
    private final EventBus eventBus;

    public AgentMemoryMcpServer(MemoryStore memoryStore, GraphStore graphStore, BrainEngine brain, EventBus eventBus) {
        this.memoryStore = memoryStore;
        this.graphStore = graphStore;
        this.brain = brain;
        this.eventBus = eventBus;
    }

    public void start() {
        var transportProvider = new StdioServerTransportProvider(new JacksonMcpJsonMapper(JsonMapper.builder().build()));

        McpServer.sync(transportProvider)
            .serverInfo("agent-memory", "0.1.0")
            .toolCall(
                Tool.builder()
                    .name("mem_store")
                    .description("Store or update a memory. Triggers contradiction check. Tags and importance help with retrieval.")
                    .inputSchema(jsonSchema(Map.of(
                        "key", propString("Unique key for this memory"),
                        "value", propString("The content to store"),
                        "agent", propString("Agent identifier"),
                        "project", propString("Project scope (optional)"),
                        "tags", propArray("Tags for categorization"),
                        "importance", propNumber("Importance score 0.0-1.0 (default 0.5)"),
                        "shared", propBool("Whether this memory is shared across agents")
                    ), List.of("key", "value", "agent")))
                    .build(),
                (exchange, request) -> timed("store", request.arguments(), this::handleStore)
            )
            .toolCall(
                Tool.builder()
                    .name("mem_recall")
                    .description("Recall a memory by key. If agent is provided, recalls for that specific agent. Otherwise returns all memories with that key.")
                    .inputSchema(jsonSchema(Map.of(
                        "key", propString("Memory key to recall"),
                        "agent", propString("Agent identifier (optional, narrows to specific agent)")
                    ), List.of("key")))
                    .build(),
                (exchange, request) -> timed("recall", request.arguments(), this::handleRecall)
            )
            .toolCall(
                Tool.builder()
                    .name("mem_search")
                    .description("Full-text search across memories using FTS5.")
                    .inputSchema(jsonSchema(Map.of(
                        "query", propString("Search query text"),
                        "agent", propString("Filter by agent (optional)"),
                        "project", propString("Filter by project (optional)"),
                        "tags", propArray("Filter by tags (optional)"),
                        "limit", propInt("Max results (default 20)")
                    ), List.of("query")))
                    .build(),
                (exchange, request) -> timed("search", request.arguments(), this::handleSearch)
            )
            .toolCall(
                Tool.builder()
                    .name("mem_delete")
                    .description("Soft-delete a memory by key and agent.")
                    .inputSchema(jsonSchema(Map.of(
                        "key", propString("Memory key to delete"),
                        "agent", propString("Agent that owns the memory")
                    ), List.of("key", "agent")))
                    .build(),
                (exchange, request) -> timed("delete", request.arguments(), this::handleDelete)
            )
            .toolCall(
                Tool.builder()
                    .name("mem_list")
                    .description("List memories with optional filters and pagination.")
                    .inputSchema(jsonSchema(Map.of(
                        "agent", propString("Filter by agent (optional)"),
                        "project", propString("Filter by project (optional)"),
                        "tags", propArray("Filter by tags (optional)"),
                        "shared", propBool("Filter by shared status (optional)"),
                        "limit", propInt("Max results (default 20)"),
                        "offset", propInt("Pagination offset (default 0)")
                    ), List.of()))
                    .build(),
                (exchange, request) -> timed("list", request.arguments(), this::handleList)
            )
            .toolCall(
                Tool.builder()
                    .name("mem_history")
                    .description("Show version history for a memory.")
                    .inputSchema(jsonSchema(Map.of(
                        "key", propString("Memory key"),
                        "agent", propString("Agent that owns the memory")
                    ), List.of("key", "agent")))
                    .build(),
                (exchange, request) -> timed("history", request.arguments(), this::handleHistory)
            )
            .toolCall(
                Tool.builder()
                    .name("mem_share")
                    .description("Mark a memory as shared across agents.")
                    .inputSchema(jsonSchema(Map.of(
                        "key", propString("Memory key to share"),
                        "agent", propString("Agent that owns the memory")
                    ), List.of("key", "agent")))
                    .build(),
                (exchange, request) -> timed("share", request.arguments(), this::handleShare)
            )
            .toolCall(
                Tool.builder()
                    .name("mem_shared")
                    .description("List all shared memories, optionally filtered by project or tags.")
                    .inputSchema(jsonSchema(Map.of(
                        "project", propString("Filter by project (optional)"),
                        "tags", propArray("Filter by tags (optional)"),
                        "limit", propInt("Max results (default 20)")
                    ), List.of()))
                    .build(),
                (exchange, request) -> timed("shared", request.arguments(), this::handleShared)
            )
            .toolCall(
                Tool.builder()
                    .name("mem_goals")
                    .description("Register active goals for an agent. Replaces any existing active goals.")
                    .inputSchema(jsonSchema(Map.of(
                        "agent", propString("Agent identifier"),
                        "goals", propArray("List of goal descriptions"),
                        "project", propString("Project scope (optional)")
                    ), List.of("agent", "goals")))
                    .build(),
                (exchange, request) -> timed("goals", request.arguments(), this::handleGoals)
            )
            .toolCall(
                Tool.builder()
                    .name("mem_check")
                    .description("Run brain analysis: detect loops, drift, and contradictions for an agent.")
                    .inputSchema(jsonSchema(Map.of(
                        "agent", propString("Agent identifier"),
                        "project", propString("Project scope (optional)")
                    ), List.of("agent")))
                    .build(),
                (exchange, request) -> timed("check", request.arguments(), this::handleCheck)
            )
            .toolCall(
                Tool.builder()
                    .name("mem_link")
                    .description("Create a typed link between two memories in the knowledge graph.")
                    .inputSchema(jsonSchema(Map.of(
                        "source_key", propString("Source memory key"),
                        "target_key", propString("Target memory key"),
                        "relation", propString("Relation type (e.g. 'depends_on', 'contradicts', 'related_to')"),
                        "agent", propString("Agent identifier")
                    ), List.of("source_key", "target_key", "relation", "agent")))
                    .build(),
                (exchange, request) -> timed("link", request.arguments(), this::handleLink)
            )
            .toolCall(
                Tool.builder()
                    .name("mem_graph")
                    .description("Traverse the knowledge graph from a starting memory, showing connected memories up to a given depth.")
                    .inputSchema(jsonSchema(Map.of(
                        "key", propString("Starting memory key"),
                        "depth", propInt("Max traversal depth (default 2)"),
                        "agent", propString("Agent filter (optional)")
                    ), List.of("key")))
                    .build(),
                (exchange, request) -> timed("graph", request.arguments(), this::handleGraph)
            )
            .build();

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- Handlers ---

    private String handleStore(Map<String, Object> args) {
        String key = (String) args.get("key");
        String value = (String) args.get("value");
        String agent = (String) args.get("agent");
        String project = (String) args.get("project");
        List<String> tags = toStringList(args.get("tags"));
        double importance = toDouble(args.get("importance"), 0.5);
        boolean shared = toBoolean(args.get("shared"), false);

        memoryStore.upsert(key, value, agent, project, tags, importance, shared);
        if (eventBus != null) {
            eventBus.publish("memory", Map.of("action", "store", "key", key, "agent", agent, "project", project != null ? project : ""));
            eventBus.publish("agent", Map.of("name", agent, "status", "active", "event", "operation"));
        }

        List<Anomaly> anomalies = brain.onStore(key, value, agent, project);
        if (eventBus != null && !anomalies.isEmpty()) {
            for (Anomaly a : anomalies) {
                eventBus.publish("anomaly", a);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Stored memory: ").append(key).append("\n");
        sb.append("Agent: ").append(agent).append("\n");
        if (project != null) sb.append("Project: ").append(project).append("\n");
        if (!tags.isEmpty()) sb.append("Tags: ").append(String.join(", ", tags)).append("\n");
        sb.append("Importance: ").append(importance).append("\n");
        sb.append("Shared: ").append(shared).append("\n");

        if (!anomalies.isEmpty()) {
            sb.append("\n--- Anomalies Detected ---\n");
            for (Anomaly a : anomalies) {
                sb.append("[").append(a.severity()).append("] ").append(a.type())
                  .append(": ").append(a.description()).append("\n");
            }
        }

        return sb.toString();
    }

    private String handleRecall(Map<String, Object> args) {
        String key = (String) args.get("key");
        String agent = (String) args.get("agent");

        if (agent != null) {
            Optional<Memory> mem = memoryStore.recallByKeyAndAgent(key, agent);
            return mem.map(this::formatMemory).orElse("No memory found for key '" + key + "' and agent '" + agent + "'");
        } else {
            List<Memory> memories = memoryStore.recallAllByKey(key);
            if (memories.isEmpty()) return "No memories found for key '" + key + "'";
            return memories.stream().map(this::formatMemory).collect(Collectors.joining("\n---\n"));
        }
    }

    private String handleSearch(Map<String, Object> args) {
        String query = (String) args.get("query");
        String agent = (String) args.get("agent");
        String project = (String) args.get("project");
        List<String> tags = toStringList(args.get("tags"));
        int limit = toInt(args.get("limit"), 20);

        List<Memory> results = memoryStore.search(query, agent, project, tags, limit);
        if (results.isEmpty()) return "No results for query: " + query;
        return results.stream().map(this::formatMemory).collect(Collectors.joining("\n---\n"));
    }

    private String handleDelete(Map<String, Object> args) {
        String key = (String) args.get("key");
        String agent = (String) args.get("agent");

        memoryStore.softDeleteByKeyAndAgent(key, agent);
        if (eventBus != null) {
            eventBus.publish("memory", Map.of("action", "delete", "key", key, "agent", agent));
        }
        return "Soft-deleted memory: " + key + " (agent: " + agent + ")";
    }

    private String handleList(Map<String, Object> args) {
        String agent = (String) args.get("agent");
        String project = (String) args.get("project");
        List<String> tags = toStringList(args.get("tags"));
        Boolean shared = args.containsKey("shared") ? toBoolean(args.get("shared"), false) : null;
        int limit = toInt(args.get("limit"), 20);
        int offset = toInt(args.get("offset"), 0);

        List<Memory> memories = memoryStore.list(agent, project, tags, shared, limit, offset);
        if (memories.isEmpty()) return "No memories found matching filters.";

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(memories.size()).append(" memories (offset: ").append(offset).append(")\n\n");
        sb.append(memories.stream().map(this::formatMemoryCompact).collect(Collectors.joining("\n")));
        return sb.toString();
    }

    private String handleHistory(Map<String, Object> args) {
        String key = (String) args.get("key");
        String agent = (String) args.get("agent");

        List<MemoryVersion> versions = memoryStore.getVersions(key, agent, null);

        Optional<Memory> current = memoryStore.recallByKeyAndAgent(key, agent);
        StringBuilder sb = new StringBuilder();
        sb.append("History for '").append(key).append("' (agent: ").append(agent).append(")\n\n");

        if (current.isPresent()) {
            sb.append("Current: ").append(current.get().value()).append("\n");
            sb.append("Updated: ").append(current.get().updatedAt()).append("\n\n");
        }

        if (versions.isEmpty()) {
            sb.append("No previous versions.");
        } else {
            sb.append("Previous versions:\n");
            for (MemoryVersion v : versions) {
                sb.append("  v").append(v.version()).append(": ").append(v.value())
                  .append(" (").append(v.createdAt()).append(")\n");
            }
        }
        return sb.toString();
    }

    private String handleShare(Map<String, Object> args) {
        String key = (String) args.get("key");
        String agent = (String) args.get("agent");

        memoryStore.share(key, agent, null);
        if (eventBus != null) {
            eventBus.publish("memory", Map.of("action", "share", "key", key, "agent", agent));
        }
        return "Memory '" + key + "' is now shared (agent: " + agent + ")";
    }

    private String handleShared(Map<String, Object> args) {
        String project = (String) args.get("project");
        List<String> tags = toStringList(args.get("tags"));
        int limit = toInt(args.get("limit"), 20);

        List<Memory> shared = memoryStore.list(null, project, tags, true, limit, 0);
        if (shared.isEmpty()) return "No shared memories found.";
        return shared.stream().map(this::formatMemory).collect(Collectors.joining("\n---\n"));
    }

    private String handleGoals(Map<String, Object> args) {
        String agent = (String) args.get("agent");
        List<String> goals = toStringList(args.get("goals"));
        String project = (String) args.get("project");

        memoryStore.setGoals(agent, goals, project);
        if (eventBus != null) {
            eventBus.publish("goal", Map.of("agent", agent, "goals", goals, "action", "registered"));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Registered ").append(goals.size()).append(" goals for agent '").append(agent).append("':\n");
        for (int i = 0; i < goals.size(); i++) {
            sb.append("  ").append(i + 1).append(". ").append(goals.get(i)).append("\n");
        }
        return sb.toString();
    }

    private String handleCheck(Map<String, Object> args) {
        String agent = (String) args.get("agent");
        String project = (String) args.get("project");

        List<Anomaly> anomalies = brain.check(agent, project);
        if (eventBus != null && !anomalies.isEmpty()) {
            for (Anomaly a : anomalies) {
                eventBus.publish("anomaly", a);
            }
        }

        if (anomalies.isEmpty()) return "No anomalies detected for agent '" + agent + "'.";

        StringBuilder sb = new StringBuilder();
        sb.append("Brain analysis for agent '").append(agent).append("':\n\n");
        for (Anomaly a : anomalies) {
            sb.append("[").append(a.severity()).append("] ").append(a.type()).append("\n");
            sb.append("  ").append(a.description()).append("\n");
            if (a.affectedKeys() != null && !a.affectedKeys().isEmpty()) {
                sb.append("  Keys: ").append(String.join(", ", a.affectedKeys())).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String handleLink(Map<String, Object> args) {
        String sourceKey = (String) args.get("source_key");
        String targetKey = (String) args.get("target_key");
        String relation = (String) args.get("relation");
        String agent = (String) args.get("agent");

        graphStore.link(sourceKey, targetKey, relation, agent);
        if (eventBus != null) {
            eventBus.publish("graph", Map.of("source", sourceKey, "target", targetKey, "relation", relation));
        }
        return "Linked '" + sourceKey + "' --[" + relation + "]--> '" + targetKey + "'";
    }

    private String handleGraph(Map<String, Object> args) {
        String key = (String) args.get("key");
        int depth = toInt(args.get("depth"), 2);

        List<Memory> connected = graphStore.traverse(key, depth);

        StringBuilder sb = new StringBuilder();
        sb.append("Graph traversal from '").append(key).append("' (depth: ").append(depth).append(")\n\n");

        if (connected.isEmpty()) {
            sb.append("No connected memories found.");
        } else {
            sb.append("Connected memories (").append(connected.size()).append("):\n");
            for (Memory m : connected) {
                sb.append("  - ").append(m.key()).append(": ").append(truncate(m.value(), 80))
                  .append(" [agent: ").append(m.agent()).append("]\n");
            }
        }
        return sb.toString();
    }

    // --- Timing & Result ---

    private CallToolResult timed(String operation, Map<String, Object> args,
                                  Function<Map<String, Object>, String> handler) {
        long start = System.nanoTime();
        String result = handler.apply(args);
        double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
        String agent = (String) args.getOrDefault("agent", "unknown");
        String key = (String) args.get("key");
        memoryStore.logAudit(agent, operation, key, null, latencyMs);

        // Publish audit event
        if (eventBus != null) {
            eventBus.publish("audit", Map.of(
                "agent", agent,
                "operation", operation,
                "key", key != null ? key : "",
                "latencyMs", latencyMs
            ));
        }

        return textResult(result);
    }

    // --- Formatting helpers ---

    private String formatMemory(Memory m) {
        StringBuilder sb = new StringBuilder();
        sb.append("Key: ").append(m.key()).append("\n");
        sb.append("Value: ").append(m.value()).append("\n");
        sb.append("Agent: ").append(m.agent()).append("\n");
        if (m.project() != null) sb.append("Project: ").append(m.project()).append("\n");
        if (m.tags() != null && !m.tags().isEmpty()) sb.append("Tags: ").append(String.join(", ", m.tags())).append("\n");
        sb.append("Importance: ").append(m.importance()).append("\n");
        sb.append("Shared: ").append(m.shared()).append("\n");
        sb.append("Created: ").append(m.createdAt()).append("\n");
        sb.append("Updated: ").append(m.updatedAt());
        return sb.toString();
    }

    private String formatMemoryCompact(Memory m) {
        return "  [" + m.key() + "] " + truncate(m.value(), 60) + " (agent: " + m.agent() + ")";
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // --- Schema helpers ---

    private static JsonSchema jsonSchema(Map<String, Object> properties, List<String> required) {
        return new JsonSchema("object", properties, required, null, null, null);
    }

    private static Map<String, Object> propString(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> propInt(String description) {
        return Map.of("type", "integer", "description", description);
    }

    private static Map<String, Object> propNumber(String description) {
        return Map.of("type", "number", "description", description);
    }

    private static Map<String, Object> propBool(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    private static Map<String, Object> propArray(String description) {
        return Map.of("type", "array", "description", description, "items", Map.of("type", "string"));
    }

    private static CallToolResult textResult(String text) {
        return new CallToolResult(List.of(new TextContent(text)), false, null, null);
    }

    // --- Argument extraction helpers ---

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).collect(Collectors.toList());
        }
        return List.of();
    }

    private static int toInt(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(value.toString()); } catch (NumberFormatException e) { return defaultValue; }
    }

    private static double toDouble(Object value, double defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(value.toString()); } catch (NumberFormatException e) { return defaultValue; }
    }

    private static boolean toBoolean(Object value, boolean defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(value.toString());
    }
}
