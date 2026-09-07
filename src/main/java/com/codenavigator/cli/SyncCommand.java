package com.codenavigator.cli;

import com.codenavigator.graph.GraphStore;
import com.codenavigator.indexer.ProjectIndexer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import java.nio.file.Path;

@Command(name = "sync", description = "Incremental re-index of changed files")
public class SyncCommand implements Runnable {

    @Parameters(index = "0", description = "Path to the project root")
    private Path projectPath;

    @Override
    public void run() {
        if (!ProjectPaths.hasIndex(projectPath)) {
            System.err.println("No index found. Run 'init' first.");
            return;
        }

        try (var store = new GraphStore(ProjectPaths.graphDb(projectPath))) {
            var indexer = new ProjectIndexer(store);
            indexer.indexIncremental(projectPath);
            System.out.println("Sync complete.");
        }
    }
}
