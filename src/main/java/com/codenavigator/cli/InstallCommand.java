package com.codenavigator.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Command(name = "install", description = "Configure MCP server, hooks, and CLAUDE.md for a project")
public class InstallCommand implements Runnable {

    @Option(names = "--project", description = "Project path (default: current dir)")
    private Path projectPath;

    @Option(names = "--jar", description = "Path to code-navigator JAR")
    private String jarPath;

    public void setProjectPath(Path p) { this.projectPath = p; }
    public void setJarPath(String p) { this.jarPath = p; }

    @Override
    public void run() {
        if (projectPath == null) projectPath = Path.of(".");
        if (jarPath == null) jarPath = "code-navigator.jar";
        try {
            writeMcpJson();
            writeClaudeMd();
            writeHooksConfig();
            System.out.println("Installed code-navigator for: " + projectPath.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Install failed: " + e.getMessage());
        }
    }

    private void writeMcpJson() throws IOException {
        var jarAbsPath = Path.of(jarPath).toAbsolutePath().toString();
        var projectAbsPath = projectPath.toAbsolutePath().toString();
        var json = """
                {
                  "mcpServers": {
                    "code-navigator": {
                      "command": "java",
                      "args": [
                        "-jar",
                        "%s",
                        "serve"
                      ],
                      "env": {
                        "CODE_NAVIGATOR_PROJECT": "%s"
                      }
                    }
                  }
                }
                """.formatted(jarAbsPath, projectAbsPath);
        Files.writeString(projectPath.resolve(".mcp.json"), json);
    }

    private void writeClaudeMd() throws IOException {
        var claudeMd = projectPath.resolve("CLAUDE.md");
        if (Files.exists(claudeMd)) {
            var existing = Files.readString(claudeMd);
            if (existing.contains("cg_context")) {
                return; // already installed
            }
            Files.writeString(claudeMd, "\n" + toolSection(), StandardOpenOption.APPEND);
        } else {
            Files.writeString(claudeMd, toolSection());
        }
    }

    private String toolSection() {
        return """
                ## Code Navigator Tools

                This project uses code-navigator MCP tools for navigating the codebase:

                - `cg_chain` — Trace full CQRS/DDD chain from any symbol
                - `cg_impact` — Change blast radius within N edges of a symbol
                - `cg_context` — Smart context builder for a task description
                - `cg_search` — Full-text search across all indexed symbols
                - `cg_overview` — Aggregate/service overview grouped by node type
                - `cg_map` — System-wide map of all aggregates/controllers
                - `cg_callers` — Find all callers of a symbol (who calls this?)
                - `cg_callees` — Find all callees of a symbol (what does this call?)
                - `cg_node` — Show detailed info about a single node
                - `cg_status` — Show graph status: tier, counts, breakdown
                - `cg_files` — List all indexed files with node counts
                """;
    }

    private void writeHooksConfig() throws IOException {
        var claudeDir = projectPath.resolve(".claude");
        if (!Files.isDirectory(claudeDir)) {
            return; // only write hooks if .claude directory exists
        }
        var jarAbsPath = Path.of(jarPath).toAbsolutePath().toString();
        var json = """
                {
                  "hooks": {
                    "PostToolUse": [
                      {
                        "matcher": "Edit|Write",
                        "hooks": [
                          {
                            "type": "command",
                            "command": "java -jar %s mark-dirty",
                            "async": true
                          }
                        ]
                      }
                    ],
                    "Stop": [
                      {
                        "matcher": "",
                        "hooks": [
                          {
                            "type": "command",
                            "command": "java -jar %s sync-if-dirty"
                          }
                        ]
                      }
                    ]
                  }
                }
                """.formatted(jarAbsPath, jarAbsPath);
        Files.writeString(claudeDir.resolve("settings.json"), json);
    }
}
