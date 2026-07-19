package com.codenavigator.cli;

import com.codenavigator.graph.GraphStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class SyncIfDirtyCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void syncsAndRemovesDirtyFlag() throws Exception {
        var codeGraphDir = tempDir.resolve("navigators/code");
        Files.createDirectories(codeGraphDir);

        // Create a valid code-navigator.db via GraphStore
        try (var store = new GraphStore(codeGraphDir.resolve("code-navigator.db"))) {
            store.setConfig("tier", "GENERIC");
        }

        // Create dirty flag
        Files.writeString(codeGraphDir.resolve(".dirty"), "dirty");

        var cmd = new SyncIfDirtyCommand();
        cmd.setProjectPath(tempDir);
        cmd.run();

        assertThat(codeGraphDir.resolve(".dirty")).doesNotExist();
    }

    @Test
    void noOpWhenNotDirty() throws Exception {
        var codeGraphDir = tempDir.resolve("navigators/code");
        Files.createDirectories(codeGraphDir);

        // Create a valid code-navigator.db via GraphStore
        try (var store = new GraphStore(codeGraphDir.resolve("code-navigator.db"))) {
            store.setConfig("tier", "GENERIC");
        }

        var cmd = new SyncIfDirtyCommand();
        cmd.setProjectPath(tempDir);

        assertThatNoException().isThrownBy(cmd::run);
    }
}
