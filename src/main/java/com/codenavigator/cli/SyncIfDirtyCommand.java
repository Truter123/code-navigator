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

            // Another run is indexing this project: leave the mark for the next hook and go.
            var held = IndexLock.tryAcquire(root);
            if (held.isEmpty()) {
                return;
            }
            try (var lock = held.get()) {
                // The run that held the lock a moment ago may have cleared the mark already.
                if (!Files.exists(dirtyFile)) {
                    return;
                }
                String mark = Files.readString(dirtyFile);

                var dbPath = ProjectPaths.graphDb(root);
                try (var store = new GraphStore(dbPath)) {
                    var indexer = new ProjectIndexer(store);
                    var result = indexer.indexIncremental(root);
                    if (result.line() != null) System.out.println(result.line());
                }

                // An edit during the run rewrote the mark; this run may have missed that file,
                // so the mark stays and the next hook syncs again.
                if (Files.exists(dirtyFile) && mark.equals(Files.readString(dirtyFile))) {
                    Files.deleteIfExists(dirtyFile);
                }
            }
        } catch (Exception e) {
            System.err.println("sync-if-dirty failed: " + e.getMessage());
        }
    }
}
