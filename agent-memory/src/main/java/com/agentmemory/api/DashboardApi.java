package com.agentmemory.api;

import com.agentmemory.brain.BrainEngine;
import com.agentmemory.model.*;
import com.agentmemory.store.GraphStore;
import com.agentmemory.store.MemoryStore;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.util.*;
import java.util.stream.Collectors;

public class DashboardApi {

    private final MemoryStore memoryStore;
    private final GraphStore graphStore;
    private final BrainEngine brain;

    public DashboardApi(MemoryStore memoryStore, GraphStore graphStore, BrainEngine brain) {
        this.memoryStore = memoryStore;
        this.graphStore = graphStore;
        this.brain = brain;
    }

    public void register(JavalinDefaultRoutingApi app) {
        // Memories
        app.get("/api/memories", this::listMemories);
        app.get("/api/memories/{id}", this::getMemory);
        app.get("/api/memories/{id}/versions", this::getMemoryVersions);
        app.post("/api/memories", this::createMemory);
        app.delete("/api/memories/{id}", this::deleteMemory);

        // Agents
        app.get("/api/agents", this::listAgents);
        app.get("/api/agents/{name}/metrics", this::getAgentMetrics);

        // Graph
        app.get("/api/graph", this::getGraph);
        app.get("/api/graph/{memoryId}", this::getSubgraph);

        // Goals, Anomalies, Audit, Performance, Settings
        app.get("/api/goals", this::listGoals);
        app.get("/api/anomalies", this::listAnomalies);
        app.get("/api/audit", this::getAuditLog);
        app.get("/api/performance/timeseries", this::getTimeseries);
        app.get("/api/performance/summary", this::getPerformanceSummary);
        app.get("/api/settings", this::getSettings);
        app.put("/api/settings", this::updateSettings);
    }

    private void listMemories(Context ctx) {
        String agent = ctx.queryParam("agent");
        String project = ctx.queryParam("project");
        String tagsParam = ctx.queryParam("tags");
        String sharedParam = ctx.queryParam("shared");
        String q = ctx.queryParam("q");
        int limit = intParam(ctx, "limit", 50);
        int offset = intParam(ctx, "offset", 0);

        List<String> tags = tagsParam != null ? Arrays.asList(tagsParam.split(",")) : null;
        Boolean shared = sharedParam != null ? Boolean.parseBoolean(sharedParam) : null;

        List<Memory> memories;
        if (q != null && !q.isBlank()) {
            memories = memoryStore.search(q, agent, project, tags, limit);
        } else {
            memories = memoryStore.list(agent, project, tags, shared, limit, offset);
        }

        ctx.json(memories.stream().map(this::mapMemory).collect(Collectors.toList()));
    }

    private void getMemory(Context ctx) {
        String id = ctx.pathParam("id");
        Optional<Memory> memory = memoryStore.findById(id);
        if (memory.isEmpty()) {
            ctx.status(404).json(Map.of("error", "Memory not found"));
            return;
        }
        ctx.json(mapMemory(memory.get()));
    }

    private void getMemoryVersions(Context ctx) {
        String id = ctx.pathParam("id");
        Optional<Memory> memory = memoryStore.findById(id);
        if (memory.isEmpty()) {
            ctx.status(404).json(Map.of("error", "Memory not found"));
            return;
        }
        Memory m = memory.get();
        List<MemoryVersion> versions = memoryStore.getVersions(m.key(), m.agent(), m.project());
        ctx.json(versions.stream().map(this::mapVersion).collect(Collectors.toList()));
    }

    @SuppressWarnings("unchecked")
    private void createMemory(Context ctx) {
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String key = (String) body.get("key");
        String value = (String) body.get("value");
        String agent = (String) body.get("agent");
        String project = (String) body.get("project");
        List<String> tags = body.containsKey("tags") ? (List<String>) body.get("tags") : List.of();
        double importance = body.containsKey("importance") ? ((Number) body.get("importance")).doubleValue() : 0.5;
        boolean shared = body.containsKey("shared") && Boolean.TRUE.equals(body.get("shared"));

        memoryStore.upsert(key, value, agent, project, tags, importance, shared);
        ctx.status(201).json(Map.of("status", "created", "key", key));
    }

