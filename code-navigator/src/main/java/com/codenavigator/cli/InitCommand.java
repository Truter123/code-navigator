package com.codenavigator.cli;

import com.codenavigator.graph.GraphStore;
import com.codenavigator.indexer.ProjectIndexer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import java.nio.file.Path;

@Command(name = "init", description = "Full index of a project")
public class InitCommand implements Runnable {

    @Parameters(index = "0", description = "Path to the project root")
    private Path projectPath;

    @Override
    public void run() {
        var dbPath = ProjectPaths.graphDb(projectPath);
        dbPath.getParent().toFile().mkdirs();

        System.out.println("Indexing project: " + projectPath);
        try (var store = new GraphStore(dbPath)) {
            var indexer = new ProjectIndexer(store);
            indexer.indexFull(projectPath);

            var nodeCount = store.getAllNodes().size();
            var edgeCount = store.getAllEdges().size();
            var tier = store.getConfig("tier");
            System.out.printf("Done! Tier: %s, %d nodes, %d edges indexed.%n", tier, nodeCount, edgeCount);
        }
    }
}
