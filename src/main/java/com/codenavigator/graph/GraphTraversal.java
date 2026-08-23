package com.codenavigator.graph;

import java.util.*;

public class GraphTraversal {

    private static final Set<EdgeType> CALL_EDGE_TYPES = Set.of(
        EdgeType.CALLS_METHOD, EdgeType.INJECTS, EdgeType.CALLS_API, EdgeType.USES_SERVICE,
        EdgeType.DISPATCHES_COMMAND, EdgeType.DISPATCHES_QUERY, EdgeType.USES_LIBRARY,
        EdgeType.CALLS, EdgeType.DECLARES_METHOD, EdgeType.OVERRIDES
    );

    /**
     * A class and the methods it declares are the same place, so crossing DECLARES_METHOD costs no
     * depth. Without this, {@code depth 2} from a class would spend one hop reaching its own
     * methods and one reaching their callees, and never arrive at the calling class — every
     * class-level answer would collapse to the class's own members.
     */
    private static boolean isFreeHop(EdgeType type) {
        return type == EdgeType.DECLARES_METHOD;
    }

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
        return search(startId, maxDepth, incoming ? Direction.INCOMING : Direction.OUTGOING,
            CALL_EDGE_TYPES);
    }

    private enum Direction { INCOMING, OUTGOING, BOTH }

    /**
     * 0-1 BFS over the graph. Free hops (DECLARES_METHOD) are pushed to the front of the deque and
     * costly ones to the back, so a node is always settled at its true minimum depth — a plain
     * queue would settle a method at the depth of whichever path happened to reach it first.
     *
     * @param allowedTypes edge types to follow, or null to follow every edge
     */
    private List<Node> search(String startId, int maxDepth, Direction direction,
                              Set<EdgeType> allowedTypes) {
        List<Node> result = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        Map<String, Integer> depth = new LinkedHashMap<>();
        Deque<String> deque = new ArrayDeque<>();

        depth.put(startId, 0);
        deque.add(startId);

        while (!deque.isEmpty()) {
            String currentId = deque.pollFirst();
            int currentDepth = depth.get(currentId);

            // Both directions are gathered every time; a free hop is containment rather than a
            // call, so it is crossed whichever way it points even on a directional query. Reaching
            // B#b() as a callee is only useful if the answer can then name B.
            List<Edge> edges = new ArrayList<>(store.findEdgesTo(currentId));
            edges.addAll(store.findEdgesFrom(currentId));

            for (Edge edge : edges) {
                if (allowedTypes != null && !allowedTypes.contains(edge.type())) continue;

                boolean free = isFreeHop(edge.type());
                boolean outgoing = edge.sourceId().equals(currentId);
                String neighborId = outgoing ? edge.targetId() : edge.sourceId();
                if (neighborId.equals(currentId)) continue;          // self-loop

                if (!free) {
                    if (direction == Direction.OUTGOING && !outgoing) continue;
                    if (direction == Direction.INCOMING && outgoing) continue;
                }

                // Checked per edge rather than at poll time, so a node sitting exactly at the
                // depth limit can still be rolled up to its declaring class for free.
                int neighborDepth = free ? currentDepth : currentDepth + 1;
                if (neighborDepth > maxDepth) continue;

                Integer known = depth.get(neighborId);
                if (known != null && known <= neighborDepth) continue;

                depth.put(neighborId, neighborDepth);
                if (free) deque.addFirst(neighborId); else deque.addLast(neighborId);

                if (!neighborId.equals(startId) && emitted.add(neighborId)) {
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
        return search(nodeId, depth, Direction.BOTH, null);
    }
}