    private void deleteMemory(Context ctx) {
        String id = ctx.pathParam("id");
        Optional<Memory> memory = memoryStore.findById(id);
        if (memory.isEmpty()) {
            ctx.status(404).json(Map.of("error", "Memory not found"));
            return;
        }
        Memory m = memory.get();
        memoryStore.softDelete(m.key(), m.agent(), m.project());
        ctx.json(Map.of("status", "deleted", "id", id));
    }

    private void listAgents(Context ctx) {
        List<String> agents = memoryStore.getKnownAgents();
        List<Map<String, Object>> result = new ArrayList<>();
        for (String agent : agents) {
            result.add(computeAgentMetrics(agent));
        }
        ctx.json(result);
    }

    private void getAgentMetrics(Context ctx) {
        String name = ctx.pathParam("name");
        ctx.json(computeAgentMetrics(name));
    }

    private Map<String, Object> computeAgentMetrics(String agent) {
        List<AuditEntry> entries = memoryStore.getAuditLog(agent, null, null, null, 10000, 0);

        long totalWrites = entries.stream().filter(e -> isWrite(e.operation())).count();
        long totalReads = entries.stream().filter(e -> isRead(e.operation())).count();
        long errors = entries.stream().filter(e -> "error".equals(e.operation())).count();

        double avgWriteLatency = entries.stream()
                .filter(e -> isWrite(e.operation()))
                .mapToDouble(AuditEntry::latencyMs)
                .average().orElse(0.0);

        double avgReadLatency = entries.stream()
                .filter(e -> isRead(e.operation()))
                .mapToDouble(AuditEntry::latencyMs)
                .average().orElse(0.0);

        String firstSeen = entries.isEmpty() ? null :
                entries.stream().map(AuditEntry::createdAt).min(String::compareTo).orElse(null);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("name", agent);
        metrics.put("avgWriteLatency", avgWriteLatency);
        metrics.put("avgReadLatency", avgReadLatency);
        metrics.put("totalWrites", totalWrites);
        metrics.put("totalReads", totalReads);
        metrics.put("errors", errors);
        metrics.put("firstSeen", firstSeen);
        return metrics;
    }

    private boolean isWrite(String operation) {
        return "store".equals(operation) || "delete".equals(operation) ||
               "link".equals(operation) || "share".equals(operation) || "goals".equals(operation);
    }

    private boolean isRead(String operation) {
        return "recall".equals(operation) || "search".equals(operation) ||
               "list".equals(operation) || "history".equals(operation) ||
               "graph".equals(operation) || "check".equals(operation) || "shared".equals(operation);
    }

    private void getGraph(Context ctx) {
        String agent = ctx.queryParam("agent");
        String project = ctx.queryParam("project");

        List<MemoryLink> links = graphStore.getAllLinks(agent, project);

        Set<String> nodeIds = new HashSet<>();
        for (MemoryLink link : links) {
            nodeIds.add(link.sourceId());
            nodeIds.add(link.targetId());
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (String nodeId : nodeIds) {
            memoryStore.findById(nodeId).ifPresent(m -> {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("id", m.id());
                node.put("key", m.key());
                node.put("agent", m.agent());
                node.put("project", m.project());
                nodes.add(node);
            });
        }

        List<Map<String, String>> edges = links.stream().map(l -> {
            Map<String, String> edge = new LinkedHashMap<>();
            edge.put("source", l.sourceId());
            edge.put("target", l.targetId());
            edge.put("relation", l.relation());
            return edge;
        }).collect(Collectors.toList());

        ctx.json(Map.of("nodes", nodes, "edges", edges));
    }

    private void getSubgraph(Context ctx) {
        String memoryId = ctx.pathParam("memoryId");
        int depth = intParam(ctx, "depth", 2);

        Optional<Memory> memory = memoryStore.findById(memoryId);
        if (memory.isEmpty()) {
            ctx.status(404).json(Map.of("error", "Memory not found"));
            return;
        }

        Memory root = memory.get();
        List<Memory> connected = graphStore.traverse(root.key(), depth);

        List<Map<String, Object>> nodes = new ArrayList<>();
        Map<String, Object> rootNode = new LinkedHashMap<>();
        rootNode.put("id", root.id());
        rootNode.put("key", root.key());
        rootNode.put("agent", root.agent());
        rootNode.put("project", root.project());
        nodes.add(rootNode);

        for (Memory m : connected) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", m.id());
            node.put("key", m.key());
            node.put("agent", m.agent());
            node.put("project", m.project());
            nodes.add(node);
        }

        ctx.json(Map.of("nodes", nodes));
    }

