package com.agentmemory.store;

import com.agentmemory.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class MemoryStoreTest {

    @TempDir
    Path tempDir;

    MemoryStore store;

    @BeforeEach
    void setUp() {
        store = new MemoryStore(tempDir.resolve("test.db"));
    }

    @Test
    void storeAndRecallMemoryWithTags() {
        store.upsert("api-key", "sk-123", "claude", "myproject",
                List.of("config", "secret"), 0.8, false);

        Optional<Memory> result = store.recall("api-key", "claude", "myproject");

        assertThat(result).isPresent();
        Memory m = result.get();
        assertThat(m.key()).isEqualTo("api-key");
        assertThat(m.value()).isEqualTo("sk-123");
        assertThat(m.agent()).isEqualTo("claude");
        assertThat(m.project()).isEqualTo("myproject");
        assertThat(m.importance()).isEqualTo(0.8);
        assertThat(m.shared()).isFalse();
        assertThat(m.tags()).containsExactlyInAnyOrder("config", "secret");
        assertThat(m.id()).isNotNull();
        assertThat(m.createdAt()).isNotNull();
        assertThat(m.deletedAt()).isNull();
    }

    @Test
    void upsertCreatesVersionOnUpdate() {
        store.upsert("note", "first value", "claude", "proj",
                List.of(), 0.5, false);
        store.upsert("note", "second value", "claude", "proj",
                List.of(), 0.5, false);

        Optional<Memory> current = store.recall("note", "claude", "proj");
        assertThat(current).isPresent();
        assertThat(current.get().value()).isEqualTo("second value");

        List<MemoryVersion> versions = store.getVersions("note", "claude", "proj");
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).value()).isEqualTo("first value");
        assertThat(versions.get(0).version()).isEqualTo(1);
    }

    @Test
    void recallReturnsEmptyForMissingKey() {
        Optional<Memory> result = store.recall("nonexistent", "claude", "proj");
        assertThat(result).isEmpty();
    }

    @Test
    void softDeleteExcludesFromRecall() {
        store.upsert("temp", "data", "claude", "proj", List.of(), 0.5, false);

        assertThat(store.recall("temp", "claude", "proj")).isPresent();

        store.softDelete("temp", "claude", "proj");

        assertThat(store.recall("temp", "claude", "proj")).isEmpty();
    }

    @Test
    void listWithFilters() {
        store.upsert("k1", "v1", "claude", "projA", List.of("tag1"), 0.5, false);
        store.upsert("k2", "v2", "claude", "projA", List.of("tag2"), 0.5, true);
        store.upsert("k3", "v3", "claude", "projB", List.of("tag1"), 0.5, false);
        store.upsert("k4", "v4", "other", "projA", List.of("tag1"), 0.5, false);

        // filter by agent
        List<Memory> byAgent = store.list("claude", null, null, null, 100, 0);
        assertThat(byAgent).hasSize(3);

        // filter by agent + project
        List<Memory> byProject = store.list("claude", "projA", null, null, 100, 0);
        assertThat(byProject).hasSize(2);

        // filter by tags
        List<Memory> byTag = store.list("claude", null, List.of("tag1"), null, 100, 0);
        assertThat(byTag).hasSize(2);

        // filter by shared
        List<Memory> shared = store.list("claude", null, null, true, 100, 0);
        assertThat(shared).hasSize(1);
        assertThat(shared.get(0).key()).isEqualTo("k2");

        // pagination
        List<Memory> page = store.list("claude", null, null, null, 2, 0);
        assertThat(page).hasSize(2);
    }

    @Test
    void fts5Search() {
        store.upsert("readme", "This project uses Spring Boot for REST APIs",
                "claude", "proj", List.of("docs"), 0.5, false);
        store.upsert("config", "Database connection string for PostgreSQL",
                "claude", "proj", List.of("config"), 0.5, false);

        List<Memory> results = store.search("Spring Boot", "claude", "proj", null, 10);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).key()).isEqualTo("readme");

        List<Memory> dbResults = store.search("PostgreSQL", "claude", "proj", null, 10);
        assertThat(dbResults).hasSize(1);
        assertThat(dbResults.get(0).key()).isEqualTo("config");
    }

    @Test
    void shareMemory() {
        store.upsert("shared-note", "data", "claude", "proj", List.of(), 0.5, false);

        Optional<Memory> before = store.recall("shared-note", "claude", "proj");
        assertThat(before).isPresent();
        assertThat(before.get().shared()).isFalse();

        store.share("shared-note", "claude", "proj");

        Optional<Memory> after = store.recall("shared-note", "claude", "proj");
        assertThat(after).isPresent();
        assertThat(after.get().shared()).isTrue();
    }

    @Test
    void recallByKeyFindsAcrossAgentsAndProjects() {
        store.upsert("global-key", "v1", "agent1", "proj1", List.of(), 0.5, false);
        store.upsert("global-key", "v2", "agent2", "proj2", List.of(), 0.5, false);

        Optional<Memory> first = store.recallByKey("global-key");
        assertThat(first).isPresent();

        List<Memory> all = store.recallAllByKey("global-key");
        assertThat(all).hasSize(2);
    }

    @Test
    void recallByKeyAndAgent() {
        store.upsert("agent-key", "val", "claude", "proj", List.of(), 0.5, false);

        Optional<Memory> result = store.recallByKeyAndAgent("agent-key", "claude");
        assertThat(result).isPresent();
        assertThat(result.get().value()).isEqualTo("val");

        assertThat(store.recallByKeyAndAgent("agent-key", "other")).isEmpty();
    }

    @Test
    void softDeleteByKeyAndAgent() {
        store.upsert("multi", "v1", "claude", "proj1", List.of(), 0.5, false);
        store.upsert("multi", "v2", "claude", "proj2", List.of(), 0.5, false);

        store.softDeleteByKeyAndAgent("multi", "claude");

        assertThat(store.recall("multi", "claude", "proj1")).isEmpty();
        assertThat(store.recall("multi", "claude", "proj2")).isEmpty();
    }

    @Test
    void recallWithNullProject() {
        store.upsert("np", "val", "claude", null, List.of(), 0.5, false);

        Optional<Memory> result = store.recall("np", "claude", null);
        assertThat(result).isPresent();
        assertThat(result.get().value()).isEqualTo("val");
    }

    @Test
    void findById() {
        store.upsert("findme", "val", "claude", "proj", List.of("t"), 0.5, false);
        Optional<Memory> recalled = store.recall("findme", "claude", "proj");
        assertThat(recalled).isPresent();

        Optional<Memory> found = store.findById(recalled.get().id());
        assertThat(found).isPresent();
        assertThat(found.get().key()).isEqualTo("findme");

        assertThat(store.findById("nonexistent-id")).isEmpty();
    }

    @Test
    void goalsLifecycle() {
        store.setGoals("claude", List.of("Build API", "Write tests"), "proj");

        List<Goal> active = store.getGoals("claude", "active");
        assertThat(active).hasSize(2);

        // Setting new goals abandons old ones
        store.setGoals("claude", List.of("Deploy"), "proj");

        List<Goal> newActive = store.getGoals("claude", "active");
        assertThat(newActive).hasSize(1);
        assertThat(newActive.get(0).description()).isEqualTo("Deploy");

        List<Goal> abandoned = store.getGoals("claude", "abandoned");
        assertThat(abandoned).hasSize(2);
    }

    @Test
    void auditLogLifecycle() {
        store.logAudit("claude", "upsert", "key1", "created", 15.5);
        store.logAudit("claude", "recall", "key1", "found", 3.2);
        store.logAudit("other", "upsert", "key2", "created", 10.0);

        List<AuditEntry> all = store.getAuditLog(null, null, null, null, 100, 0);
        assertThat(all).hasSize(3);

        List<AuditEntry> byAgent = store.getAuditLog("claude", null, null, null, 100, 0);
        assertThat(byAgent).hasSize(2);

        List<AuditEntry> byOp = store.getAuditLog("claude", "upsert", null, null, 100, 0);
        assertThat(byOp).hasSize(1);
    }

    @Test
    void settingsLifecycle() {
        store.setSetting("theme", "dark");
        assertThat(store.getSetting("theme")).isEqualTo("dark");

        store.setSetting("theme", "light");
        assertThat(store.getSetting("theme")).isEqualTo("light");

        store.setSetting("lang", "en");
        Map<String, String> all = store.getAllSettings();
        assertThat(all).hasSize(2);
        assertThat(all).containsEntry("theme", "light");
        assertThat(all).containsEntry("lang", "en");

        assertThat(store.getSetting("missing")).isNull();
    }

    @Test
    void knownAgents() {
        store.logAudit("claude", "upsert", "k", "d", 1.0);
        store.logAudit("gemini", "upsert", "k", "d", 1.0);
        store.logAudit("claude", "recall", "k", "d", 1.0);

        List<String> agents = store.getKnownAgents();
        assertThat(agents).containsExactlyInAnyOrder("claude", "gemini");
    }
}
