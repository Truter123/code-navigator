package com.whatdid.scanner;

import com.whatdid.model.Repo;
import com.whatdid.store.WhatDidStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GitScannerTest {

    @TempDir Path tempDir;
    WhatDidStore store;
    GitScanner scanner;

    @BeforeEach
    void setUp() {
        store = new WhatDidStore(tempDir.resolve("test.db"));
        scanner = new GitScanner(store);
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
    }

    @Test
    void shouldParseGitLogLine() {
        var line = "abc1234|John Doe|2026-03-29T10:00:00+00:00|fix authentication bug|3|10|5";
        var parsed = GitScanner.parseLogLine(line);

        assertThat(parsed).isNotNull();
        assertThat(parsed.hash()).isEqualTo("abc1234");
        assertThat(parsed.author()).isEqualTo("John Doe");
        assertThat(parsed.message()).isEqualTo("fix authentication bug");
        assertThat(parsed.filesChanged()).isEqualTo(3);
        assertThat(parsed.insertions()).isEqualTo(10);
        assertThat(parsed.deletions()).isEqualTo(5);
    }

    @Test
    void shouldHandleMalformedLine() {
        var parsed = GitScanner.parseLogLine("not a valid line");
        assertThat(parsed).isNull();
    }

    @Test
    void shouldHandleMessageWithPipes() {
        var line = "abc1234|John|2026-03-29T10:00:00+00:00|fix: handle a|b case|3|10|5";
        var parsed = GitScanner.parseLogLine(line);
        assertThat(parsed).isNotNull();
        assertThat(parsed.message()).isEqualTo("fix: handle a|b case");
    }

    @Test
    void shouldScanRealGitRepo() throws Exception {
        var repoDir = tempDir.resolve("test-repo");
        runGit(repoDir, "init");
        runGit(repoDir, "config", "user.email", "test@test.com");
        runGit(repoDir, "config", "user.name", "Test");
        java.nio.file.Files.writeString(repoDir.resolve("file.txt"), "hello");
        runGit(repoDir, "add", ".");
        runGit(repoDir, "commit", "-m", "initial commit");

        var repo = store.saveRepo(repoDir.toString(), "test-repo");
        int count = scanner.scan(repo);

        assertThat(count).isEqualTo(1);
        var commits = store.getCommitsByRepo(repo.id());
        assertThat(commits).hasSize(1);
        assertThat(commits.get(0).message()).isEqualTo("initial commit");
    }

    private void runGit(Path dir, String... args) throws Exception {
        java.nio.file.Files.createDirectories(dir);
        var cmd = new java.util.ArrayList<String>();
        cmd.add("git");
        cmd.addAll(java.util.List.of(args));
        new ProcessBuilder(cmd).directory(dir.toFile())
            .redirectErrorStream(true).start().waitFor();
    }
}
