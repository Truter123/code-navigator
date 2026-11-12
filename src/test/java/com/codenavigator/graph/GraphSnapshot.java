package com.codenavigator.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The comparable state of a graph: edge counts per type, the sorted multiset of
 * (source id, target id, type) triples, the sorted node ids and the sorted method rows.
 * Edge ids are random UUIDs and so are left out. {@link #diff} gives a readable message.
 */
public record GraphSnapshot(Map<String, Integer> edgeCounts, List<String> edgeTriples,
                            List<String> nodeIds, List<String> methodRows) {

    public static GraphSnapshot of(GraphStore store) {
        var counts = new TreeMap<String, Integer>();
        var triples = new ArrayList<String>();
        for (Edge e : store.getAllEdges()) {
            counts.merge(e.type().name(), 1, Integer::sum);
            triples.add(e.sourceId() + " -> " + e.targetId() + " [" + e.type() + "]");
        }
        triples.sort(null);
        var nodeIds = new ArrayList<String>();
        var methods = new ArrayList<String>();
        for (Node n : store.getAllNodes()) {
            nodeIds.add(n.id());
            for (var m : store.findMethodsByNodeId(n.id())) methods.add(m.toString());
        }
        nodeIds.sort(null);
        methods.sort(null);
        return new GraphSnapshot(counts, triples, nodeIds, methods);
    }

    /** Empty when equal; otherwise per-type count changes and the first differing rows of each list. */
    public String diff(GraphSnapshot other) {
        var sb = new StringBuilder();
        if (!edgeCounts.equals(other.edgeCounts)) {
            sb.append("edge counts differ: ").append(edgeCounts).append(" vs ").append(other.edgeCounts).append('\n');
        }
        diffList("edges", edgeTriples, other.edgeTriples, sb);
        diffList("nodes", nodeIds, other.nodeIds, sb);
        diffList("methods", methodRows, other.methodRows, sb);
        return sb.toString();
    }

    private static void diffList(String what, List<String> a, List<String> b, StringBuilder sb) {
        if (a.equals(b)) return;
        var onlyA = new ArrayList<>(a);
        for (String s : b) onlyA.remove(s);
        var onlyB = new ArrayList<>(b);
        for (String s : a) onlyB.remove(s);
        sb.append(what).append(" only in first (").append(onlyA.size()).append("): ")
          .append(onlyA.subList(0, Math.min(5, onlyA.size()))).append('\n');
        sb.append(what).append(" only in second (").append(onlyB.size()).append("): ")
          .append(onlyB.subList(0, Math.min(5, onlyB.size()))).append('\n');
    }
}