    private void listGoals(Context ctx) {
        String agent = ctx.queryParam("agent");
        String status = ctx.queryParam("status");
        List<Goal> goals = memoryStore.getGoals(agent, status);
        ctx.json(goals);
    }

    private void listAnomalies(Context ctx) {
        List<String> agents = memoryStore.getKnownAgents();
        List<Anomaly> all = new ArrayList<>();
        for (String agent : agents) {
            String project = ctx.queryParam("project");
            all.addAll(brain.check(agent, project));
        }
        ctx.json(all);
    }

    private void getAuditLog(Context ctx) {
        String agent = ctx.queryParam("agent");
        String operation = ctx.queryParam("operation");
        String from = ctx.queryParam("from");
        String to = ctx.queryParam("to");
        int limit = intParam(ctx, "limit", 100);
        int offset = intParam(ctx, "offset", 0);

        List<AuditEntry> entries = memoryStore.getAuditLog(agent, operation, from, to, limit, offset);
        ctx.json(entries.stream().map(this::mapAuditEntry).collect(Collectors.toList()));
    }

    private void getTimeseries(Context ctx) {
        String agent = ctx.queryParam("agent");
        String from = ctx.queryParam("from");
        String to = ctx.queryParam("to");
        int limit = intParam(ctx, "limit", 500);

        List<AuditEntry> entries = memoryStore.getAuditLog(agent, null, from, to, limit, 0);

        List<Map<String, Object>> timeseries = entries.stream()
                .map(this::mapAuditEntry)
                .collect(Collectors.toList());

        ctx.json(timeseries);
    }

    private void getPerformanceSummary(Context ctx) {
        String agent = ctx.queryParam("agent");
        List<AuditEntry> entries = memoryStore.getAuditLog(agent, null, null, null, 10000, 0);

        double avgLatency = entries.stream().mapToDouble(AuditEntry::latencyMs).average().orElse(0.0);
        long totalOps = entries.size();
        long totalWrites = entries.stream().filter(e -> isWrite(e.operation())).count();
        long totalReads = entries.stream().filter(e -> isRead(e.operation())).count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("avgLatency", avgLatency);
        summary.put("totalOps", totalOps);
        summary.put("totalWrites", totalWrites);
        summary.put("totalReads", totalReads);
        ctx.json(summary);
    }

    private void getSettings(Context ctx) {
        ctx.json(memoryStore.getAllSettings());
    }

    @SuppressWarnings("unchecked")
    private void updateSettings(Context ctx) {
        Map<String, String> settings = ctx.bodyAsClass(Map.class);
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            memoryStore.setSetting(entry.getKey(), entry.getValue());
        }
        ctx.json(memoryStore.getAllSettings());
    }

    private Map<String, Object> mapAuditEntry(AuditEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("agentName", e.agent());
        m.put("operation", e.operation());
        m.put("key", e.memoryKey());
        m.put("details", e.details());
        m.put("latencyMs", e.latencyMs());
        m.put("timestamp", e.createdAt());
        return m;
    }

    private Map<String, Object> mapMemory(Memory mem) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", mem.id());
        m.put("key", mem.key());
        m.put("value", mem.value());
        m.put("agentName", mem.agent());
        m.put("project", mem.project());
        m.put("shared", mem.shared());
        m.put("importance", mem.importance());
        m.put("tags", mem.tags());
        m.put("createdAt", mem.createdAt());
        m.put("updatedAt", mem.updatedAt());
        return m;
    }

    private Map<String, Object> mapVersion(MemoryVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id());
        m.put("version", v.version());
        m.put("content", v.value());
        m.put("timestamp", v.createdAt());
        return m;
    }

    private int intParam(Context ctx, String name, int defaultValue) {
        String val = ctx.queryParam(name);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
