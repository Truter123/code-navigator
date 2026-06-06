package com.codenavigator.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class MarkDirtyCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void createsDirtyFlagFile() throws Exception {
        Files.createDirectories(tempDir.resolve("navigators/code"));

        var cmd = new MarkDirtyCommand();
        cmd.setProjectPath(tempDir);
        cmd.run();

        assertThat(tempDir.resolve("navigators/code/.dirty")).exists();
    }

    @Test
    void noOpWhenNoCodeNavigatorDir() {
        var cmd = new MarkDirtyCommand();
        cmd.setProjectPath(tempDir);

        assertThatNoException().isThrownBy(cmd::run);
        assertThat(tempDir.resolve("navigators/code/.dirty")).doesNotExist();
    }
}
