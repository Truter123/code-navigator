package com.codenavigator.export;

import com.codenavigator.graph.*;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

public class ExportService {

    public String toJson(List<Node> nodes, List<Edge> edges, String tier) {
        var sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"tier\":\"").append(tier != null ? tier : "unknown").append("\",\n");
        sb.append("  \"generated\":\"").append(LocalDate.now()).append("\",\n");
        sb.append("  \"nodes\":[\n");
        for (int i = 0; i < nodes.size(); i++) {
            var n = nodes.get(i);
            sb.append("    {\"id\":\"").append(n.id())
              .append("\",\"type\":\"").append(n.type())
              .append("\",\"name\":\"").append(n.name())
              .append("\",\"filePath\":\"").append(n.filePath())
              .append("\",\"lineNumber\":").append(n.lineNumber())
              .append("}");
            if (i < nodes.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n");
        sb.append("  \"edges\":[\n");
        for (int i = 0; i < edges.size(); i++) {
            var e = edges.get(i);
            sb.append("    {\"type\":\"").append(e.type())
              .append("\",\"source\":\"").append(e.sourceId())
              .append("\",\"target\":\"").append(e.targetId())
              .append("\"}");
            if (i < edges.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    public String toMermaid(List<Node> nodes, List<Edge> edges) {
        var sb = new StringBuilder();
        sb.append("graph LR\n");

        var byPackage = nodes.stream().collect(Collectors.groupingBy(
            n -> extractPackage(n.qualifiedName()), LinkedHashMap::new, Collectors.toList()));

        Map<String, String> idToName = nodes.stream().collect(Collectors.toMap(Node::id, Node::name));

        for (var entry : byPackage.entrySet()) {
            sb.append("  subgraph ").append(entry.getKey()).append("\n");
            for (var node : entry.getValue()) {
                String safeId = mermaidId(node.name());
                sb.append("    ").append(safeId).append("[").append(node.name())
                  .append("<br/>").append(node.type()).append("]\n");
            }
            sb.append("  end\n");
        }
        sb.append("\n");

        for (var edge : edges) {
            String src = idToName.get(edge.sourceId());
            String tgt = idToName.get(edge.targetId());
            if (src == null || tgt == null) continue;
            sb.append("  ").append(mermaidId(src)).append(" -->|").append(edge.type())
              .append("| ").append(mermaidId(tgt)).append("\n");
        }

        sb.append("\n");
        sb.append("  classDef controller fill:#4A90D9,color:#fff\n");
        sb.append("  classDef command fill:#E8A838,color:#fff\n");
        sb.append("  classDef aggregate fill:#D94A4A,color:#fff\n");
        sb.append("  classDef event fill:#4AD94A,color:#fff\n");
        sb.append("  classDef projection fill:#9B59B6,color:#fff\n");

        Map<NodeType, String> styleMap = Map.of(
            NodeType.CONTROLLER, "controller",
            NodeType.COMMAND, "command", NodeType.QUERY, "command",
            NodeType.AGGREGATE, "aggregate",
            NodeType.DOMAIN_EVENT, "event",
            NodeType.PROJECTION_HANDLER, "projection"
        );

        for (var node : nodes) {
            String style = styleMap.get(node.type());
            if (style != null) {
                sb.append("  class ").append(mermaidId(node.name())).append(" ").append(style).append("\n");
            }
        }

        return sb.toString();
    }

    public String toPlantUml(List<Node> nodes, List<Edge> edges) {
        var sb = new StringBuilder();
        sb.append("@startuml\n");

        var byPackage = nodes.stream().collect(Collectors.groupingBy(
            n -> extractPackage(n.qualifiedName()), LinkedHashMap::new, Collectors.toList()));

        Map<String, String> idToName = nodes.stream().collect(Collectors.toMap(Node::id, Node::name));

        for (var entry : byPackage.entrySet()) {
            sb.append("package \"").append(entry.getKey()).append("\" {\n");
            for (var node : entry.getValue()) {
                sb.append("  [").append(node.name()).append("] <<").append(node.type()).append(">>\n");
            }
            sb.append("}\n\n");
        }

        for (var edge : edges) {
            String src = idToName.get(edge.sourceId());
            String tgt = idToName.get(edge.targetId());
            if (src == null || tgt == null) continue;
            sb.append("[").append(src).append("] --> [").append(tgt).append("] : ").append(edge.type()).append("\n");
        }

        sb.append("@enduml\n");
        return sb.toString();
    }

    private static String extractPackage(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot > 0 ? qualifiedName.substring(0, lastDot) : qualifiedName;
    }

    private static String mermaidId(String name) {
        return name.replaceAll("[^a-zA-Z0-9]", "_");
    }
}
