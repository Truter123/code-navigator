package com.whatdid.store;

import com.whatdid.model.Repo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WhatDidStoreTest {

    @TempDir Path tempDir;
    WhatDidStore store;

    @BeforeEach
    void setUp() {
        store = new WhatDidStore(tempDir.resolve("test.db"));
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
    }

    @Test
    void shouldCreateSchemaOnInit() {
        assertThat(store).isNotNull();
    }

    @Test
    void shouldSaveAndFindRepo() {
        var repo = store.saveRepo("/home/user/project", "my-project");
        assertThat(repo.id()).isGreaterThan(0);
        assertThat(repo.name()).isEqualTo("my-project");

        var all = store.getAllRepos();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).path()).isEqualTo("/home/user/project");
    }

    @Test
    void shouldDeduplicateRepoByPath() {
        store.saveRepo("/home/user/project", "my-project");
        store.saveRepo("/home/user/project", "my-project");
        assertThat(store.getAllRepos()).hasSize(1);
    }

    @Test
    void shouldSaveAndFindCommit() {
        var repo = store.saveRepo("/home/user/project", "my-project");
        store.saveCommit(repo.id(), "abc123", "John", "fix bug", 3, 10, 5, Instant.parse("2026-03-29T10:00:00Z"));

        var commits = store.getCommitsByRepo(repo.id());
        assertThat(commits).hasSize(1);
        assertThat(commits.get(0).hash()).isEqualTo("abc123");
        assertThat(commits.get(0).message()).isEqualTo("fix bug");
    }

    @Test
    void shouldDeduplicateCommitByHash() {
        var repo = store.saveRepo("/home/user/project", "my-project");
        store.saveCommit(repo.id(), "abc123", "John", "fix bug", 3, 10, 5, Instant.now());
        store.saveCommit(repo.id(), "abc123", "John", "fix bug", 3, 10, 5, Instant.now());
        assertThat(store.getCommitsByRepo(repo.id())).hasSize(1);
    }

    @Test
    void shouldCreateSessionAndLinkCommits() {
        var repo = store.saveRepo("/path", "proj");
        store.saveCommit(repo.id(), "aaa", "A", "msg1", 1, 1, 0, Instant.now());
        store.saveCommit(repo.id(), "bbb", "A", "msg2", 2, 3, 1, Instant.now());

        var session = store.createSession("sprint work");
        store.linkUnsavedCommitsToSession(session.id());

        var commits = store.getCommitsBySession(session.id());
        assertThat(commits).hasSize(2);
    }

    @Test
    void shouldNotRelinkAlreadyLinkedCommits() {
        var repo = store.saveRepo("/path", "proj");
        store.saveCommit(repo.id(), "aaa", "A", "msg1", 1, 1, 0, Instant.now());

        var session1 = store.createSession("session 1");
        store.linkUnsavedCommitsToSession(session1.id());

        store.saveCommit(repo.id(), "bbb", "A", "msg2", 2, 3, 1, Instant.now());

        var session2 = store.createSession("session 2");
        store.linkUnsavedCommitsToSession(session2.id());

        assertThat(store.getCommitsBySession(session1.id())).hasSize(1);
        assertThat(store.getCommitsBySession(session2.id())).hasSize(1);
    }

    @Test
    void shouldSearchCommitMessages() {
        var repo = store.saveRepo("/path", "proj");
        store.saveCommit(repo.id(), "aaa", "A", "fix authentication bug", 1, 1, 0, Instant.now());
        store.saveCommit(repo.id(), "bbb", "A", "add new feature", 2, 3, 1, Instant.now());

        var results = store.searchCommits("authentication");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).hash()).isEqualTo("aaa");
    }

    @Test
    void shouldGetCommitsSince() {
        var repo = store.saveRepo("/path", "proj");
        store.saveCommit(repo.id(), "old", "A", "old commit", 1, 1, 0, Instant.parse("2026-03-01T00:00:00Z"));
        store.saveCommit(repo.id(), "new", "A", "new commit", 1, 1, 0, Instant.parse("2026-03-29T00:00:00Z"));

        var results = store.getCommitsSince(Instant.parse("2026-03-28T00:00:00Z"));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).hash()).isEqualTo("new");
    }

    @Test
    void shouldGetAllSessions() {
        store.createSession("s1");
        store.createSession("s2");
        assertThat(store.getAllSessions()).hasSize(2);
    }
}
