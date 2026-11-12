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

        try (var lock = IndexLock.acquire(projectPath);
             var store = new GraphStore(ProjectPaths.graphDb(projectPath))) {
            var indexer = new ProjectIndexer(store);
            var result = indexer.indexIncremental(projectPath);
            if (result.line() != null) System.out.println(result.line());
            try {
                java.nio.file.Files.deleteIfExists(ProjectPaths.dirtyFile(projectPath));
            } catch (java.io.IOException e) {
                System.err.println("Could not clear the dirty flag: " + e.getMessage());
            }
            System.out.println("Sync complete.");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Could not lock the index for " + projectPath, e);
        }
    }
}
