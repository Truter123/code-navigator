package com.codenavigator.graph;

import java.util.*;

public class GraphTraversal {

    private static final Set<EdgeType> CALL_EDGE_TYPES = Set.of(
        EdgeType.CALLS_METHOD, EdgeType.INJECTS, EdgeType.CALLS_API, EdgeType.USES_SERVICE,
        EdgeType.DISPATCHES_COMMAND, EdgeType.DISPATCHES_QUERY, EdgeType.USES_LIBRARY
    );

    private final GraphStore store;

    public GraphTraversal(GraphStore store) {
        this.store = store;
    }

    /**
     * Trace the full chain from any symbol, following edges in both directions.
     * Uses BFS. Returns all connected nodes including start node. No duplicates.
     */
    public List<Node> traceChain(String startNodeId) {
        List<Node> result = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        Queue<String> queue = new ArrayDeque<>();

        queue.add(startNodeId);
        visited.add(startNodeId);

        while (!queue.isEmpty()) {
            String currentId = queue.poll();
            store.findNodeById(currentId).ifPresent(result::add);

            for (Edge edge : store.findEdgesFrom(currentId)) {
                if (visited.add(edge.targetId())) {
                    queue.add(edge.targetId());
                }
            }

            for (Edge edge : store.findEdgesTo(currentId)) {
                if (visited.add(edge.sourceId())) {
                    queue.add(edge.sourceId());
                }
            }
        }

        return result;
    }

    /**
     * Find all nodes that call the given node (directly or transitively), following
     * only call-type edges (CALLS_METHOD, INJECTS, CALLS_API, USES_SERVICE) in the
     * incoming direction.
     */
    public List<Node> callers(String nodeId, int maxDepth) {
        return traverseDirectional(nodeId, maxDepth, true);
    }

    /**
     * Find all nodes that the given node calls (directly or transitively), following
     * only call-type edges (CALLS_METHOD, INJECTS, CALLS_API, USES_SERVICE) in the
     * outgoing direction.
     */
    public List<Node> callees(String nodeId, int maxDepth) {
        return traverseDirectional(nodeId, maxDepth, false);
    }

    private List<Node> traverseDirectional(String startId, int maxDepth, boolean incoming) {
        List<Node> result = new ArrayList<>();
        Map<String, Integer> visited = new LinkedHashMap<>();
        Queue<String> queue = new ArrayDeque<>();

        visited.put(startId, 0);
        queue.add(startId);

        while (!queue.isEmpty()) {
            String currentId = queue.poll();
            int currentDepth = visited.get(currentId);

            if (currentDepth >= maxDepth) {
                continue;
            }

            List<Edge> edges;
            if (incoming) {
                edges = store.findEdgesTo(currentId);
            } else {
                edges = store.findEdgesFrom(currentId);
            }

            for (Edge edge : edges) {
                if (!CALL_EDGE_TYPES.contains(edge.type())) {
                    continue;
                }
                String neighborId = incoming ? edge.sourceId() : edge.targetId();
                if (!visited.containsKey(neighborId)) {
                    visited.put(neighborId, currentDepth + 1);
                    queue.add(neighborId);
                    store.findNodeById(neighborId).ifPresent(result::add);
                }
            }
        }

        return result;
    }

    /**
     * Find all nodes within `depth` edges of the given node (both directions).
     * Excludes the start node itself.
     */
    public List<Node> impact(String nodeId, int depth) {
        List<Node> result = new ArrayList<>();
        Map<String, Integer> visited = new LinkedHashMap<>();
        Queue<String> queue = new ArrayDeque<>();

        visited.put(nodeId, 0);
        queue.add(nodeId);

        while (!queue.isEmpty()) {
            String currentId = queue.poll();
            int currentDepth = visited.get(currentId);

            if (currentDepth >= depth) {
                continue;
            }

            for (Edge edge : store.findEdgesFrom(currentId)) {
                if (!visited.containsKey(edge.targetId())) {
                    visited.put(edge.targetId(), currentDepth + 1);
                    queue.add(edge.targetId());
                    store.findNodeById(edge.targetId()).ifPresent(result::add);
                }
            }

            for (Edge edge : store.findEdgesTo(currentId)) {
                if (!visited.containsKey(edge.sourceId())) {
                    visited.put(edge.sourceId(), currentDepth + 1);
                    queue.add(edge.sourceId());
                    store.findNodeById(edge.sourceId()).ifPresent(result::add);
                }
            }
        }

        return result;
    }
}
