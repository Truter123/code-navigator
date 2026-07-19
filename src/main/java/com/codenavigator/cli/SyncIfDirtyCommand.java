package com.codenavigator.cli;

import com.codenavigator.graph.GraphStore;
import com.codenavigator.indexer.ProjectIndexer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;

@Command(name = "sync-if-dirty", description = "Run incremental sync if the .dirty flag is present")
public class SyncIfDirtyCommand implements Runnable {

    @Parameters(index = "0", description = "Path to project root", defaultValue = "")
    private String projectPathArg;

    private Path projectPath;

    public void setProjectPath(Path projectPath) {
        this.projectPath = projectPath;
    }

    @Override
    public void run() {
        try {
            var root = ProjectPaths.resolveProjectPath(projectPath, projectPathArg);
            var dirtyFile = ProjectPaths.dirtyFile(root);
            if (!Files.exists(dirtyFile)) {
                return;
            }

            var dbPath = ProjectPaths.graphDb(root);
            try (var store = new GraphStore(dbPath)) {
                var indexer = new ProjectIndexer(store, com.codenavigator.embedding.EmbeddingProviders.fromEnv());
                indexer.indexIncremental(root);
            }

            Files.deleteIfExists(dirtyFile);
        } catch (Exception e) {
            System.err.println("sync-if-dirty failed: " + e.getMessage());
        }
    }
}
