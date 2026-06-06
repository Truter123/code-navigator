package com.codenavigator.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InstallCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void writesMcpJson() throws Exception {
        var cmd = new InstallCommand();
        cmd.setProjectPath(tempDir);
        cmd.setJarPath("/path/to/code-navigator.jar");
        cmd.run();

        var mcpJson = tempDir.resolve(".mcp.json");
        assertThat(mcpJson).exists();
        var content = Files.readString(mcpJson);
        assertThat(content).contains("code-navigator");
        assertThat(content).contains("serve");
    }

    @Test
    void writesClaudeMd() throws Exception {
        var cmd = new InstallCommand();
        cmd.setProjectPath(tempDir);
        cmd.setJarPath("/path/to/code-navigator.jar");
        cmd.run();

        var claudeMd = tempDir.resolve("CLAUDE.md");
        assertThat(claudeMd).exists();
        var content = Files.readString(claudeMd);
        assertThat(content).contains("cg_");
    }

    @Test
    void writesHooksConfig() throws Exception {
        Files.createDirectory(tempDir.resolve(".claude"));

        var cmd = new InstallCommand();
        cmd.setProjectPath(tempDir);
        cmd.setJarPath("/path/to/code-navigator.jar");
        cmd.run();

        var settings = tempDir.resolve(".claude/settings.json");
        assertThat(settings).exists();
        var content = Files.readString(settings);
        assertThat(content).contains("mark-dirty");
        assertThat(content).contains("sync-if-dirty");
        assertThat(content).contains("PostToolUse");
        assertThat(content).contains("Stop");
    }
}
