package com.codenavigator.cli;

import com.codenavigator.export.ExportService;
import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.GraphTraversal;
import com.codenavigator.graph.Node;
import com.codenavigator.graph.Edge;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Command(name = "export", description = "Export graph as JSON, Mermaid, or PlantUML")
public class ExportCommand implements Runnable {

    @Parameters(index = "0", description = "Path to the project root")
    private Path projectPath;

    @Option(names = "--format", required = true, description = "Output format: json, mermaid, plantuml")
    private String format;

    @Option(names = "--symbol", description = "Scope export to a symbol's chain")
    private String symbol;

    @Option(names = "--output", description = "Output file path (default: stdout)")
    private Path output;

    @Override
    public void run() {
        try (var store = new GraphStore(ProjectPaths.graphDb(projectPath))) {
            var traversal = new GraphTraversal(store);
            var exportService = new ExportService();

            List<Node> nodes;
            List<Edge> edges;

            if (symbol != null) {
                var nodeId = store.findNodesByName(symbol).stream().findFirst()
                    .map(Node::id).orElse(null);
                if (nodeId == null) {
                    System.err.println("Symbol not found: " + symbol);
                    return;
                }
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
            String result = switch (format.toLowerCase()) {
                case "json" -> exportService.toJson(nodes, edges, tier);
                case "mermaid" -> exportService.toMermaid(nodes, edges);
                case "plantuml" -> exportService.toPlantUml(nodes, edges);
                default -> { System.err.println("Unknown format: " + format); yield ""; }
            };

            if (output != null) {
                Files.writeString(output, result);
                System.out.println("Exported to " + output);
            } else {
                System.out.println(result);
            }
        } catch (IOException e) {
            System.err.println("Export failed: " + e.getMessage());
        }
    }
}
