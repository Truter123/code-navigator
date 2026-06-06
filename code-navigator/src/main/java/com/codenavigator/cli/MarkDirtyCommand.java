package com.codenavigator.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

@Command(name = "mark-dirty", description = "Write a .dirty flag file to signal that the graph needs re-sync")
public class MarkDirtyCommand implements Runnable {

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
            var codeGraphDir = ProjectPaths.codeGraphDir(root);
            if (!Files.isDirectory(codeGraphDir)) {
                return;
            }
            Files.writeString(ProjectPaths.dirtyFile(root), Instant.now().toString());
        } catch (Exception e) {
            // Silent on errors — this runs as an async Claude Code hook
        }
    }
}
