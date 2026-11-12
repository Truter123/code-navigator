package com.codenavigator.cli;

import com.codenavigator.graph.GraphStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IndexLockTest {

    @TempDir
    Path tempDir;

    @Test
    void secondRunIsRefusedWhileTheFirstHoldsTheProject() throws Exception {
        try (var first = IndexLock.acquire(tempDir)) {
            assertThat(IndexLock.tryAcquire(tempDir)).isEmpty();
        }
    }

    @Test
    void projectIsFreeAgainAfterTheRunEnds() throws Exception {
        IndexLock.acquire(tempDir).close();

        var second = IndexLock.tryAcquire(tempDir);
        assertThat(second).isPresent();
        second.get().close();
    }

    @Test
    void syncIfDirtySkipsAndKeepsTheMarkWhileAnotherRunIsIndexing() throws Exception {
        var codeGraphDir = tempDir.resolve("navigators/code");
        Files.createDirectories(codeGraphDir);
        try (var store = new GraphStore(codeGraphDir.resolve("code-navigator.db"))) {
            store.setConfig("tier", "GENERIC");
        }
        var dirty = codeGraphDir.resolve(".dirty");
        Files.writeString(dirty, "dirty");

        var cmd = new SyncIfDirtyCommand();
        cmd.setProjectPath(tempDir);

        try (var running = IndexLock.acquire(tempDir)) {
            cmd.run();
            assertThat(dirty).exists();
        }

        cmd.run();
        assertThat(dirty).doesNotExist();
    }
}
