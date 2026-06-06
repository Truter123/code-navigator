package com.codenavigator.cli;

import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.NodeType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import java.nio.file.Path;

@Command(name = "status", description = "Show index statistics")
public class StatusCommand implements Runnable {

    @Parameters(index = "0", description = "Path to the project root")
    private Path projectPath;

    @Override
    public void run() {
        if (!ProjectPaths.hasIndex(projectPath)) {
            System.out.println("No index found. Run 'init' first.");
            return;
        }

        try (var store = new GraphStore(ProjectPaths.graphDb(projectPath))) {
            var tier = store.getConfig("tier");
            var totalNodes = store.getAllNodes().size();
            var totalEdges = store.getAllEdges().size();

            System.out.println("=== Code Navigator Status ===");
            System.out.println("Project: " + projectPath);
            System.out.println("Tier: " + (tier != null ? tier : "unknown"));
            System.out.printf("Total: %d nodes, %d edges%n%n", totalNodes, totalEdges);

            System.out.println("Nodes by type:");
            for (var type : NodeType.values()) {
                var count = store.findNodesByType(type).size();
                if (count > 0) {
                    System.out.printf("  %-25s %d%n", type, count);
                }
            }
        }
    }
}
