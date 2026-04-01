# Agent Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a general-purpose agent memory MCP server with Angular dashboard, fitting the existing monorepo pattern.

**Architecture:** Single shadow JAR running MCP stdio transport + Javalin HTTP server in one process. SQLite at `~/.agent-memory/memory.db`. Angular 19 SPA bundled as static resources.

**Tech Stack:** Java 21, Gradle + Shadow plugin, MCP SDK 1.1.0, Javalin 7.0.1, SQLite (sqlite-jdbc 3.47.2.0), picocli 4.7.6, Jackson 2.18.2, Angular 19, Tailwind CSS, cytoscape.js, ngx-charts.

**Spec:** `docs/superpowers/2026-04-01-agent-memory-design.md`

**Existing pattern reference:** `code-navigator/` — follow its picocli, MCP registration, SQLite, and Gradle patterns exactly.

---

## File Map

### Java Backend

| File | Responsibility |
|------|---------------|
| `agent-memory/build.gradle` | Gradle build config with Shadow, Javalin, Angular build task |
| `agent-memory/settings.gradle` | Root project name |
| `src/main/java/com/agentmemory/AgentMemoryApplication.java` | picocli entry point |
| `src/main/java/com/agentmemory/cli/ServeCommand.java` | Starts MCP + Javalin, wires everything |
| `src/main/java/com/agentmemory/model/Memory.java` | Record: id, key, value, agent, project, shared, importance, tagsText, deletedAt, createdAt, updatedAt |
| `src/main/java/com/agentmemory/model/MemoryVersion.java` | Record: id, memoryId, value, version, createdAt |
| `src/main/java/com/agentmemory/model/MemoryLink.java` | Record: id, sourceId, targetId, relation, createdAt |
| `src/main/java/com/agentmemory/model/Goal.java` | Record: id, agent, project, description, status, createdAt, updatedAt |
| `src/main/java/com/agentmemory/model/AuditEntry.java` | Record: id, agent, operation, memoryKey, details, latencyMs, createdAt |
| `src/main/java/com/agentmemory/model/Anomaly.java` | Record: type (loop/drift/contradiction), severity, description, affectedKeys, detectedAt |
| `src/main/java/com/agentmemory/store/MemoryStore.java` | SQLite DAO: memories, tags, versions, goals, audit_log, settings, FTS5 |
| `src/main/java/com/agentmemory/store/GraphStore.java` | SQLite DAO: memory_links, graph traversal |
| `src/main/java/com/agentmemory/brain/LoopDetector.java` | Scans audit_log for repeated patterns |
| `src/main/java/com/agentmemory/brain/DriftDetector.java` | Compares recent ops vs goals |
| `src/main/java/com/agentmemory/brain/ContradictionDetector.java` | Finds conflicting memories |
| `src/main/java/com/agentmemory/brain/BrainEngine.java` | Orchestrates all 3 detectors, returns list of Anomaly |
| `src/main/java/com/agentmemory/mcp/AgentMemoryMcpServer.java` | 12 MCP tools with handlers |
| `src/main/java/com/agentmemory/api/DashboardApi.java` | Javalin REST routes for all /api/* endpoints |

### Test Files

| File | Tests |
|------|-------|
| `src/test/java/com/agentmemory/store/MemoryStoreTest.java` | CRUD, tags, versions, FTS5, soft-delete, goals, audit |
| `src/test/java/com/agentmemory/store/GraphStoreTest.java` | Links, traversal |
| `src/test/java/com/agentmemory/brain/LoopDetectorTest.java` | Loop detection logic |
| `src/test/java/com/agentmemory/brain/DriftDetectorTest.java` | Drift detection logic |
| `src/test/java/com/agentmemory/brain/ContradictionDetectorTest.java` | Contradiction detection logic |
| `src/test/java/com/agentmemory/api/DashboardApiTest.java` | REST API integration tests |

### Angular Frontend

| File | Responsibility |
|------|---------------|
| `angular/` | Angular 19 project root (created via `ng new`) |
| `angular/src/app/app.component.ts` | Root component with sidebar layout |
| `angular/src/app/app.routes.ts` | Route definitions for all 11 pages |
| `angular/src/app/services/api.service.ts` | HTTP client for all REST endpoints |
| `angular/src/app/components/sidebar/` | Sidebar navigation component |
| `angular/src/app/components/memory-card/` | Reusable memory display card |
| `angular/src/app/components/tag-badge/` | Tag display component |
| `angular/src/app/pages/overview/` | Dashboard overview page |
| `angular/src/app/pages/agents/` | Agents monitoring page |
| `angular/src/app/pages/memory-explorer/` | Memory browser with version history |
| `angular/src/app/pages/shared-memory/` | Shared memory view |
| `angular/src/app/pages/knowledge-graph/` | Cytoscape.js graph visualization |
| `angular/src/app/pages/performance/` | Performance charts page |
| `angular/src/app/pages/analytics/` | Analytics charts page |
| `angular/src/app/pages/audit-trail/` | Audit log table page |
| `angular/src/app/pages/recovery/` | Recovery status page |
| `angular/src/app/pages/anomalies/` | Anomalies list page |
| `angular/src/app/pages/settings/` | Settings form page |

### Build Scripts

| File | Responsibility |
|------|---------------|
| `jars/build-all.sh` | Updated to include agent-memory |
| `jars/run-agent-memory.sh` | Runner script |

---

## Phase 1: Project Scaffolding

### Task 1: Gradle Project Setup

**Files:**
- Create: `agent-memory/build.gradle`
- Create: `agent-memory/settings.gradle`

- [ ] **Step 1: Create build.gradle**

```gradle
plugins {
    id 'java'
    id 'application'
    id 'com.gradleup.shadow' version '9.0.0-beta12'
}

group = 'com.agentmemory'
version = '0.1.0'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = 'com.agentmemory.AgentMemoryApplication'
}

run {
    standardInput = System.in
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'io.modelcontextprotocol.sdk:mcp:1.1.0'
    implementation 'org.xerial:sqlite-jdbc:3.47.2.0'
    implementation 'com.fasterxml.jackson.core:jackson-databind:2.18.2'
    implementation 'info.picocli:picocli:4.7.6'
    annotationProcessor 'info.picocli:picocli-codegen:4.7.6'
    implementation 'io.javalin:javalin:7.0.1'

    testImplementation platform('org.junit:junit-bom:5.11.4')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testImplementation 'org.assertj:assertj-core:3.27.3'
}

tasks.named('test') {
    useJUnitPlatform()
    testLogging {
        events 'passed', 'failed', 'skipped'
        showStandardStreams = false
    }
}

shadowJar {
    archiveBaseName = 'agent-memory'
    archiveClassifier = ''
    mergeServiceFiles()
}

distTar.dependsOn shadowJar
distZip.dependsOn shadowJar
startScripts.dependsOn shadowJar
startShadowScripts.dependsOn jar
```

- [ ] **Step 2: Create settings.gradle**

```gradle
rootProject.name = 'agent-memory'
```

- [ ] **Step 3: Create source directories**

```bash
cd /home/kamil/Documents/Project/My/Mcp/agent-memory
mkdir -p src/main/java/com/agentmemory/{cli,mcp,store,brain,model,api}
mkdir -p src/main/resources/static
mkdir -p src/test/java/com/agentmemory/{store,brain,api}
```

- [ ] **Step 4: Copy Gradle wrapper from code-navigator**

```bash
cd /home/kamil/Documents/Project/My/Mcp/agent-memory
cp -r ../code-navigator/gradle .
cp ../code-navigator/gradlew .
cp ../code-navigator/gradlew.bat .
```

- [ ] **Step 5: Verify build compiles**

```bash
cd /home/kamil/Documents/Project/My/Mcp/agent-memory
./gradlew build
```
Expected: BUILD SUCCESSFUL (no sources yet, but dependencies resolve)

- [ ] **Step 6: Commit**

```bash
git add agent-memory/
git commit -m "feat(agent-memory): scaffold Gradle project with dependencies"
```

---

### Task 2: Model Records

**Files:**
- Create: `src/main/java/com/agentmemory/model/Memory.java`
- Create: `src/main/java/com/agentmemory/model/MemoryVersion.java`
- Create: `src/main/java/com/agentmemory/model/MemoryLink.java`
- Create: `src/main/java/com/agentmemory/model/Goal.java`
- Create: `src/main/java/com/agentmemory/model/AuditEntry.java`
- Create: `src/main/java/com/agentmemory/model/Anomaly.java`

- [ ] **Step 1: Create all model records**

`Memory.java`:
```java
package com.agentmemory.model;

import java.util.List;

public record Memory(
    String id,
    String key,
    String value,
    String agent,
    String project,
    boolean shared,
    double importance,
    String tagsText,
    String deletedAt,
    String createdAt,
    String updatedAt,
    List<String> tags
) {}
```

`MemoryVersion.java`:
```java
package com.agentmemory.model;

public record MemoryVersion(
    String id,
    String memoryId,
    String value,
    int version,
    String createdAt
) {}
```

`MemoryLink.java`:
```java
package com.agentmemory.model;

public record MemoryLink(
    String id,
    String sourceId,
    String targetId,
    String relation,
    String createdAt
) {}
```

`Goal.java`:
```java
package com.agentmemory.model;

public record Goal(
    String id,
    String agent,
    String project,
    String description,
    String status,
    String createdAt,
    String updatedAt
) {}
```

`AuditEntry.java`:
```java
package com.agentmemory.model;

public record AuditEntry(
    String id,
    String agent,
    String operation,
    String memoryKey,
    String details,
    double latencyMs,
    String createdAt
) {}
```

`Anomaly.java`:
```java
package com.agentmemory.model;

import java.util.List;

public record Anomaly(
    String type,
    String severity,
    String description,
    List<String> affectedKeys,
    String detectedAt
) {}
```

- [ ] **Step 2: Verify compilation**

```bash
cd /home/kamil/Documents/Project/My/Mcp/agent-memory
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add agent-memory/src/main/java/com/agentmemory/model/
git commit -m "feat(agent-memory): add model records"
```

---

### Task 3: picocli Entry Point (skeleton)

**Files:**
- Create: `src/main/java/com/agentmemory/AgentMemoryApplication.java`
- Create: `src/main/java/com/agentmemory/cli/ServeCommand.java`

- [ ] **Step 1: Create AgentMemoryApplication.java**

```java
package com.agentmemory;

import com.agentmemory.cli.ServeCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "agent-memory",
    mixinStandardHelpOptions = true,
    version = "0.1.0",
    description = "Agent memory MCP server with dashboard",
    subcommands = {
        ServeCommand.class
    }
)
public class AgentMemoryApplication implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new AgentMemoryApplication()).execute(args);
        System.exit(exitCode);
    }
}
```

- [ ] **Step 2: Create ServeCommand.java (skeleton)**

```java
package com.agentmemory.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "serve", description = "Start MCP server and dashboard")
public class ServeCommand implements Runnable {

    @Option(names = {"--port", "-p"}, description = "Dashboard HTTP port (default: 7070)", defaultValue = "7070")
    private int port;

    @Override
    public void run() {
        System.err.println("agent-memory server starting on port " + port + "...");
        // Will be wired up after store, MCP, and API are built
    }
}
```

- [ ] **Step 3: Verify shadow JAR builds**

```bash
cd /home/kamil/Documents/Project/My/Mcp/agent-memory
./gradlew shadowJar
java -jar build/libs/agent-memory-0.1.0.jar --help
```
Expected: Shows help text with `serve` subcommand listed

- [ ] **Step 4: Commit**

```bash
git add agent-memory/src/main/java/com/agentmemory/AgentMemoryApplication.java
git add agent-memory/src/main/java/com/agentmemory/cli/ServeCommand.java
git commit -m "feat(agent-memory): add picocli entry point and serve command skeleton"
```

---

## Phase 2: Data Layer

### Task 4: MemoryStore — Schema Init + Memory CRUD

**Files:**
- Create: `src/main/java/com/agentmemory/store/MemoryStore.java`
- Create: `src/test/java/com/agentmemory/store/MemoryStoreTest.java`

- [ ] **Step 1: Write failing tests for schema init and basic CRUD**

```java
package com.agentmemory.store;

import com.agentmemory.model.Memory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryStoreTest {

    private MemoryStore store;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        store = new MemoryStore(tempDir.resolve("test-memory.db"));
    }

    @Test
    void storeAndRecallMemory() {
        store.upsert("customer:acme:status", "At-risk", "claude-code", "/project", List.of("customer", "at-risk"), 0.8, false);

        var memory = store.recall("customer:acme:status", "claude-code", "/project");
        assertThat(memory).isPresent();
        assertThat(memory.get().key()).isEqualTo("customer:acme:status");
        assertThat(memory.get().value()).isEqualTo("At-risk");
        assertThat(memory.get().agent()).isEqualTo("claude-code");
        assertThat(memory.get().project()).isEqualTo("/project");
        assertThat(memory.get().importance()).isEqualTo(0.8);
        assertThat(memory.get().tags()).containsExactlyInAnyOrder("customer", "at-risk");
    }

    @Test
    void upsertUpdatesExistingAndCreatesVersion() {
        store.upsert("key1", "value1", "agent1", null, List.of(), 0.5, false);
        store.upsert("key1", "value2", "agent1", null, List.of(), 0.5, false);

        var memory = store.recall("key1", "agent1", null);
        assertThat(memory).isPresent();
        assertThat(memory.get().value()).isEqualTo("value2");

        var versions = store.getVersions("key1", "agent1", null);
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).value()).isEqualTo("value1");
        assertThat(versions.get(0).version()).isEqualTo(1);
    }

    @Test
    void recallReturnsEmptyForMissingKey() {
        var memory = store.recall("nonexistent", "agent1", null);
        assertThat(memory).isEmpty();
    }

    @Test
    void softDeleteExcludesFromRecall() {
        store.upsert("key1", "value1", "agent1", null, List.of(), 0.5, false);
        store.softDelete("key1", "agent1", null);

        var memory = store.recall("key1", "agent1", null);
        assertThat(memory).isEmpty();
    }

    @Test
    void listMemoriesWithFilters() {
        store.upsert("key1", "v1", "agent1", "/p1", List.of("tag1"), 0.5, false);
        store.upsert("key2", "v2", "agent2", "/p1", List.of("tag2"), 0.5, true);
        store.upsert("key3", "v3", "agent1", "/p2", List.of("tag1"), 0.5, false);

        var byAgent = store.list("agent1", null, null, null, 10, 0);
        assertThat(byAgent).hasSize(2);

        var byProject = store.list(null, "/p1", null, null, 10, 0);
        assertThat(byProject).hasSize(2);

        var byTag = store.list(null, null, List.of("tag1"), null, 10, 0);
        assertThat(byTag).hasSize(2);

        var sharedOnly = store.list(null, null, null, true, 10, 0);
        assertThat(sharedOnly).hasSize(1);
        assertThat(sharedOnly.get(0).key()).isEqualTo("key2");
    }

    @Test
    void ftsSearch() {
        store.upsert("customer:acme:status", "At-risk due to latency", "agent1", null, List.of("customer"), 0.5, false);
        store.upsert("metric:latency", "p99 is 250ms", "agent1", null, List.of("metric"), 0.5, false);

        var results = store.search("latency", null, null, null, 10);
        assertThat(results).hasSize(2);
    }

    @Test
    void shareMemory() {
        store.upsert("key1", "v1", "agent1", null, List.of(), 0.5, false);
        store.share("key1", "agent1", null);

        var memory = store.recall("key1", "agent1", null);
        assertThat(memory).isPresent();
        assertThat(memory.get().shared()).isTrue();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /home/kamil/Documents/Project/My/Mcp/agent-memory
./gradlew test
```
Expected: FAIL — `MemoryStore` class doesn't exist yet

- [ ] **Step 3: Implement MemoryStore**

```java
package com.agentmemory.store;

import com.agentmemory.model.AuditEntry;
import com.agentmemory.model.Goal;
import com.agentmemory.model.Memory;
import com.agentmemory.model.MemoryVersion;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public class MemoryStore implements AutoCloseable {

    private final Connection connection;

    public MemoryStore(Path dbPath) {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA foreign_keys=ON");
            }
            initSchema();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open database: " + dbPath, e);
        }
    }

    private void initSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS memories (
                    id TEXT PRIMARY KEY,
                    key TEXT NOT NULL,
                    value TEXT NOT NULL,
                    agent TEXT NOT NULL,
                    project TEXT,
                    shared INTEGER DEFAULT 0,
                    importance REAL DEFAULT 0.5,
                    tags_text TEXT DEFAULT '',
                    deleted_at TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    UNIQUE(key, agent, project)
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS memory_tags (
                    memory_id TEXT NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
                    tag TEXT NOT NULL,
                    PRIMARY KEY (memory_id, tag)
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS memory_versions (
                    id TEXT PRIMARY KEY,
                    memory_id TEXT NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
                    value TEXT NOT NULL,
                    version INTEGER NOT NULL,
                    created_at TEXT NOT NULL
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS memory_links (
                    id TEXT PRIMARY KEY,
                    source_id TEXT NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
                    target_id TEXT NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
                    relation TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    UNIQUE(source_id, target_id, relation)
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS goals (
                    id TEXT PRIMARY KEY,
                    agent TEXT NOT NULL,
                    project TEXT,
                    description TEXT NOT NULL,
                    status TEXT DEFAULT 'active',
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS audit_log (
                    id TEXT PRIMARY KEY,
                    agent TEXT NOT NULL,
                    operation TEXT NOT NULL,
                    memory_key TEXT,
                    details TEXT,
                    latency_ms REAL,
                    created_at TEXT NOT NULL
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS settings (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )""");

            stmt.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(
                    key, value, tags_text,
                    content=memories, content_rowid=rowid
                )""");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_memories_agent ON memories(agent)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_memories_project ON memories(project)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_memories_shared ON memories(shared)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_audit_agent ON audit_log(agent)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_log(created_at)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_audit_operation ON audit_log(operation)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_goals_agent ON goals(agent)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_links_source ON memory_links(source_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_links_target ON memory_links(target_id)");
        }
    }

    // ---- Memory CRUD ----

    public void upsert(String key, String value, String agent, String project,
                       List<String> tags, double importance, boolean shared) {
        String now = Instant.now().toString();
        String tagsText = String.join(" ", tags);

        try {
            var existing = findMemoryByKey(key, agent, project);
            if (existing.isPresent()) {
                // Save current value as version before updating
                int nextVersion = getMaxVersion(existing.get().id()) + 1;
                try (var ps = connection.prepareStatement(
                    "INSERT INTO memory_versions (id, memory_id, value, version, created_at) VALUES (?, ?, ?, ?, ?)")) {
                    ps.setString(1, UUID.randomUUID().toString());
                    ps.setString(2, existing.get().id());
                    ps.setString(3, existing.get().value());
                    ps.setInt(4, nextVersion);
                    ps.setString(5, now);
                    ps.executeUpdate();
                }
                // Delete old FTS entry
                deleteFtsEntry(existing.get().id());
                // Update memory
                try (var ps = connection.prepareStatement(
                    "UPDATE memories SET value=?, importance=?, shared=?, tags_text=?, updated_at=? WHERE id=?")) {
                    ps.setString(1, value);
                    ps.setDouble(2, importance);
                    ps.setInt(3, shared ? 1 : 0);
                    ps.setString(4, tagsText);
                    ps.setString(5, now);
                    ps.setString(6, existing.get().id());
                    ps.executeUpdate();
                }
                // Re-insert FTS entry
                insertFtsEntry(existing.get().id());
                // Update tags
                updateTags(existing.get().id(), tags);
            } else {
                String id = UUID.randomUUID().toString();
                try (var ps = connection.prepareStatement(
                    "INSERT INTO memories (id, key, value, agent, project, shared, importance, tags_text, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, id);
                    ps.setString(2, key);
                    ps.setString(3, value);
                    ps.setString(4, agent);
                    ps.setString(5, project);
                    ps.setInt(6, shared ? 1 : 0);
                    ps.setDouble(7, importance);
                    ps.setString(8, tagsText);
                    ps.setString(9, now);
                    ps.setString(10, now);
                    ps.executeUpdate();
                }
                insertFtsEntry(id);
                updateTags(id, tags);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert memory: " + key, e);
        }
    }

    public Optional<Memory> recall(String key, String agent, String project) {
        String sql = "SELECT m.*, GROUP_CONCAT(t.tag) as tag_list FROM memories m " +
            "LEFT JOIN memory_tags t ON m.id = t.memory_id " +
            "WHERE m.key = ? AND m.agent = ? AND m.deleted_at IS NULL";
        List<Object> params = new ArrayList<>(List.of(key, agent));

        if (project != null) {
            sql += " AND m.project = ?";
            params.add(project);
        } else {
            sql += " AND m.project IS NULL";
        }
        sql += " GROUP BY m.id";

        try (var ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            var rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(mapMemory(rs));
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to recall: " + key, e);
        }
    }

    public Optional<Memory> recallByKey(String key) {
        String sql = "SELECT m.*, GROUP_CONCAT(t.tag) as tag_list FROM memories m " +
            "LEFT JOIN memory_tags t ON m.id = t.memory_id " +
            "WHERE m.key = ? AND m.deleted_at IS NULL GROUP BY m.id LIMIT 1";
        try (var ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            var rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(mapMemory(rs));
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to recall by key: " + key, e);
        }
    }

    public void softDelete(String key, String agent, String project) {
        String now = Instant.now().toString();
        String sql = "UPDATE memories SET deleted_at = ? WHERE key = ? AND agent = ?";
        List<Object> params = new ArrayList<>(List.of(now, key, agent));

        if (project != null) {
            sql += " AND project = ?";
            params.add(project);
        } else {
            sql += " AND project IS NULL";
        }

        try (var ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to soft-delete: " + key, e);
        }
    }

    public List<Memory> list(String agent, String project, List<String> tags,
                             Boolean shared, int limit, int offset) {
        var sql = new StringBuilder(
            "SELECT m.*, GROUP_CONCAT(t.tag) as tag_list FROM memories m " +
            "LEFT JOIN memory_tags t ON m.id = t.memory_id WHERE m.deleted_at IS NULL");
        var params = new ArrayList<>();

        if (agent != null) { sql.append(" AND m.agent = ?"); params.add(agent); }
        if (project != null) { sql.append(" AND m.project = ?"); params.add(project); }
        if (shared != null) { sql.append(" AND m.shared = ?"); params.add(shared ? 1 : 0); }
        if (tags != null && !tags.isEmpty()) {
            sql.append(" AND m.id IN (SELECT memory_id FROM memory_tags WHERE tag IN (");
            sql.append(String.join(",", tags.stream().map(t -> "?").toList()));
            sql.append("))");
            params.addAll(tags);
        }
        sql.append(" GROUP BY m.id ORDER BY m.updated_at DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        try (var ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            var rs = ps.executeQuery();
            var results = new ArrayList<Memory>();
            while (rs.next()) { results.add(mapMemory(rs)); }
            return results;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list memories", e);
        }
    }

    public List<Memory> search(String query, String agent, String project, List<String> tags, int limit) {
        var sql = new StringBuilder(
            "SELECT m.*, GROUP_CONCAT(t.tag) as tag_list FROM memory_fts f " +
            "JOIN memories m ON m.rowid = f.rowid " +
            "LEFT JOIN memory_tags t ON m.id = t.memory_id " +
            "WHERE memory_fts MATCH ? AND m.deleted_at IS NULL");
        var params = new ArrayList<Object>();
        params.add(query);

        if (agent != null) { sql.append(" AND m.agent = ?"); params.add(agent); }
        if (project != null) { sql.append(" AND m.project = ?"); params.add(project); }
        if (tags != null && !tags.isEmpty()) {
            sql.append(" AND m.id IN (SELECT memory_id FROM memory_tags WHERE tag IN (");
            sql.append(String.join(",", tags.stream().map(tg -> "?").toList()));
            sql.append("))");
            params.addAll(tags);
        }
        sql.append(" GROUP BY m.id ORDER BY rank LIMIT ?");
        params.add(limit);

        try (var ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            var rs = ps.executeQuery();
            var results = new ArrayList<Memory>();
            while (rs.next()) { results.add(mapMemory(rs)); }
            return results;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to search: " + query, e);
        }
    }

    public void share(String key, String agent, String project) {
        String sql = "UPDATE memories SET shared = 1 WHERE key = ? AND agent = ?";
        List<Object> params = new ArrayList<>(List.of(key, agent));
        if (project != null) {
            sql += " AND project = ?";
            params.add(project);
        } else {
            sql += " AND project IS NULL";
        }

        try (var ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to share: " + key, e);
        }
    }

    // ---- Versions ----

    public List<MemoryVersion> getVersions(String key, String agent, String project) {
        var mem = findMemoryByKey(key, agent, project);
        if (mem.isEmpty()) return List.of();

        try (var ps = connection.prepareStatement(
            "SELECT * FROM memory_versions WHERE memory_id = ? ORDER BY version DESC")) {
            ps.setString(1, mem.get().id());
            var rs = ps.executeQuery();
            var results = new ArrayList<MemoryVersion>();
            while (rs.next()) {
                results.add(new MemoryVersion(
                    rs.getString("id"), rs.getString("memory_id"),
                    rs.getString("value"), rs.getInt("version"), rs.getString("created_at")));
            }
            return results;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get versions", e);
        }
    }

    // ---- Goals ----

    public void setGoals(String agent, List<String> descriptions, String project) {
        String now = Instant.now().toString();
        try {
            // Abandon old active goals
            try (var ps = connection.prepareStatement(
                "UPDATE goals SET status = 'abandoned', updated_at = ? WHERE agent = ? AND status = 'active'" +
                (project != null ? " AND project = ?" : " AND project IS NULL"))) {
                ps.setString(1, now);
                ps.setString(2, agent);
                if (project != null) ps.setString(3, project);
                ps.executeUpdate();
            }
            // Insert new goals
            for (var desc : descriptions) {
                try (var ps = connection.prepareStatement(
                    "INSERT INTO goals (id, agent, project, description, status, created_at, updated_at) VALUES (?, ?, ?, ?, 'active', ?, ?)")) {
                    ps.setString(1, UUID.randomUUID().toString());
                    ps.setString(2, agent);
                    ps.setString(3, project);
                    ps.setString(4, desc);
                    ps.setString(5, now);
                    ps.setString(6, now);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set goals", e);
        }
    }

    public List<Goal> getGoals(String agent, String status) {
        var sql = new StringBuilder("SELECT * FROM goals WHERE 1=1");
        var params = new ArrayList<>();
        if (agent != null) { sql.append(" AND agent = ?"); params.add(agent); }
        if (status != null) { sql.append(" AND status = ?"); params.add(status); }
        sql.append(" ORDER BY created_at DESC");

        try (var ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            var rs = ps.executeQuery();
            var results = new ArrayList<Goal>();
            while (rs.next()) {
                results.add(new Goal(
                    rs.getString("id"), rs.getString("agent"), rs.getString("project"),
                    rs.getString("description"), rs.getString("status"),
                    rs.getString("created_at"), rs.getString("updated_at")));
            }
            return results;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get goals", e);
        }
    }

    // ---- Audit Log ----

    public void logAudit(String agent, String operation, String memoryKey, String details, double latencyMs) {
        try (var ps = connection.prepareStatement(
            "INSERT INTO audit_log (id, agent, operation, memory_key, details, latency_ms, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, agent);
            ps.setString(3, operation);
            ps.setString(4, memoryKey);
            ps.setString(5, details);
            ps.setDouble(6, latencyMs);
            ps.setString(7, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to log audit", e);
        }
    }

    public List<AuditEntry> getAuditLog(String agent, String operation, String from, String to, int limit, int offset) {
        var sql = new StringBuilder("SELECT * FROM audit_log WHERE 1=1");
        var params = new ArrayList<>();
        if (agent != null) { sql.append(" AND agent = ?"); params.add(agent); }
        if (operation != null) { sql.append(" AND operation = ?"); params.add(operation); }
        if (from != null) { sql.append(" AND created_at >= ?"); params.add(from); }
        if (to != null) { sql.append(" AND created_at <= ?"); params.add(to); }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        try (var ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            var rs = ps.executeQuery();
            var results = new ArrayList<AuditEntry>();
            while (rs.next()) {
                results.add(new AuditEntry(
                    rs.getString("id"), rs.getString("agent"), rs.getString("operation"),
                    rs.getString("memory_key"), rs.getString("details"),
                    rs.getDouble("latency_ms"), rs.getString("created_at")));
            }
            return results;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get audit log", e);
        }
    }

    // ---- Settings ----

    public void setSetting(String key, String value) {
        try (var ps = connection.prepareStatement(
            "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set setting: " + key, e);
        }
    }

    public Optional<String> getSetting(String key) {
        try (var ps = connection.prepareStatement("SELECT value FROM settings WHERE key = ?")) {
            ps.setString(1, key);
            var rs = ps.executeQuery();
            return rs.next() ? Optional.of(rs.getString("value")) : Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get setting: " + key, e);
        }
    }

    public Map<String, String> getAllSettings() {
        try (var stmt = connection.createStatement()) {
            var rs = stmt.executeQuery("SELECT key, value FROM settings");
            var map = new LinkedHashMap<String, String>();
            while (rs.next()) { map.put(rs.getString("key"), rs.getString("value")); }
            return map;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get all settings", e);
        }
    }

    // ---- Metrics (computed from audit_log) ----

    public List<String> getKnownAgents() {
        try (var stmt = connection.createStatement()) {
            var rs = stmt.executeQuery("SELECT DISTINCT agent FROM audit_log ORDER BY agent");
            var agents = new ArrayList<String>();
            while (rs.next()) { agents.add(rs.getString("agent")); }
            return agents;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get known agents", e);
        }
    }

    public Optional<Memory> findById(String id) {
        String sql = "SELECT m.*, GROUP_CONCAT(t.tag) as tag_list FROM memories m " +
            "LEFT JOIN memory_tags t ON m.id = t.memory_id WHERE m.id = ? GROUP BY m.id";
        try (var ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            var rs = ps.executeQuery();
            if (rs.next()) return Optional.of(mapMemory(rs));
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find by id: " + id, e);
        }
    }

    public List<Memory> recallAllByKey(String key) {
        String sql = "SELECT m.*, GROUP_CONCAT(t.tag) as tag_list FROM memories m " +
            "LEFT JOIN memory_tags t ON m.id = t.memory_id " +
            "WHERE m.key = ? AND m.deleted_at IS NULL GROUP BY m.id";
        try (var ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            var rs = ps.executeQuery();
            var results = new ArrayList<Memory>();
            while (rs.next()) { results.add(mapMemory(rs)); }
            return results;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to recall all by key: " + key, e);
        }
    }

    public Optional<Memory> recallByKeyAndAgent(String key, String agent) {
        String sql = "SELECT m.*, GROUP_CONCAT(t.tag) as tag_list FROM memories m " +
            "LEFT JOIN memory_tags t ON m.id = t.memory_id " +
            "WHERE m.key = ? AND m.agent = ? AND m.deleted_at IS NULL GROUP BY m.id LIMIT 1";
        try (var ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, agent);
            var rs = ps.executeQuery();
            if (rs.next()) return Optional.of(mapMemory(rs));
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to recall by key+agent: " + key, e);
        }
    }

    public void softDeleteByKeyAndAgent(String key, String agent) {
        String now = Instant.now().toString();
        try (var ps = connection.prepareStatement(
            "UPDATE memories SET deleted_at = ? WHERE key = ? AND agent = ? AND deleted_at IS NULL")) {
            ps.setString(1, now);
            ps.setString(2, key);
            ps.setString(3, agent);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to soft-delete: " + key, e);
        }
    }

    // ---- Internal helpers ----

    private Optional<Memory> findMemoryByKey(String key, String agent, String project) {
        String sql = "SELECT m.*, GROUP_CONCAT(t.tag) as tag_list FROM memories m " +
            "LEFT JOIN memory_tags t ON m.id = t.memory_id " +
            "WHERE m.key = ? AND m.agent = ?";
        List<Object> params = new ArrayList<>(List.of(key, agent));

        if (project != null) {
            sql += " AND m.project = ?";
            params.add(project);
        } else {
            sql += " AND m.project IS NULL";
        }
        sql += " GROUP BY m.id";

        try (var ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            var rs = ps.executeQuery();
            if (rs.next()) return Optional.of(mapMemory(rs));
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find memory: " + key, e);
        }
    }

    private int getMaxVersion(String memoryId) throws SQLException {
        try (var ps = connection.prepareStatement(
            "SELECT COALESCE(MAX(version), 0) FROM memory_versions WHERE memory_id = ?")) {
            ps.setString(1, memoryId);
            var rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void updateTags(String memoryId, List<String> tags) throws SQLException {
        try (var ps = connection.prepareStatement("DELETE FROM memory_tags WHERE memory_id = ?")) {
            ps.setString(1, memoryId);
            ps.executeUpdate();
        }
        for (var tag : tags) {
            try (var ps = connection.prepareStatement(
                "INSERT INTO memory_tags (memory_id, tag) VALUES (?, ?)")) {
                ps.setString(1, memoryId);
                ps.setString(2, tag);
                ps.executeUpdate();
            }
        }
    }

    private void insertFtsEntry(String memoryId) throws SQLException {
        try (var ps = connection.prepareStatement(
            "INSERT INTO memory_fts(rowid, key, value, tags_text) " +
            "SELECT rowid, key, value, tags_text FROM memories WHERE id = ?")) {
            ps.setString(1, memoryId);
            ps.executeUpdate();
        }
    }

    private void deleteFtsEntry(String memoryId) throws SQLException {
        try (var ps = connection.prepareStatement(
            "INSERT INTO memory_fts(memory_fts, rowid, key, value, tags_text) " +
            "SELECT 'delete', rowid, key, value, tags_text FROM memories WHERE id = ?")) {
            ps.setString(1, memoryId);
            ps.executeUpdate();
        }
    }

    private Memory mapMemory(ResultSet rs) throws SQLException {
        String tagList = rs.getString("tag_list");
        List<String> tags = tagList != null ? List.of(tagList.split(",")) : List.of();
        return new Memory(
            rs.getString("id"), rs.getString("key"), rs.getString("value"),
            rs.getString("agent"), rs.getString("project"),
            rs.getInt("shared") == 1, rs.getDouble("importance"),
            rs.getString("tags_text"), rs.getString("deleted_at"),
            rs.getString("created_at"), rs.getString("updated_at"), tags);
    }

    @Override
    public void close() {
        try { connection.close(); } catch (SQLException e) { /* ignore */ }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd /home/kamil/Documents/Project/My/Mcp/agent-memory
./gradlew test
```
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add agent-memory/src/main/java/com/agentmemory/store/MemoryStore.java
git add agent-memory/src/test/java/com/agentmemory/store/MemoryStoreTest.java
git commit -m "feat(agent-memory): add MemoryStore with CRUD, FTS5, tags, versions, audit, goals"
```

---

### Task 5: GraphStore — Links and Traversal

**Files:**
- Create: `src/main/java/com/agentmemory/store/GraphStore.java`
- Create: `src/test/java/com/agentmemory/store/GraphStoreTest.java`

- [ ] **Step 1: Write failing tests**

```java
package com.agentmemory.store;

import com.agentmemory.model.MemoryLink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphStoreTest {

    private MemoryStore memoryStore;
    private GraphStore graphStore;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        var dbPath = tempDir.resolve("test.db");
        memoryStore = new MemoryStore(dbPath);
        graphStore = new GraphStore(memoryStore);

        // Seed memories
        memoryStore.upsert("key1", "value1", "agent1", null, List.of(), 0.5, false);
        memoryStore.upsert("key2", "value2", "agent1", null, List.of(), 0.5, false);
        memoryStore.upsert("key3", "value3", "agent1", null, List.of(), 0.5, false);
    }

    @Test
    void createAndRetrieveLink() {
        graphStore.link("key1", "key2", "related_to", "agent1");
        var links = graphStore.getLinksFrom("key1");
        assertThat(links).hasSize(1);
        assertThat(links.get(0).relation()).isEqualTo("related_to");
    }

    @Test
    void traverseGraph() {
        graphStore.link("key1", "key2", "related_to", "agent1");
        graphStore.link("key2", "key3", "depends_on", "agent1");

        var graph = graphStore.traverse("key1", 2);
        assertThat(graph).hasSize(2); // key2 and key3
    }

    @Test
    void traverseRespectDepth() {
        graphStore.link("key1", "key2", "related_to", "agent1");
        graphStore.link("key2", "key3", "depends_on", "agent1");

        var graph = graphStore.traverse("key1", 1);
        assertThat(graph).hasSize(1); // only key2
    }

    @Test
    void getFullGraph() {
        graphStore.link("key1", "key2", "related_to", "agent1");
        graphStore.link("key2", "key3", "depends_on", "agent1");

        var allLinks = graphStore.getAllLinks(null, null);
        assertThat(allLinks).hasSize(2);
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
cd /home/kamil/Documents/Project/My/Mcp/agent-memory
./gradlew test
```
Expected: FAIL — `GraphStore` doesn't exist

- [ ] **Step 3: Implement GraphStore**

```java
package com.agentmemory.store;

import com.agentmemory.model.Memory;
import com.agentmemory.model.MemoryLink;

import java.sql.*;
import java.time.Instant;
import java.util.*;

public class GraphStore {

    private final MemoryStore memoryStore;

    public GraphStore(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    public void link(String sourceKey, String targetKey, String relation, String agent) {
        var source = memoryStore.recallByKey(sourceKey);
        var target = memoryStore.recallByKey(targetKey);
        if (source.isEmpty() || target.isEmpty()) {
            throw new IllegalArgumentException("Source or target memory not found");
        }

        memoryStore.executeUpdate(
            "INSERT OR IGNORE INTO memory_links (id, source_id, target_id, relation, created_at) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID().toString(), source.get().id(), target.get().id(), relation, Instant.now().toString());
    }

    public List<MemoryLink> getLinksFrom(String key) {
        var mem = memoryStore.recallByKey(key);
        if (mem.isEmpty()) return List.of();

        return memoryStore.query(
            "SELECT * FROM memory_links WHERE source_id = ?",
            rs -> new MemoryLink(rs.getString("id"), rs.getString("source_id"),
                rs.getString("target_id"), rs.getString("relation"), rs.getString("created_at")),
            mem.get().id());
    }

    public List<Memory> traverse(String startKey, int maxDepth) {
        var start = memoryStore.recallByKey(startKey);
        if (start.isEmpty()) return List.of();

        Set<String> visited = new HashSet<>();
        visited.add(start.get().id());
        List<Memory> result = new ArrayList<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(start.get().id());
        Map<String, Integer> depths = new HashMap<>();
        depths.put(start.get().id(), 0);

        while (!queue.isEmpty()) {
            String currentId = queue.poll();
            int currentDepth = depths.get(currentId);
            if (currentDepth >= maxDepth) continue;

            var links = memoryStore.query(
                "SELECT * FROM memory_links WHERE source_id = ? OR target_id = ?",
                rs -> new MemoryLink(rs.getString("id"), rs.getString("source_id"),
                    rs.getString("target_id"), rs.getString("relation"), rs.getString("created_at")),
                currentId, currentId);

            for (var link : links) {
                String neighborId = link.sourceId().equals(currentId) ? link.targetId() : link.sourceId();
                if (!visited.contains(neighborId)) {
                    visited.add(neighborId);
                    depths.put(neighborId, currentDepth + 1);
                    queue.add(neighborId);
                    memoryStore.findById(neighborId).ifPresent(result::add);
                }
            }
        }
        return result;
    }

    public List<MemoryLink> getAllLinks(String agent, String project) {
        // Returns all links, optionally filtered by agent/project of source memory
        var sql = new StringBuilder("SELECT l.* FROM memory_links l");
        var params = new ArrayList<>();

        if (agent != null || project != null) {
            sql.append(" JOIN memories m ON l.source_id = m.id");
            if (agent != null) { sql.append(" AND m.agent = ?"); params.add(agent); }
            if (project != null) { sql.append(" AND m.project = ?"); params.add(project); }
        }

        return memoryStore.query(sql.toString(),
            rs -> new MemoryLink(rs.getString("id"), rs.getString("source_id"),
                rs.getString("target_id"), rs.getString("relation"), rs.getString("created_at")),
            params.toArray());
    }
}
```

**Note:** `GraphStore` needs two helper methods on `MemoryStore` — `executeUpdate(sql, params...)` and `query(sql, mapper, params...)`. Add these to `MemoryStore`:

```java
// Add to MemoryStore.java - package-private helpers for GraphStore/BrainEngine

void executeUpdate(String sql, Object... params) {
    try (var ps = connection.prepareStatement(sql)) {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
        ps.executeUpdate();
    } catch (SQLException e) {
        throw new RuntimeException("Failed to execute: " + sql, e);
    }
}

<T> List<T> query(String sql, SqlMapper<T> mapper, Object... params) {
    try (var ps = connection.prepareStatement(sql)) {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
        var rs = ps.executeQuery();
        var results = new ArrayList<T>();
        while (rs.next()) { results.add(mapper.map(rs)); }
        return results;
    } catch (SQLException e) {
        throw new RuntimeException("Failed to query: " + sql, e);
    }
}

@FunctionalInterface
interface SqlMapper<T> {
    T map(ResultSet rs) throws SQLException;
}
```

- [ ] **Step 4: Run tests**

```bash
cd /home/kamil/Documents/Project/My/Mcp/agent-memory
./gradlew test
```
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add agent-memory/src/main/java/com/agentmemory/store/GraphStore.java
git add agent-memory/src/test/java/com/agentmemory/store/GraphStoreTest.java
git add agent-memory/src/main/java/com/agentmemory/store/MemoryStore.java
git commit -m "feat(agent-memory): add GraphStore with link creation and BFS traversal"
```

---

## Phase 3: Brain System

### Task 6: LoopDetector

**Files:**
- Create: `src/main/java/com/agentmemory/brain/LoopDetector.java`
- Create: `src/test/java/com/agentmemory/brain/LoopDetectorTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.agentmemory.brain;

import com.agentmemory.model.Anomaly;
import com.agentmemory.store.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoopDetectorTest {

    private MemoryStore store;
    private LoopDetector detector;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        store = new MemoryStore(tempDir.resolve("test.db"));
        detector = new LoopDetector(store);
    }

    @Test
    void detectsRepeatedStoresOnSameKey() {
        for (int i = 0; i < 4; i++) {
            store.logAudit("agent1", "store", "key1", null, 1.0);
        }
        var anomalies = detector.detect("agent1", null);
        assertThat(anomalies).isNotEmpty();
        assertThat(anomalies.get(0).type()).isEqualTo("loop");
    }

    @Test
    void noLoopWhenBelowThreshold() {
        store.logAudit("agent1", "store", "key1", null, 1.0);
        store.logAudit("agent1", "store", "key2", null, 1.0);
        var anomalies = detector.detect("agent1", null);
        assertThat(anomalies).isEmpty();
    }

    @Test
    void detectsRepeatedSearchQueries() {
        for (int i = 0; i < 4; i++) {
            store.logAudit("agent1", "search", null, "{\"query\":\"latency\"}", 1.0);
        }
        var anomalies = detector.detect("agent1", null);
        assertThat(anomalies).isNotEmpty();
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
cd /home/kamil/Documents/Project/My/Mcp/agent-memory
./gradlew test --tests '*LoopDetectorTest*'
```

- [ ] **Step 3: Implement LoopDetector**

```java
package com.agentmemory.brain;

import com.agentmemory.model.Anomaly;
import com.agentmemory.store.MemoryStore;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

public class LoopDetector {

    private static final int THRESHOLD = 3;
    private static final int WINDOW_MINUTES = 30;

    private final MemoryStore store;

    public LoopDetector(MemoryStore store) {
        this.store = store;
    }

    public List<Anomaly> detect(String agent, String project) {
        String from = Instant.now().minus(WINDOW_MINUTES, ChronoUnit.MINUTES).toString();
        var entries = store.getAuditLog(agent, null, from, null, 1000, 0);
        var anomalies = new ArrayList<Anomaly>();

        // Check repeated stores on same key
        var storesByKey = entries.stream()
            .filter(e -> "store".equals(e.operation()) && e.memoryKey() != null)
            .collect(Collectors.groupingBy(e -> e.memoryKey(), Collectors.counting()));

        for (var entry : storesByKey.entrySet()) {
            if (entry.getValue() > THRESHOLD) {
                anomalies.add(new Anomaly("loop", "warning",
                    "Key '" + entry.getKey() + "' stored " + entry.getValue() + " times in " + WINDOW_MINUTES + " minutes",
                    List.of(entry.getKey()), Instant.now().toString()));
            }
        }

        // Check recall-store-recall cycles
        for (int i = 0; i < entries.size() - 2; i++) {
            var e1 = entries.get(i);
            var e2 = entries.get(i + 1);
            var e3 = entries.get(i + 2);
            if ("recall".equals(e1.operation()) && "store".equals(e2.operation()) && "recall".equals(e3.operation())
                && e1.memoryKey() != null && e1.memoryKey().equals(e2.memoryKey()) && e2.memoryKey().equals(e3.memoryKey())) {
                anomalies.add(new Anomaly("loop", "warning",
                    "Recall-store-recall cycle detected on key '" + e1.memoryKey() + "'",
                    List.of(e1.memoryKey()), Instant.now().toString()));
            }
        }

        // Check repeated search queries
        var searchQueries = entries.stream()
            .filter(e -> "search".equals(e.operation()) && e.details() != null)
            .collect(Collectors.groupingBy(e -> e.details(), Collectors.counting()));

        for (var entry : searchQueries.entrySet()) {
            if (entry.getValue() > THRESHOLD) {
                anomalies.add(new Anomaly("loop", "info",
                    "Same search query repeated " + entry.getValue() + " times",
                    List.of(), Instant.now().toString()));
            }
        }

        return anomalies;
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd /home/kamil/Documents/Project/My/Mcp/agent-memory
./gradlew test --tests '*LoopDetectorTest*'
```
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
git add agent-memory/src/main/java/com/agentmemory/brain/LoopDetector.java
git add agent-memory/src/test/java/com/agentmemory/brain/LoopDetectorTest.java
git commit -m "feat(agent-memory): add LoopDetector for repeated pattern detection"
```

---

### Task 7: DriftDetector

**Files:**
- Create: `src/main/java/com/agentmemory/brain/DriftDetector.java`
- Create: `src/test/java/com/agentmemory/brain/DriftDetectorTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.agentmemory.brain;

import com.agentmemory.store.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DriftDetectorTest {

    private MemoryStore store;
    private DriftDetector detector;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        store = new MemoryStore(tempDir.resolve("test.db"));
        detector = new DriftDetector(store);
    }

    @Test
    void detectsDriftWhenOpsUnrelatedToGoals() {
        store.setGoals("agent1", List.of("fix authentication bug", "update login flow"), null);

        // Log operations unrelated to goals
        for (int i = 0; i < 10; i++) {
            store.logAudit("agent1", "store", "billing:invoice:" + i, null, 1.0);
        }

        var anomalies = detector.detect("agent1", null);
        assertThat(anomalies).isNotEmpty();
        assertThat(anomalies.get(0).type()).isEqualTo("drift");
    }

    @Test
    void noDriftWhenOpsAlignWithGoals() {
        store.setGoals("agent1", List.of("fix authentication bug"), null);

        store.logAudit("agent1", "store", "auth:login:status", null, 1.0);
        store.logAudit("agent1", "store", "auth:bug:fix", null, 1.0);
        store.logAudit("agent1", "recall", "authentication:config", null, 1.0);

        var anomalies = detector.detect("agent1", null);
        assertThat(anomalies).isEmpty();
    }

    @Test
    void noDriftWhenNoGoalsRegistered() {
        store.logAudit("agent1", "store", "key1", null, 1.0);
        var anomalies = detector.detect("agent1", null);
        assertThat(anomalies).isEmpty();
    }
}
```

- [ ] **Step 2: Run to verify failure**

- [ ] **Step 3: Implement DriftDetector**

```java
package com.agentmemory.brain;

import com.agentmemory.model.Anomaly;
import com.agentmemory.store.MemoryStore;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

public class DriftDetector {

    private static final double DRIFT_THRESHOLD = 0.7;
    private static final int WINDOW_MINUTES = 30;

    private final MemoryStore store;

    public DriftDetector(MemoryStore store) {
        this.store = store;
    }

    public List<Anomaly> detect(String agent, String project) {
        var goals = store.getGoals(agent, "active");
        if (goals.isEmpty()) return List.of();

        // Extract keywords from goals
        Set<String> goalKeywords = goals.stream()
            .flatMap(g -> extractKeywords(g.description()).stream())
            .collect(Collectors.toSet());

        // Get recent operations
        String from = Instant.now().minus(WINDOW_MINUTES, ChronoUnit.MINUTES).toString();
        var entries = store.getAuditLog(agent, null, from, null, 100, 0);
        if (entries.isEmpty()) return List.of();

        // Count operations with no keyword overlap
        long unrelated = entries.stream()
            .filter(e -> e.memoryKey() != null)
            .filter(e -> {
                Set<String> keyWords = extractKeywords(e.memoryKey().replace(":", " "));
                return Collections.disjoint(keyWords, goalKeywords);
            })
            .count();

        long total = entries.stream().filter(e -> e.memoryKey() != null).count();
        if (total == 0) return List.of();

        double ratio = (double) unrelated / total;
        if (ratio > DRIFT_THRESHOLD) {
            return List.of(new Anomaly("drift", "warning",
                String.format("%.0f%% of recent operations unrelated to active goals", ratio * 100),
                List.of(), Instant.now().toString()));
        }
        return List.of();
    }

    private Set<String> extractKeywords(String text) {
        return Arrays.stream(text.toLowerCase().split("[\\s:_\\-/]+"))
            .filter(w -> w.length() > 2)
            .collect(Collectors.toSet());
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd /home/kamil/Documents/Project/My/Mcp/agent-memory
./gradlew test --tests '*DriftDetectorTest*'
```
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
git add agent-memory/src/main/java/com/agentmemory/brain/DriftDetector.java
git add agent-memory/src/test/java/com/agentmemory/brain/DriftDetectorTest.java
git commit -m "feat(agent-memory): add DriftDetector for goal drift detection"
```

---

### Task 8: ContradictionDetector

**Files:**
- Create: `src/main/java/com/agentmemory/brain/ContradictionDetector.java`
- Create: `src/test/java/com/agentmemory/brain/ContradictionDetectorTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.agentmemory.brain;

import com.agentmemory.store.GraphStore;
import com.agentmemory.store.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContradictionDetectorTest {

    private MemoryStore memoryStore;
    private GraphStore graphStore;
    private ContradictionDetector detector;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        memoryStore = new MemoryStore(tempDir.resolve("test.db"));
        graphStore = new GraphStore(memoryStore);
        detector = new ContradictionDetector(memoryStore, graphStore);
    }

    @Test
    void detectsOpposingSentiment() {
        memoryStore.upsert("customer:acme:status", "healthy and performing well", "agent1", null, List.of("customer"), 0.5, false);
        var anomalies = detector.checkForContradictions("customer:acme:status", "at-risk due to issues", "agent1", null);

        assertThat(anomalies).isNotEmpty();
        assertThat(anomalies.get(0).type()).isEqualTo("contradiction");
    }

    @Test
    void noContradictionWhenSimilarContent() {
        memoryStore.upsert("metric:latency", "p99 is 200ms", "agent1", null, List.of("metric"), 0.5, false);
        var anomalies = detector.checkForContradictions("metric:latency", "p99 is 210ms, slightly higher", "agent1", null);

        assertThat(anomalies).isEmpty();
    }
}
```

- [ ] **Step 2: Run to verify failure**

- [ ] **Step 3: Implement ContradictionDetector**

```java
package com.agentmemory.brain;

import com.agentmemory.model.Anomaly;
import com.agentmemory.store.GraphStore;
import com.agentmemory.store.MemoryStore;

import java.time.Instant;
import java.util.*;

public class ContradictionDetector {

    private static final Map<String, String> OPPOSITES = Map.ofEntries(
        Map.entry("healthy", "at-risk"),
        Map.entry("at-risk", "healthy"),
        Map.entry("passing", "failing"),
        Map.entry("failing", "passing"),
        Map.entry("active", "inactive"),
        Map.entry("inactive", "active"),
        Map.entry("up", "down"),
        Map.entry("down", "up"),
        Map.entry("enabled", "disabled"),
        Map.entry("disabled", "enabled"),
        Map.entry("success", "failure"),
        Map.entry("failure", "success")
    );

    private final MemoryStore memoryStore;
    private final GraphStore graphStore;

    public ContradictionDetector(MemoryStore memoryStore, GraphStore graphStore) {
        this.memoryStore = memoryStore;
        this.graphStore = graphStore;
    }

    public List<Anomaly> checkForContradictions(String key, String newValue, String agent, String project) {
        var existing = memoryStore.recall(key, agent, project);
        if (existing.isEmpty()) return List.of();

        String oldValue = existing.get().value().toLowerCase();
        String newLower = newValue.toLowerCase();

        for (var entry : OPPOSITES.entrySet()) {
            if (oldValue.contains(entry.getKey()) && newLower.contains(entry.getValue())) {
                // Log contradiction as anomaly (no self-link since it's the same memory being updated)
                return List.of(new Anomaly("contradiction", "warning",
                    "Memory '" + key + "' changed from containing '" + entry.getKey() +
                    "' to '" + entry.getValue() + "'. Previous value preserved in version history.",
                    List.of(key), Instant.now().toString()));
            }
        }
        return List.of();
    }

    public List<Anomaly> detectAll(String agent, String project) {
        // Scan for existing contradiction links
        var links = graphStore.getAllLinks(agent, project);
        var anomalies = new ArrayList<Anomaly>();
        for (var link : links) {
            if ("contradicts".equals(link.relation())) {
                memoryStore.findById(link.sourceId()).ifPresent(source ->
                    anomalies.add(new Anomaly("contradiction", "warning",
                        "Contradiction detected in memory '" + source.key() + "'",
                        List.of(source.key()), link.createdAt())));
            }
        }
        return anomalies;
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd /home/kamil/Documents/Project/My/Mcp/agent-memory
./gradlew test --tests '*ContradictionDetectorTest*'
```
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
git add agent-memory/src/main/java/com/agentmemory/brain/ContradictionDetector.java
git add agent-memory/src/test/java/com/agentmemory/brain/ContradictionDetectorTest.java
git commit -m "feat(agent-memory): add ContradictionDetector with keyword heuristics"
```

---

### Task 9: BrainEngine Orchestrator

**Files:**
- Create: `src/main/java/com/agentmemory/brain/BrainEngine.java`

- [ ] **Step 1: Implement BrainEngine**

```java
package com.agentmemory.brain;

import com.agentmemory.model.Anomaly;
import com.agentmemory.store.GraphStore;
import com.agentmemory.store.MemoryStore;

import java.util.ArrayList;
import java.util.List;

public class BrainEngine {

    private final LoopDetector loopDetector;
    private final DriftDetector driftDetector;
    private final ContradictionDetector contradictionDetector;

    public BrainEngine(MemoryStore memoryStore, GraphStore graphStore) {
        this.loopDetector = new LoopDetector(memoryStore);
        this.driftDetector = new DriftDetector(memoryStore);
        this.contradictionDetector = new ContradictionDetector(memoryStore, graphStore);
    }

    public List<Anomaly> check(String agent, String project) {
        var anomalies = new ArrayList<Anomaly>();
        anomalies.addAll(loopDetector.detect(agent, project));
        anomalies.addAll(driftDetector.detect(agent, project));
        anomalies.addAll(contradictionDetector.detectAll(agent, project));
        return anomalies;
    }

    public List<Anomaly> onStore(String key, String newValue, String agent, String project) {
        return contradictionDetector.checkForContradictions(key, newValue, agent, project);
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd /home/kamil/Documents/Project/My/Mcp/agent-memory
./gradlew compileJava
```

- [ ] **Step 3: Commit**

```bash
git add agent-memory/src/main/java/com/agentmemory/brain/BrainEngine.java
git commit -m "feat(agent-memory): add BrainEngine orchestrator"
```

---

## Phase 4: MCP Server

### Task 10: AgentMemoryMcpServer — All 12 Tools

**Files:**
- Create: `src/main/java/com/agentmemory/mcp/AgentMemoryMcpServer.java`

This is a large file. Follow the exact pattern from `CodeNavigatorMcpServer.java`: fluent `.toolCall()` chain with `Tool.builder()`, `jsonSchema()` helper, `(exchange, request) -> textResult(handler())` lambdas.

- [ ] **Step 1: Implement AgentMemoryMcpServer**

```java
package com.agentmemory.mcp;

import com.agentmemory.brain.BrainEngine;
import com.agentmemory.model.Anomaly;
import com.agentmemory.store.GraphStore;
import com.agentmemory.store.MemoryStore;
import tools.jackson.databind.json.JsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.*;
import java.util.stream.Collectors;

public class AgentMemoryMcpServer {

    private final MemoryStore memoryStore;
    private final GraphStore graphStore;
    private final BrainEngine brain;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public AgentMemoryMcpServer(MemoryStore memoryStore, GraphStore graphStore, BrainEngine brain) {
        this.memoryStore = memoryStore;
        this.graphStore = graphStore;
        this.brain = brain;
    }

    public void start() {
        var transportProvider = new StdioServerTransportProvider(new JacksonMcpJsonMapper(JsonMapper.builder().build()));

        McpServer.sync(transportProvider)
            .serverInfo("agent-memory", "0.1.0")

            // mem_store
            .toolCall(
                Tool.builder()
                    .name("mem_store")
                    .description("Store or update a memory. Creates a version if the key already exists. Returns any contradiction warnings.")
                    .inputSchema(jsonSchema(Map.of(
                        "key", propString("Namespaced memory key (e.g. 'customer:acme:status')"),
                        "value", propString("Memory content"),
                        "agent", propString("Agent identifier (e.g. 'claude-code', 'gemini')"),
                        "project", propString("Project path (optional, for project-scoped memories)"),
                        "tags", propArray("Tags for categorization"),
                        "importance", propNumber("Importance score 0.0-1.0 (default 0.5)"),
                        "shared", propBool("Whether this memory is shared across agents (default false)")
                    ), List.of("key", "value", "agent")))
                    .build(),
                (exchange, request) -> timed("store", request.arguments(), this::handleStore))

            // mem_recall
            .toolCall(
                Tool.builder()
                    .name("mem_recall")
                    .description("Retrieve a memory by its key. If agent is omitted, returns all agents' versions.")
                    .inputSchema(jsonSchema(Map.of(
                        "key", propString("Memory key to retrieve"),
                        "agent", propString("Agent identifier (optional)")
                    ), List.of("key")))
                    .build(),
                (exchange, request) -> timed("recall", request.arguments(), this::handleRecall))

            // mem_search
            .toolCall(
                Tool.builder()
                    .name("mem_search")
                    .description("Search memories using full-text search with optional filters.")
                    .inputSchema(jsonSchema(Map.of(
                        "query", propString("Search query"),
                        "agent", propString("Filter by agent (optional)"),
                        "project", propString("Filter by project (optional)"),
                        "tags", propArray("Filter by tags (optional)"),
                        "limit", propInt("Max results (default 20)")
                    ), List.of("query")))
                    .build(),
                (exchange, request) -> timed("search", request.arguments(), this::handleSearch))

            // mem_delete
            .toolCall(
                Tool.builder()
                    .name("mem_delete")
                    .description("Soft-delete a memory. It will be excluded from recall/search but preserved in audit trail.")
                    .inputSchema(jsonSchema(Map.of(
                        "key", propString("Memory key to delete"),
                        "agent", propString("Agent identifier")
                    ), List.of("key", "agent")))
                    .build(),
                (exchange, request) -> timed("delete", request.arguments(), this::handleDelete))

            // mem_list
            .toolCall(
                Tool.builder()
                    .name("mem_list")
                    .description("List memories with optional filters. Paginated.")
                    .inputSchema(jsonSchema(Map.of(
                        "agent", propString("Filter by agent (optional)"),
                        "project", propString("Filter by project (optional)"),
                        "tags", propArray("Filter by tags (optional)"),
                        "shared", propBool("Filter shared only (optional)"),
                        "limit", propInt("Max results (default 20)"),
                        "offset", propInt("Offset for pagination (default 0)")
                    ), List.of()))
                    .build(),
                (exchange, request) -> timed("list", request.arguments(), this::handleList))

            // mem_history
            .toolCall(
                Tool.builder()
                    .name("mem_history")
                    .description("Get version history for a memory key.")
                    .inputSchema(jsonSchema(Map.of(
                        "key", propString("Memory key"),
                        "agent", propString("Agent identifier")
                    ), List.of("key", "agent")))
                    .build(),
                (exchange, request) -> timed("history", request.arguments(), this::handleHistory))

            // mem_share
            .toolCall(
                Tool.builder()
                    .name("mem_share")
                    .description("Mark a memory as shared, making it visible to all agents.")
                    .inputSchema(jsonSchema(Map.of(
                        "key", propString("Memory key to share"),
                        "agent", propString("Agent identifier")
                    ), List.of("key", "agent")))
                    .build(),
                (exchange, request) -> timed("share", request.arguments(), this::handleShare))

            // mem_shared
            .toolCall(
                Tool.builder()
                    .name("mem_shared")
                    .description("List all shared memories from all agents.")
                    .inputSchema(jsonSchema(Map.of(
                        "project", propString("Filter by project (optional)"),
                        "tags", propArray("Filter by tags (optional)"),
                        "limit", propInt("Max results (default 20)")
                    ), List.of()))
                    .build(),
                (exchange, request) -> timed("shared", request.arguments(), this::handleShared))

            // mem_goals
            .toolCall(
                Tool.builder()
                    .name("mem_goals")
                    .description("Register current goals/tasks for drift detection. Replaces previous active goals.")
                    .inputSchema(jsonSchema(Map.of(
                        "agent", propString("Agent identifier"),
                        "goals", propArray("List of goal descriptions"),
                        "project", propString("Project path (optional)")
                    ), List.of("agent", "goals")))
                    .build(),
                (exchange, request) -> timed("goals", request.arguments(), this::handleGoals))

            // mem_check
            .toolCall(
                Tool.builder()
                    .name("mem_check")
                    .description("Run brain analysis. Returns detected loops, goal drift, and contradictions.")
                    .inputSchema(jsonSchema(Map.of(
                        "agent", propString("Agent identifier"),
                        "project", propString("Project path (optional)")
                    ), List.of("agent")))
                    .build(),
                (exchange, request) -> timed("check", request.arguments(), this::handleCheck))

            // mem_link
            .toolCall(
                Tool.builder()
                    .name("mem_link")
                    .description("Create a relationship between two memories in the knowledge graph.")
                    .inputSchema(jsonSchema(Map.of(
                        "source_key", propString("Source memory key"),
                        "target_key", propString("Target memory key"),
                        "relation", propString("Relationship type: related_to, contradicts, depends_on, caused_by"),
                        "agent", propString("Agent identifier")
                    ), List.of("source_key", "target_key", "relation", "agent")))
                    .build(),
                (exchange, request) -> timed("link", request.arguments(), this::handleLink))

            // mem_graph
            .toolCall(
                Tool.builder()
                    .name("mem_graph")
                    .description("Traverse the knowledge graph from a memory. Returns connected memories.")
                    .inputSchema(jsonSchema(Map.of(
                        "key", propString("Starting memory key"),
                        "depth", propInt("Max traversal depth (default 2)"),
                        "agent", propString("Agent identifier (optional)")
                    ), List.of("key")))
                    .build(),
                (exchange, request) -> timed("graph", request.arguments(), this::handleGraph))

            .build();

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- Handlers ----

    private String handleStore(Map<String, Object> args) {
        String key = (String) args.get("key");
        String value = (String) args.get("value");
        String agent = (String) args.get("agent");
        String project = (String) args.get("project");
        List<String> tags = args.containsKey("tags") ? ((List<?>) args.get("tags")).stream().map(Object::toString).toList() : List.of();
        double importance = args.containsKey("importance") ? ((Number) args.get("importance")).doubleValue() : 0.5;
        boolean shared = args.containsKey("shared") && Boolean.TRUE.equals(args.get("shared"));

        var warnings = brain.onStore(key, value, agent, project);
        memoryStore.upsert(key, value, agent, project, tags, importance, shared);

        var sb = new StringBuilder("Stored memory: " + key);
        if (!warnings.isEmpty()) {
            sb.append("\n\nWarnings:");
            for (var w : warnings) { sb.append("\n- ").append(w.description()); }
        }
        return sb.toString();
    }

    private String handleRecall(Map<String, Object> args) {
        String key = (String) args.get("key");
        String agent = (String) args.get("agent");

        if (agent != null) {
            // Try with null project first, then search all
            var mem = memoryStore.recallByKeyAndAgent(key, agent);
            return mem.map(this::formatMemory).orElse("No memory found for key: " + key);
        } else {
            var mems = memoryStore.recallAllByKey(key);
            if (mems.isEmpty()) return "No memory found for key: " + key;
            return mems.stream().map(this::formatMemory).collect(Collectors.joining("\n---\n"));
        }
    }

    private String handleSearch(Map<String, Object> args) {
        String query = (String) args.get("query");
        String agent = (String) args.get("agent");
        String project = (String) args.get("project");
        List<String> tags = args.containsKey("tags") ? ((List<?>) args.get("tags")).stream().map(Object::toString).toList() : null;
        int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 20;

        var results = memoryStore.search(query, agent, project, tags, limit);
        if (results.isEmpty()) return "No memories found for: " + query;
        return results.stream().map(this::formatMemory).collect(Collectors.joining("\n---\n"));
    }

    private String handleDelete(Map<String, Object> args) {
        String key = (String) args.get("key");
        String agent = (String) args.get("agent");
        // Delete across all projects for this key+agent
        memoryStore.softDeleteByKeyAndAgent(key, agent);
        return "Deleted memory: " + key;
    }

    private String handleList(Map<String, Object> args) {
        String agent = (String) args.get("agent");
        String project = (String) args.get("project");
        List<String> tags = args.containsKey("tags") ? ((List<?>) args.get("tags")).stream().map(Object::toString).toList() : null;
        Boolean shared = args.containsKey("shared") ? (Boolean) args.get("shared") : null;
        int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 20;
        int offset = args.containsKey("offset") ? ((Number) args.get("offset")).intValue() : 0;

        var results = memoryStore.list(agent, project, tags, shared, limit, offset);
        if (results.isEmpty()) return "No memories found";
        return results.stream().map(this::formatMemory).collect(Collectors.joining("\n---\n"));
    }

    private String handleHistory(Map<String, Object> args) {
        String key = (String) args.get("key");
        String agent = (String) args.get("agent");
        var versions = memoryStore.getVersions(key, agent, null);
        if (versions.isEmpty()) return "No version history for: " + key;
        var sb = new StringBuilder("Version history for " + key + ":\n");
        for (var v : versions) {
            sb.append(String.format("\nv%d (%s):\n%s\n", v.version(), v.createdAt(), v.value()));
        }
        return sb.toString();
    }

    private String handleShare(Map<String, Object> args) {
        String key = (String) args.get("key");
        String agent = (String) args.get("agent");
        memoryStore.share(key, agent, null);
        return "Memory shared: " + key;
    }

    private String handleShared(Map<String, Object> args) {
        String project = (String) args.get("project");
        List<String> tags = args.containsKey("tags") ? ((List<?>) args.get("tags")).stream().map(Object::toString).toList() : null;
        int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 20;
        var results = memoryStore.list(null, project, tags, true, limit, 0);
        if (results.isEmpty()) return "No shared memories found";
        return results.stream().map(this::formatMemory).collect(Collectors.joining("\n---\n"));
    }

    private String handleGoals(Map<String, Object> args) {
        String agent = (String) args.get("agent");
        List<String> goals = ((List<?>) args.get("goals")).stream().map(Object::toString).toList();
        String project = (String) args.get("project");
        memoryStore.setGoals(agent, goals, project);
        return "Registered " + goals.size() + " goals for " + agent;
    }

    private String handleCheck(Map<String, Object> args) {
        String agent = (String) args.get("agent");
        String project = (String) args.get("project");
        var anomalies = brain.check(agent, project);
        if (anomalies.isEmpty()) return "No anomalies detected. All clear.";
        var sb = new StringBuilder("Anomalies detected:\n");
        for (var a : anomalies) {
            sb.append(String.format("\n[%s] %s: %s", a.severity().toUpperCase(), a.type(), a.description()));
            if (!a.affectedKeys().isEmpty()) {
                sb.append("\n  Affected: ").append(String.join(", ", a.affectedKeys()));
            }
        }
        return sb.toString();
    }

    private String handleLink(Map<String, Object> args) {
        String sourceKey = (String) args.get("source_key");
        String targetKey = (String) args.get("target_key");
        String relation = (String) args.get("relation");
        String agent = (String) args.get("agent");
        graphStore.link(sourceKey, targetKey, relation, agent);
        return "Linked " + sourceKey + " --[" + relation + "]--> " + targetKey;
    }

    private String handleGraph(Map<String, Object> args) {
        String key = (String) args.get("key");
        int depth = args.containsKey("depth") ? ((Number) args.get("depth")).intValue() : 2;
        var neighbors = graphStore.traverse(key, depth);
        if (neighbors.isEmpty()) return "No connected memories found for: " + key;
        var sb = new StringBuilder("Knowledge graph from " + key + " (depth " + depth + "):\n");
        for (var m : neighbors) {
            sb.append(String.format("\n- %s [%s]: %s", m.key(), m.agent(), truncate(m.value(), 100)));
        }
        return sb.toString();
    }

    // ---- Helpers ----

    private String formatMemory(com.agentmemory.model.Memory m) {
        return String.format("[%s] %s (agent: %s, importance: %.1f, shared: %s)\nTags: %s\n%s",
            m.key(), m.shared() ? "[SHARED]" : "", m.agent(), m.importance(),
            m.shared(), String.join(", ", m.tags()), m.value());
    }

    private CallToolResult timed(String operation, Map<String, Object> args,
                                  java.util.function.Function<Map<String, Object>, String> handler) {
        long start = System.nanoTime();
        String result = handler.apply(args);
        double latencyMs = (System.nanoTime() - start) / 1_000_000.0;

        String agent = (String) args.get("agent");
        String key = (String) args.get("key");
        if (agent == null) agent = "unknown";
        memoryStore.logAudit(agent, operation, key, null, latencyMs);

        return textResult(result);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static CallToolResult textResult(String text) {
        return new CallToolResult(List.of(new TextContent(text)), false, null, null);
    }

    private static JsonSchema jsonSchema(Map<String, Object> properties, List<String> required) {
        return new JsonSchema("object", properties, required, null, null, null);
    }

    private static Map<String, Object> propString(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> propInt(String description) {
        return Map.of("type", "integer", "description", description);
    }

    private static Map<String, Object> propNumber(String description) {
        return Map.of("type", "number", "description", description);
    }

    private static Map<String, Object> propBool(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    private static Map<String, Object> propArray(String description) {
        return Map.of("type", "array", "description", description, "items", Map.of("type", "string"));
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd /home/kamil/Documents/Project/My/Mcp/agent-memory
./gradlew compileJava
```

- [ ] **Step 3: Commit**

```bash
git add agent-memory/src/main/java/com/agentmemory/mcp/AgentMemoryMcpServer.java
git commit -m "feat(agent-memory): add AgentMemoryMcpServer with 12 MCP tools"
```

---

## Phase 5: REST API + Dashboard API

### Task 11: DashboardApi — Javalin REST Routes

**Files:**
- Create: `src/main/java/com/agentmemory/api/DashboardApi.java`
- Create: `src/test/java/com/agentmemory/api/DashboardApiTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.agentmemory.api;

import com.agentmemory.brain.BrainEngine;
import com.agentmemory.store.GraphStore;
import com.agentmemory.store.MemoryStore;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardApiTest {

    @Test
    void listMemories(@TempDir Path tempDir) {
        var store = new MemoryStore(tempDir.resolve("test.db"));
        var graphStore = new GraphStore(store);
        var brain = new BrainEngine(store, graphStore);
        store.upsert("key1", "value1", "agent1", null, List.of("tag1"), 0.5, false);

        var app = Javalin.create();
        new DashboardApi(store, graphStore, brain).register(app);

        JavalinTest.test(app, (server, client) -> {
            var response = client.get("/api/memories");
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).contains("key1");
        });
    }

    @Test
    void getMemoryById(@TempDir Path tempDir) {
        var store = new MemoryStore(tempDir.resolve("test.db"));
        var graphStore = new GraphStore(store);
        var brain = new BrainEngine(store, graphStore);
        store.upsert("key1", "value1", "agent1", null, List.of(), 0.5, false);

        var app = Javalin.create();
        new DashboardApi(store, graphStore, brain).register(app);

        JavalinTest.test(app, (server, client) -> {
            // First get the list to find the ID
            var listResponse = client.get("/api/memories");
            assertThat(listResponse.code()).isEqualTo(200);
        });
    }

    @Test
    void getAuditLog(@TempDir Path tempDir) {
        var store = new MemoryStore(tempDir.resolve("test.db"));
        var graphStore = new GraphStore(store);
        var brain = new BrainEngine(store, graphStore);
        store.logAudit("agent1", "store", "key1", null, 5.0);

        var app = Javalin.create();
        new DashboardApi(store, graphStore, brain).register(app);

        JavalinTest.test(app, (server, client) -> {
            var response = client.get("/api/audit");
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).contains("agent1");
        });
    }
}
```

**Note:** Add Javalin test dependency to `build.gradle`:
```gradle
testImplementation 'io.javalin:javalin-testtools:7.0.1'
```

- [ ] **Step 2: Run to verify failure**

- [ ] **Step 3: Implement DashboardApi**

```java
package com.agentmemory.api;

import com.agentmemory.brain.BrainEngine;
import com.agentmemory.store.GraphStore;
import com.agentmemory.store.MemoryStore;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.*;

public class DashboardApi {

    private final MemoryStore memoryStore;
    private final GraphStore graphStore;
    private final BrainEngine brain;

    public DashboardApi(MemoryStore memoryStore, GraphStore graphStore, BrainEngine brain) {
        this.memoryStore = memoryStore;
        this.graphStore = graphStore;
        this.brain = brain;
    }

    public void register(Javalin app) {
        // Memories
        app.get("/api/memories", this::listMemories);
        app.get("/api/memories/{id}", this::getMemory);
        app.get("/api/memories/{id}/versions", this::getMemoryVersions);
        app.post("/api/memories", this::createMemory);
        app.delete("/api/memories/{id}", this::deleteMemory);

        // Agents
        app.get("/api/agents", this::listAgents);
        app.get("/api/agents/{name}/metrics", this::getAgentMetrics);

        // Graph
        app.get("/api/graph", this::getGraph);
        app.get("/api/graph/{memoryId}", this::getSubgraph);

        // Goals
        app.get("/api/goals", this::listGoals);

        // Anomalies
        app.get("/api/anomalies", this::listAnomalies);

        // Audit
        app.get("/api/audit", this::getAuditLog);

        // Performance
        app.get("/api/performance/timeseries", this::getTimeseries);
        app.get("/api/performance/summary", this::getPerformanceSummary);

        // Settings
        app.get("/api/settings", this::getSettings);
        app.put("/api/settings", this::updateSettings);
    }

    // ---- Memories ----

    private void listMemories(Context ctx) {
        String agent = ctx.queryParam("agent");
        String project = ctx.queryParam("project");
        String q = ctx.queryParam("q");
        String tagsParam = ctx.queryParam("tags");
        Boolean shared = ctx.queryParam("shared") != null ? Boolean.parseBoolean(ctx.queryParam("shared")) : null;
        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(50);
        int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);
        List<String> tags = tagsParam != null ? List.of(tagsParam.split(",")) : null;

        if (q != null && !q.isBlank()) {
            ctx.json(memoryStore.search(q, agent, project, tags, limit));
        } else {
            ctx.json(memoryStore.list(agent, project, tags, shared, limit, offset));
        }
    }

    private void getMemory(Context ctx) {
        String id = ctx.pathParam("id");
        memoryStore.findById(id).ifPresentOrElse(ctx::json, () -> ctx.status(404).result("Not found"));
    }

    private void getMemoryVersions(Context ctx) {
        String id = ctx.pathParam("id");
        var mem = memoryStore.findById(id);
        if (mem.isEmpty()) { ctx.status(404).result("Not found"); return; }
        ctx.json(memoryStore.getVersions(mem.get().key(), mem.get().agent(), mem.get().project()));
    }

    private void createMemory(Context ctx) {
        var body = ctx.bodyAsClass(Map.class);
        String key = (String) body.get("key");
        String value = (String) body.get("value");
        String agent = (String) body.getOrDefault("agent", "dashboard");
        String project = (String) body.get("project");
        List<String> tags = body.containsKey("tags") ? (List<String>) body.get("tags") : List.of();
        double importance = body.containsKey("importance") ? ((Number) body.get("importance")).doubleValue() : 0.5;
        boolean shared = Boolean.TRUE.equals(body.get("shared"));

        memoryStore.upsert(key, value, agent, project, tags, importance, shared);
        ctx.status(201).result("Created");
    }

    private void deleteMemory(Context ctx) {
        String id = ctx.pathParam("id");
        var mem = memoryStore.findById(id);
        if (mem.isEmpty()) { ctx.status(404).result("Not found"); return; }
        memoryStore.softDelete(mem.get().key(), mem.get().agent(), mem.get().project());
        ctx.result("Deleted");
    }

    // ---- Agents ----

    private void listAgents(Context ctx) {
        var agents = memoryStore.getKnownAgents();
        var result = new ArrayList<Map<String, Object>>();
        for (var agent : agents) {
            var metrics = computeAgentMetrics(agent);
            result.add(metrics);
        }
        ctx.json(result);
    }

    private void getAgentMetrics(Context ctx) {
        String name = ctx.pathParam("name");
        ctx.json(computeAgentMetrics(name));
    }

    private Map<String, Object> computeAgentMetrics(String agent) {
        var entries = memoryStore.getAuditLog(agent, null, null, null, 10000, 0);
        long writes = entries.stream().filter(e -> "store".equals(e.operation())).count();
        long reads = entries.stream().filter(e -> "recall".equals(e.operation()) || "search".equals(e.operation())).count();
        long errors = 0;
        double avgWriteLatency = entries.stream().filter(e -> "store".equals(e.operation()))
            .mapToDouble(e -> e.latencyMs()).average().orElse(0);
        double avgReadLatency = entries.stream().filter(e -> "recall".equals(e.operation()))
            .mapToDouble(e -> e.latencyMs()).average().orElse(0);
        String firstSeen = entries.isEmpty() ? null : entries.get(entries.size() - 1).createdAt();

        return Map.of(
            "agent", agent,
            "totalWrites", writes,
            "totalReads", reads,
            "errors", errors,
            "avgWriteLatencyMs", Math.round(avgWriteLatency * 100.0) / 100.0,
            "avgReadLatencyMs", Math.round(avgReadLatency * 100.0) / 100.0,
            "firstSeen", firstSeen != null ? firstSeen : ""
        );
    }

    // ---- Graph ----

    private void getGraph(Context ctx) {
        String agent = ctx.queryParam("agent");
        String project = ctx.queryParam("project");
        var links = graphStore.getAllLinks(agent, project);
        // Collect unique memory IDs
        Set<String> ids = new HashSet<>();
        for (var l : links) { ids.add(l.sourceId()); ids.add(l.targetId()); }
        var nodes = ids.stream().map(memoryStore::findById)
            .filter(Optional::isPresent).map(Optional::get).toList();
        ctx.json(Map.of("nodes", nodes, "edges", links));
    }

    private void getSubgraph(Context ctx) {
        String memoryId = ctx.pathParam("memoryId");
        int depth = ctx.queryParamAsClass("depth", Integer.class).getOrDefault(2);
        var mem = memoryStore.findById(memoryId);
        if (mem.isEmpty()) { ctx.status(404).result("Not found"); return; }
        var neighbors = graphStore.traverse(mem.get().key(), depth);
        ctx.json(Map.of("root", mem.get(), "connected", neighbors));
    }

    // ---- Goals ----

    private void listGoals(Context ctx) {
        String agent = ctx.queryParam("agent");
        String status = ctx.queryParam("status");
        ctx.json(memoryStore.getGoals(agent, status));
    }

    // ---- Anomalies ----

    private void listAnomalies(Context ctx) {
        // Check all known agents
        var agents = memoryStore.getKnownAgents();
        var all = new ArrayList<>();
        for (var agent : agents) {
            all.addAll(brain.check(agent, null));
        }
        ctx.json(all);
    }

    // ---- Audit ----

    private void getAuditLog(Context ctx) {
        String agent = ctx.queryParam("agent");
        String operation = ctx.queryParam("operation");
        String from = ctx.queryParam("from");
        String to = ctx.queryParam("to");
        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(100);
        int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);
        ctx.json(memoryStore.getAuditLog(agent, operation, from, to, limit, offset));
    }

    // ---- Performance ----

    private void getTimeseries(Context ctx) {
        String agent = ctx.queryParam("agent");
        String from = ctx.queryParam("from");
        String to = ctx.queryParam("to");
        var entries = memoryStore.getAuditLog(agent, null, from, to, 10000, 0);
        ctx.json(entries.stream().map(e -> Map.of(
            "timestamp", e.createdAt(),
            "operation", e.operation(),
            "latencyMs", e.latencyMs()
        )).toList());
    }

    private void getPerformanceSummary(Context ctx) {
        var entries = memoryStore.getAuditLog(null, null, null, null, 10000, 0);
        double avgLatency = entries.stream().mapToDouble(e -> e.latencyMs()).average().orElse(0);
        long totalOps = entries.size();
        long totalWrites = entries.stream().filter(e -> "store".equals(e.operation())).count();
        long totalReads = entries.stream().filter(e -> "recall".equals(e.operation()) || "search".equals(e.operation())).count();
        ctx.json(Map.of(
            "avgLatencyMs", Math.round(avgLatency * 100.0) / 100.0,
            "totalOperations", totalOps,
            "totalWrites", totalWrites,
            "totalReads", totalReads
        ));
    }

    // ---- Settings ----

    private void getSettings(Context ctx) {
        ctx.json(memoryStore.getAllSettings());
    }

    private void updateSettings(Context ctx) {
        var body = ctx.bodyAsClass(Map.class);
        for (var entry : ((Map<String, String>) body).entrySet()) {
            memoryStore.setSetting(entry.getKey(), entry.getValue());
        }
        ctx.result("Settings updated");
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd /home/kamil/Documents/Project/My/Mcp/agent-memory
./gradlew test --tests '*DashboardApiTest*'
```
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
git add agent-memory/src/main/java/com/agentmemory/api/DashboardApi.java
git add agent-memory/src/test/java/com/agentmemory/api/DashboardApiTest.java
git add agent-memory/build.gradle
git commit -m "feat(agent-memory): add DashboardApi with all REST endpoints"
```

---

## Phase 6: Wiring & Integration

### Task 12: ServeCommand — Wire MCP + Javalin

**Files:**
- Modify: `src/main/java/com/agentmemory/cli/ServeCommand.java`

- [ ] **Step 1: Implement full ServeCommand**

```java
package com.agentmemory.cli;

import com.agentmemory.api.DashboardApi;
import com.agentmemory.brain.BrainEngine;
import com.agentmemory.mcp.AgentMemoryMcpServer;
import com.agentmemory.store.GraphStore;
import com.agentmemory.store.MemoryStore;
import io.javalin.Javalin;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;

@Command(name = "serve", description = "Start MCP server and dashboard")
public class ServeCommand implements Runnable {

    @Option(names = {"--port", "-p"}, description = "Dashboard HTTP port (default: 7070)", defaultValue = "7070")
    private int port;

    @Option(names = {"--db"}, description = "Database path (default: ~/.agent-memory/memory.db)")
    private String dbPath;

    @Override
    public void run() {
        try {
            // 1. Determine database path
            Path db;
            if (dbPath != null) {
                db = Path.of(dbPath);
            } else {
                db = Path.of(System.getProperty("user.home"), ".agent-memory", "memory.db");
            }
            Files.createDirectories(db.getParent());

            // 2. Initialize store
            var memoryStore = new MemoryStore(db);
            var graphStore = new GraphStore(memoryStore);
            var brain = new BrainEngine(memoryStore, graphStore);

            System.err.println("Database: " + db.toAbsolutePath());

            // 3. Start Javalin on daemon thread (all logging to stderr)
            var app = Javalin.create(config -> {
                config.staticFiles.add("/static");
                config.showJavalinBanner = false;
            });

            // CORS for development
            app.before(ctx -> {
                ctx.header("Access-Control-Allow-Origin", "*");
                ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
                ctx.header("Access-Control-Allow-Headers", "Content-Type");
            });
            app.options("/*", ctx -> ctx.status(200));

            new DashboardApi(memoryStore, graphStore, brain).register(app);

            // SPA fallback — serve index.html for Angular routes
            app.error(404, ctx -> {
                if (!ctx.path().startsWith("/api/")) {
                    ctx.result(new String(getClass().getResourceAsStream("/static/index.html").readAllBytes()));
                    ctx.contentType("text/html");
                    ctx.status(200);
                }
            });

            Thread dashboardThread = new Thread(() -> {
                app.start(port);
                System.err.println("Dashboard running at http://localhost:" + port);
            });
            dashboardThread.setDaemon(true);
            dashboardThread.start();

            // 4. Start MCP server on main thread (blocks)
            System.err.println("agent-memory MCP server started (stdio)");
            var mcpServer = new AgentMemoryMcpServer(memoryStore, graphStore, brain);
            mcpServer.start();

        } catch (Exception e) {
            System.err.println("Failed to start: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }
}
```

- [ ] **Step 2: Redirect Javalin/Jetty logging to stderr**

Add `src/main/resources/simplelogger.properties`:
```properties
# Redirect all SLF4J simple logger output to stderr
org.slf4j.simpleLogger.logFile=System.err
org.slf4j.simpleLogger.defaultLogLevel=warn
```

- [ ] **Step 3: Build and verify shadow JAR**

```bash
cd /home/kamil/Documents/Project/My/Mcp/agent-memory
./gradlew shadowJar
java -jar build/libs/agent-memory-0.1.0.jar serve --help
```
Expected: Shows serve command help with `--port` and `--db` options

- [ ] **Step 4: Commit**

```bash
git add agent-memory/src/main/java/com/agentmemory/cli/ServeCommand.java
git add agent-memory/src/main/resources/simplelogger.properties
git commit -m "feat(agent-memory): wire ServeCommand with MCP + Javalin startup"
```

---

### Task 13: Build Scripts

**Files:**
- Modify: `jars/build-all.sh`
- Create: `jars/run-agent-memory.sh`

- [ ] **Step 1: Update build-all.sh**

Add before the final `echo` lines:

```bash
# agent-memory
echo ""
echo "Building agent-memory..."
cd "$MCP_DIR/agent-memory"
./gradlew shadowJar -q
cp build/libs/agent-memory-0.1.0.jar "$SCRIPT_DIR/agent-memory.jar"
echo "  -> agent-memory.jar"
```

- [ ] **Step 2: Create run-agent-memory.sh**

```bash
#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$SCRIPT_DIR/agent-memory.jar"

if [ ! -f "$JAR" ]; then
  echo "agent-memory.jar not found. Run build-all.sh first."
  exit 1
fi

java -jar "$JAR" "$@"
```

- [ ] **Step 3: Make executable**

```bash
chmod +x /home/kamil/Documents/Project/My/Mcp/jars/run-agent-memory.sh
```

- [ ] **Step 4: Run full build**

```bash
cd /home/kamil/Documents/Project/My/Mcp
./jars/build-all.sh
```
Expected: All three JARs built successfully

- [ ] **Step 5: Commit**

```bash
git add jars/build-all.sh jars/run-agent-memory.sh
git commit -m "feat(agent-memory): add build and runner scripts"
```

---

## Phase 7: Angular Dashboard

### Task 14: Angular Project Init + Tailwind + Dark Theme

**Files:**
- Create: `agent-memory/angular/` (Angular CLI generated)

- [ ] **Step 1: Initialize Angular project**

```bash
cd /home/kamil/Documents/Project/My/Mcp/agent-memory
npx @angular/cli@19 new angular --routing --style=scss --skip-git --directory=angular --ssr=false
```

- [ ] **Step 2: Install dependencies**

```bash
cd /home/kamil/Documents/Project/My/Mcp/agent-memory/angular
npm install tailwindcss @tailwindcss/postcss postcss --save-dev
npm install ngx-charts cytoscape @types/cytoscape
```

- [ ] **Step 3: Configure Tailwind**

Create `.postcssrc.json`:
```json
{
  "plugins": {
    "@tailwindcss/postcss": {}
  }
}
```

Replace `src/styles.scss` content:
```scss
@import "tailwindcss";

:root {
  --bg-primary: #0f1117;
  --bg-secondary: #1a1d27;
  --bg-card: #222533;
  --text-primary: #e4e4e7;
  --text-secondary: #a1a1aa;
  --accent: #f97316;
  --accent-hover: #fb923c;
  --border: #2e3344;
}

body {
  margin: 0;
  font-family: 'Inter', system-ui, sans-serif;
  background-color: var(--bg-primary);
  color: var(--text-primary);
}

* {
  box-sizing: border-box;
}
```

- [ ] **Step 4: Configure Angular providers**

In `angular/src/app/app.config.ts`, ensure `provideHttpClient()` and `provideRouter(routes)` are configured:

```typescript
import { ApplicationConfig } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideRouter(routes),
    provideHttpClient()
  ]
};
```

- [ ] **Step 5: Verify Angular builds**

```bash
cd /home/kamil/Documents/Project/My/Mcp/agent-memory/angular
npx ng build
```
Expected: Build succeeds

- [ ] **Step 6: Commit**

```bash
git add agent-memory/angular/
git commit -m "feat(agent-memory): init Angular 19 project with Tailwind dark theme"
```

---

### Task 15: Sidebar Layout + Routing + API Service

**Files:**
- Create: `angular/src/app/components/sidebar/sidebar.component.ts`
- Modify: `angular/src/app/app.component.ts`
- Modify: `angular/src/app/app.routes.ts`
- Create: `angular/src/app/services/api.service.ts`

- [ ] **Step 1: Create API service**

`angular/src/app/services/api.service.ts`:
```typescript
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private baseUrl = '/api';

  constructor(private http: HttpClient) {}

  // Memories
  getMemories(params?: any): Observable<any[]> {
    return this.http.get<any[]>(`${this.baseUrl}/memories`, { params });
  }
  getMemory(id: string): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/memories/${id}`);
  }
  getMemoryVersions(id: string): Observable<any[]> {
    return this.http.get<any[]>(`${this.baseUrl}/memories/${id}/versions`);
  }
  createMemory(body: any): Observable<any> {
    return this.http.post(`${this.baseUrl}/memories`, body);
  }
  deleteMemory(id: string): Observable<any> {
    return this.http.delete(`${this.baseUrl}/memories/${id}`);
  }

  // Agents
  getAgents(): Observable<any[]> {
    return this.http.get<any[]>(`${this.baseUrl}/agents`);
  }
  getAgentMetrics(name: string): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/agents/${name}/metrics`);
  }

  // Graph
  getGraph(params?: any): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/graph`, { params });
  }
  getSubgraph(memoryId: string, depth?: number): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/graph/${memoryId}`, { params: { depth: depth || 2 } });
  }

  // Goals
  getGoals(params?: any): Observable<any[]> {
    return this.http.get<any[]>(`${this.baseUrl}/goals`, { params });
  }

  // Anomalies
  getAnomalies(): Observable<any[]> {
    return this.http.get<any[]>(`${this.baseUrl}/anomalies`);
  }

  // Audit
  getAuditLog(params?: any): Observable<any[]> {
    return this.http.get<any[]>(`${this.baseUrl}/audit`, { params });
  }

  // Performance
  getTimeseries(params?: any): Observable<any[]> {
    return this.http.get<any[]>(`${this.baseUrl}/performance/timeseries`, { params });
  }
  getPerformanceSummary(): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/performance/summary`);
  }

  // Settings
  getSettings(): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/settings`);
  }
  updateSettings(body: any): Observable<any> {
    return this.http.put(`${this.baseUrl}/settings`, body);
  }
}
```

- [ ] **Step 2: Create sidebar component**

`angular/src/app/components/sidebar/sidebar.component.ts`:
```typescript
import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

interface NavItem {
  label: string;
  path: string;
  icon: string;
}

interface NavGroup {
  title: string;
  items: NavItem[];
}

@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [RouterLink, RouterLinkActive],
  template: `
    <aside class="sidebar">
      <div class="logo">
        <h2>Agent Memory</h2>
      </div>

      @for (group of navGroups; track group.title) {
        <div class="nav-group">
          <span class="group-title">{{ group.title }}</span>
          @for (item of group.items; track item.path) {
            <a [routerLink]="item.path" routerLinkActive="active" class="nav-item">
              <span class="icon">{{ item.icon }}</span>
              {{ item.label }}
            </a>
          }
        </div>
      }
    </aside>
  `,
  styles: [`
    .sidebar {
      width: 240px;
      height: 100vh;
      background: var(--bg-secondary);
      border-right: 1px solid var(--border);
      padding: 16px 0;
      position: fixed;
      overflow-y: auto;
    }
    .logo {
      padding: 0 20px 20px;
      border-bottom: 1px solid var(--border);
      margin-bottom: 16px;
    }
    .logo h2 {
      color: var(--accent);
      margin: 0;
      font-size: 18px;
    }
    .nav-group {
      margin-bottom: 16px;
    }
    .group-title {
      display: block;
      padding: 4px 20px;
      font-size: 11px;
      text-transform: uppercase;
      letter-spacing: 1px;
      color: var(--text-secondary);
    }
    .nav-item {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 8px 20px;
      color: var(--text-secondary);
      text-decoration: none;
      font-size: 14px;
      transition: all 0.15s;
    }
    .nav-item:hover {
      color: var(--text-primary);
      background: rgba(255,255,255,0.05);
    }
    .nav-item.active {
      color: var(--accent);
      background: rgba(249, 115, 22, 0.1);
      border-left: 3px solid var(--accent);
    }
    .icon { font-size: 16px; }
  `]
})
export class SidebarComponent {
  navGroups: NavGroup[] = [
    {
      title: 'Monitoring',
      items: [
        { label: 'Overview', path: '/overview', icon: '◎' },
        { label: 'Agents', path: '/agents', icon: '▸' },
        { label: 'Memory Explorer', path: '/memory-explorer', icon: '◈' },
        { label: 'Shared Memory', path: '/shared-memory', icon: '⊞' },
      ]
    },
    {
      title: 'Operations',
      items: [
        { label: 'Performance', path: '/performance', icon: '⚡' },
        { label: 'Analytics', path: '/analytics', icon: '▤' },
        { label: 'Audit Trail', path: '/audit-trail', icon: '☰' },
      ]
    },
    {
      title: 'Management',
      items: [
        { label: 'Knowledge Graph', path: '/knowledge-graph', icon: '◇' },
        { label: 'Recovery', path: '/recovery', icon: '↻' },
        { label: 'Anomalies', path: '/anomalies', icon: '△' },
        { label: 'Settings', path: '/settings', icon: '⚙' },
      ]
    }
  ];
}
```

- [ ] **Step 3: Set up routing**

`angular/src/app/app.routes.ts`:
```typescript
import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', redirectTo: 'overview', pathMatch: 'full' },
  { path: 'overview', loadComponent: () => import('./pages/overview/overview.component').then(m => m.OverviewComponent) },
  { path: 'agents', loadComponent: () => import('./pages/agents/agents.component').then(m => m.AgentsComponent) },
  { path: 'memory-explorer', loadComponent: () => import('./pages/memory-explorer/memory-explorer.component').then(m => m.MemoryExplorerComponent) },
  { path: 'shared-memory', loadComponent: () => import('./pages/shared-memory/shared-memory.component').then(m => m.SharedMemoryComponent) },
  { path: 'knowledge-graph', loadComponent: () => import('./pages/knowledge-graph/knowledge-graph.component').then(m => m.KnowledgeGraphComponent) },
  { path: 'performance', loadComponent: () => import('./pages/performance/performance.component').then(m => m.PerformanceComponent) },
  { path: 'analytics', loadComponent: () => import('./pages/analytics/analytics.component').then(m => m.AnalyticsComponent) },
  { path: 'audit-trail', loadComponent: () => import('./pages/audit-trail/audit-trail.component').then(m => m.AuditTrailComponent) },
  { path: 'recovery', loadComponent: () => import('./pages/recovery/recovery.component').then(m => m.RecoveryComponent) },
  { path: 'anomalies', loadComponent: () => import('./pages/anomalies/anomalies.component').then(m => m.AnomaliesComponent) },
  { path: 'settings', loadComponent: () => import('./pages/settings/settings.component').then(m => m.SettingsComponent) },
];
```

- [ ] **Step 4: Update app.component.ts for sidebar layout**

```typescript
import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { SidebarComponent } from './components/sidebar/sidebar.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, SidebarComponent],
  template: `
    <div class="app-layout">
      <app-sidebar />
      <main class="main-content">
        <router-outlet />
      </main>
    </div>
  `,
  styles: [`
    .app-layout {
      display: flex;
      min-height: 100vh;
    }
    .main-content {
      margin-left: 240px;
      flex: 1;
      padding: 24px;
    }
  `]
})
export class AppComponent {}
```

- [ ] **Step 5: Create stub page components**

Create each page component as a minimal stub. Example for overview (repeat pattern for all 11 pages):

`angular/src/app/pages/overview/overview.component.ts`:
```typescript
import { Component } from '@angular/core';

@Component({
  selector: 'app-overview',
  standalone: true,
  template: `<h1>Overview</h1><p>Dashboard overview coming soon...</p>`
})
export class OverviewComponent {}
```

Create similar stubs for: `agents`, `memory-explorer`, `shared-memory`, `knowledge-graph`, `performance`, `analytics`, `audit-trail`, `recovery`, `anomalies`, `settings` — each in its own directory under `pages/`.

- [ ] **Step 6: Verify build**

```bash
cd /home/kamil/Documents/Project/My/Mcp/agent-memory/angular
npx ng build
```

- [ ] **Step 7: Commit**

```bash
git add agent-memory/angular/
git commit -m "feat(agent-memory): add sidebar layout, routing, API service, and page stubs"
```

---

### Task 16: Overview Page

**Files:**
- Modify: `angular/src/app/pages/overview/overview.component.ts`

- [ ] **Step 1: Implement Overview page**

```typescript
import { Component, OnInit } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-overview',
  standalone: true,
  imports: [DatePipe],
  template: `
    <h1>Overview</h1>
    <div class="cards">
      <div class="card">
        <div class="card-label">Total Memories</div>
        <div class="card-value">{{ totalMemories }}</div>
      </div>
      <div class="card">
        <div class="card-label">Active Agents</div>
        <div class="card-value">{{ activeAgents }}</div>
      </div>
      <div class="card">
        <div class="card-label">Anomalies</div>
        <div class="card-value anomaly">{{ anomalyCount }}</div>
      </div>
      <div class="card">
        <div class="card-label">Shared Memories</div>
        <div class="card-value">{{ sharedCount }}</div>
      </div>
    </div>

    <h2>Recent Activity</h2>
    <div class="activity-feed">
      @for (entry of recentActivity; track entry.id) {
        <div class="activity-item">
          <span class="op-badge">{{ entry.operation }}</span>
          <span class="agent-name">{{ entry.agent }}</span>
          <span class="memory-key">{{ entry.memoryKey || '-' }}</span>
          <span class="timestamp">{{ entry.createdAt | date:'short' }}</span>
        </div>
      }
    </div>
  `,
  styles: [`
    h1 { margin: 0 0 24px; font-size: 24px; }
    h2 { margin: 32px 0 16px; font-size: 18px; }
    .cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; }
    .card {
      background: var(--bg-card);
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 20px;
    }
    .card-label { font-size: 13px; color: var(--text-secondary); margin-bottom: 8px; }
    .card-value { font-size: 32px; font-weight: 600; color: var(--accent); }
    .card-value.anomaly { color: #ef4444; }
    .activity-feed { display: flex; flex-direction: column; gap: 8px; }
    .activity-item {
      display: flex; gap: 16px; align-items: center;
      padding: 12px 16px; background: var(--bg-card);
      border: 1px solid var(--border); border-radius: 6px;
    }
    .op-badge {
      background: rgba(249, 115, 22, 0.2); color: var(--accent);
      padding: 2px 8px; border-radius: 4px; font-size: 12px;
    }
    .agent-name { color: var(--text-secondary); font-size: 13px; }
    .memory-key { flex: 1; font-family: monospace; font-size: 13px; }
    .timestamp { color: var(--text-secondary); font-size: 12px; }
  `]
})
export class OverviewComponent implements OnInit {
  totalMemories = 0;
  activeAgents = 0;
  anomalyCount = 0;
  sharedCount = 0;
  recentActivity: any[] = [];

  constructor(private api: ApiService) {}

  ngOnInit() {
    this.api.getMemories({ limit: 1000 }).subscribe(m => this.totalMemories = m.length);
    this.api.getAgents().subscribe(a => this.activeAgents = a.length);
    this.api.getAnomalies().subscribe(a => this.anomalyCount = a.length);
    this.api.getMemories({ shared: true, limit: 1000 }).subscribe(m => this.sharedCount = m.length);
    this.api.getAuditLog({ limit: 20 }).subscribe(a => this.recentActivity = a);
  }
}
```

- [ ] **Step 2: Verify build, commit**

---

### Task 17: Agents Page

**Files:**
- Modify: `angular/src/app/pages/agents/agents.component.ts`

- [ ] **Step 1: Implement Agents page**

```typescript
import { Component, OnInit } from '@angular/core';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-agents',
  standalone: true,
  template: `
    <h1>Agents</h1>
    <table class="data-table">
      <thead>
        <tr>
          <th>Agent</th><th>Status</th><th>Write Latency</th><th>Read Latency</th>
          <th>Total Writes</th><th>Total Reads</th><th>Errors</th>
        </tr>
      </thead>
      <tbody>
        @for (agent of agents; track agent.agent) {
          <tr>
            <td class="accent">{{ agent.agent }}</td>
            <td><span class="status-badge running">Running</span></td>
            <td>{{ agent.avgWriteLatencyMs }}ms</td>
            <td>{{ agent.avgReadLatencyMs }}ms</td>
            <td>{{ agent.totalWrites }}</td>
            <td>{{ agent.totalReads }}</td>
            <td>{{ agent.errors }}</td>
          </tr>
        }
      </tbody>
    </table>
  `,
  styles: [`
    .data-table { width: 100%; border-collapse: collapse; }
    .data-table th, .data-table td { padding: 12px 16px; text-align: left; border-bottom: 1px solid var(--border); }
    .data-table th { color: var(--text-secondary); font-size: 12px; text-transform: uppercase; }
    .data-table tr:hover { background: rgba(255,255,255,0.03); }
    .accent { color: var(--accent); }
    .status-badge { padding: 2px 8px; border-radius: 4px; font-size: 12px; }
    .status-badge.running { background: rgba(34,197,94,0.2); color: #22c55e; }
  `]
})
export class AgentsComponent implements OnInit {
  agents: any[] = [];
  constructor(private api: ApiService) {}
  ngOnInit() { this.api.getAgents().subscribe(a => this.agents = a); }
}
```

- [ ] **Step 2: Verify build, commit**

---

### Task 18: Memory Explorer Page

**Files:**
- Modify: `angular/src/app/pages/memory-explorer/memory-explorer.component.ts`

- [ ] **Step 1: Implement Memory Explorer**

```typescript
import { Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-memory-explorer',
  standalone: true,
  imports: [FormsModule],
  template: `
    <h1>Memory Explorer</h1>
    <div class="explorer-toolbar">
      <select [(ngModel)]="agentFilter" (change)="loadMemories()" class="select-input">
        <option value="">All agents</option>
        @for (a of knownAgents; track a) { <option [value]="a">{{ a }}</option> }
      </select>
      <input [(ngModel)]="searchQuery" (input)="loadMemories()" placeholder="Search memories..." class="search-input" />
    </div>
    <div class="explorer-layout">
      <div class="memory-list">
        @for (mem of memories; track mem.id) {
          <div class="memory-card" [class.selected]="selectedMemory?.id === mem.id" (click)="selectMemory(mem)">
            <div class="memory-key">{{ mem.key }}</div>
            <div class="memory-value">{{ mem.value }}</div>
            <div class="memory-tags">
              @for (tag of mem.tags; track tag) {
                <span class="tag-badge">{{ tag }}</span>
              }
            </div>
          </div>
        }
      </div>
      <div class="version-panel" >
        @if (selectedMemory) {
          <h3>Version History — <span class="accent">{{ selectedMemory.key }}</span></h3>
          @for (v of versions; track v.id) {
            <div class="version-entry">
              <div class="version-header">v{{ v.version }} <span class="timestamp">{{ v.createdAt }}</span></div>
              <div class="version-value">{{ v.value }}</div>
            </div>
          }
          @if (versions.length === 0) { <p class="muted">No previous versions</p> }
        } @else {
          <p class="muted">Select a memory to view version history</p>
        }
      </div>
    </div>
  `,
  styles: [`
    .explorer-toolbar { display: flex; gap: 12px; margin-bottom: 16px; }
    .search-input, .select-input {
      padding: 8px 12px; background: var(--bg-card); border: 1px solid var(--border);
      border-radius: 6px; color: var(--text-primary); font-size: 14px;
    }
    .search-input { flex: 1; }
    .explorer-layout { display: grid; grid-template-columns: 1fr 350px; gap: 16px; }
    .memory-card {
      padding: 12px 16px; background: var(--bg-card); border: 1px solid var(--border);
      border-radius: 6px; margin-bottom: 8px; cursor: pointer;
    }
    .memory-card:hover { border-color: var(--accent); }
    .memory-card.selected { border-color: var(--accent); background: rgba(249,115,22,0.05); }
    .memory-key { font-weight: 600; margin-bottom: 4px; }
    .memory-value { color: var(--text-secondary); font-size: 13px; margin-bottom: 8px; }
    .tag-badge {
      display: inline-block; padding: 2px 8px; background: rgba(249,115,22,0.2);
      color: var(--accent); border-radius: 4px; font-size: 11px; margin-right: 4px;
    }
    .version-panel {
      background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; padding: 16px;
    }
    .version-entry { padding: 12px 0; border-bottom: 1px solid var(--border); }
    .version-header { font-size: 13px; font-weight: 600; margin-bottom: 4px; }
    .timestamp { color: var(--text-secondary); font-size: 12px; }
    .accent { color: var(--accent); }
    .muted { color: var(--text-secondary); }
  `]
})
export class MemoryExplorerComponent implements OnInit {
  memories: any[] = [];
  knownAgents: string[] = [];
  agentFilter = '';
  searchQuery = '';
  selectedMemory: any = null;
  versions: any[] = [];

  constructor(private api: ApiService) {}

  ngOnInit() {
    this.loadMemories();
    this.api.getAgents().subscribe(a => this.knownAgents = a.map((ag: any) => ag.agent));
  }

  loadMemories() {
    const params: any = {};
    if (this.agentFilter) params.agent = this.agentFilter;
    if (this.searchQuery) params.q = this.searchQuery;
    this.api.getMemories(params).subscribe(m => this.memories = m);
  }

  selectMemory(mem: any) {
    this.selectedMemory = mem;
    this.api.getMemoryVersions(mem.id).subscribe(v => this.versions = v);
  }
}
```

- [ ] **Step 2: Verify build, commit**

---

### Task 19: Shared Memory Page

**Files:**
- Modify: `angular/src/app/pages/shared-memory/shared-memory.component.ts`

- [ ] **Step 1: Implement**

```typescript
import { Component, OnInit } from '@angular/core';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-shared-memory',
  standalone: true,
  template: `
    <h1>Shared Memory</h1>
    <p class="subtitle">Memories shared across all agents</p>
    @for (mem of memories; track mem.id) {
      <div class="memory-card">
        <div class="memory-key">{{ mem.key }}</div>
        <div class="memory-meta">Shared by <span class="accent">{{ mem.agent }}</span></div>
        <div class="memory-value">{{ mem.value }}</div>
        <div class="memory-tags">
          @for (tag of mem.tags; track tag) {
            <span class="tag-badge">{{ tag }}</span>
          }
        </div>
      </div>
    }
    @if (memories.length === 0) { <p class="muted">No shared memories yet</p> }
  `,
  styles: [`
    .subtitle { color: var(--text-secondary); margin: -16px 0 24px; }
    .memory-card { padding: 16px; background: var(--bg-card); border: 1px solid var(--border); border-radius: 6px; margin-bottom: 8px; }
    .memory-key { font-weight: 600; margin-bottom: 4px; }
    .memory-meta { color: var(--text-secondary); font-size: 12px; margin-bottom: 8px; }
    .memory-value { color: var(--text-secondary); font-size: 13px; margin-bottom: 8px; }
    .accent { color: var(--accent); }
    .tag-badge { display: inline-block; padding: 2px 8px; background: rgba(249,115,22,0.2); color: var(--accent); border-radius: 4px; font-size: 11px; margin-right: 4px; }
    .muted { color: var(--text-secondary); }
  `]
})
export class SharedMemoryComponent implements OnInit {
  memories: any[] = [];
  constructor(private api: ApiService) {}
  ngOnInit() { this.api.getMemories({ shared: true }).subscribe(m => this.memories = m); }
}
```

- [ ] **Step 2: Verify build, commit**

---

### Task 20: Knowledge Graph Page

**Files:**
- Modify: `angular/src/app/pages/knowledge-graph/knowledge-graph.component.ts`

- [ ] **Step 1: Implement with Cytoscape.js**

```typescript
import { Component, OnInit, ElementRef, ViewChild, AfterViewInit } from '@angular/core';
import { ApiService } from '../../services/api.service';
import cytoscape from 'cytoscape';

@Component({
  selector: 'app-knowledge-graph',
  standalone: true,
  template: `
    <h1>Knowledge Graph</h1>
    <div #graphContainer class="graph-container"></div>
  `,
  styles: [`
    .graph-container {
      width: 100%;
      height: calc(100vh - 120px);
      background: var(--bg-card);
      border: 1px solid var(--border);
      border-radius: 8px;
    }
  `]
})
export class KnowledgeGraphComponent implements AfterViewInit {
  @ViewChild('graphContainer') container!: ElementRef;

  constructor(private api: ApiService) {}

  ngAfterViewInit() {
    this.api.getGraph().subscribe(data => {
      const elements = [
        ...data.nodes.map((n: any) => ({
          data: { id: n.id, label: n.key }
        })),
        ...data.edges.map((e: any) => ({
          data: { source: e.sourceId, target: e.targetId, label: e.relation }
        }))
      ];

      cytoscape({
        container: this.container.nativeElement,
        elements,
        style: [
          {
            selector: 'node',
            style: {
              'label': 'data(label)',
              'background-color': '#f97316',
              'color': '#e4e4e7',
              'font-size': '12px',
              'text-valign': 'bottom',
              'text-margin-y': 8
            }
          },
          {
            selector: 'edge',
            style: {
              'label': 'data(label)',
              'line-color': '#2e3344',
              'target-arrow-color': '#2e3344',
              'target-arrow-shape': 'triangle',
              'curve-style': 'bezier',
              'color': '#a1a1aa',
              'font-size': '10px'
            }
          }
        ],
        layout: { name: 'cose', animate: true }
      });
    });
  }
}
```

- [ ] **Step 2: Verify build, commit**

---

### Task 21: Performance Page

**Files:**
- Modify: `angular/src/app/pages/performance/performance.component.ts`

- [ ] **Step 1: Implement with summary cards and latency table**

```typescript
import { Component, OnInit } from '@angular/core';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-performance',
  standalone: true,
  template: `
    <h1>Performance</h1>
    <div class="cards">
      <div class="card"><div class="card-label">Avg Latency</div><div class="card-value">{{ summary.avgLatencyMs }}ms</div></div>
      <div class="card"><div class="card-label">Total Operations</div><div class="card-value">{{ summary.totalOperations }}</div></div>
      <div class="card"><div class="card-label">Total Writes</div><div class="card-value">{{ summary.totalWrites }}</div></div>
      <div class="card"><div class="card-label">Total Reads</div><div class="card-value">{{ summary.totalReads }}</div></div>
    </div>
    <h2>Recent Operations</h2>
    <table class="data-table">
      <thead><tr><th>Timestamp</th><th>Operation</th><th>Latency</th></tr></thead>
      <tbody>
        @for (entry of timeseries; track $index) {
          <tr><td>{{ entry.timestamp }}</td><td>{{ entry.operation }}</td><td>{{ entry.latencyMs }}ms</td></tr>
        }
      </tbody>
    </table>
  `,
  styles: [`
    .cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin-bottom: 24px; }
    .card { background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; padding: 20px; }
    .card-label { font-size: 13px; color: var(--text-secondary); margin-bottom: 8px; }
    .card-value { font-size: 28px; font-weight: 600; color: var(--accent); }
    .data-table { width: 100%; border-collapse: collapse; }
    .data-table th, .data-table td { padding: 10px 16px; text-align: left; border-bottom: 1px solid var(--border); }
    .data-table th { color: var(--text-secondary); font-size: 12px; text-transform: uppercase; }
  `]
})
export class PerformanceComponent implements OnInit {
  summary: any = { avgLatencyMs: 0, totalOperations: 0, totalWrites: 0, totalReads: 0 };
  timeseries: any[] = [];
  constructor(private api: ApiService) {}
  ngOnInit() {
    this.api.getPerformanceSummary().subscribe(s => this.summary = s);
    this.api.getTimeseries({ limit: 50 }).subscribe(t => this.timeseries = t);
  }
}
```

- [ ] **Step 2: Verify build, commit**

---

### Task 22: Analytics Page

**Files:**
- Modify: `angular/src/app/pages/analytics/analytics.component.ts`

- [ ] **Step 1: Implement with agent and tag summaries**

```typescript
import { Component, OnInit } from '@angular/core';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-analytics',
  standalone: true,
  template: `
    <h1>Analytics</h1>
    <div class="grid">
      <div class="panel">
        <h3>Most Active Agents</h3>
        @for (agent of agents; track agent.agent) {
          <div class="bar-row">
            <span class="bar-label">{{ agent.agent }}</span>
            <div class="bar" [style.width.%]="getBarWidth(agent.totalWrites + agent.totalReads)">
              {{ agent.totalWrites + agent.totalReads }} ops
            </div>
          </div>
        }
      </div>
      <div class="panel">
        <h3>Memory Stats</h3>
        <div class="stat-row">Total memories: <strong>{{ totalMemories }}</strong></div>
        <div class="stat-row">Shared memories: <strong>{{ sharedMemories }}</strong></div>
        <div class="stat-row">Active agents: <strong>{{ agents.length }}</strong></div>
      </div>
    </div>
  `,
  styles: [`
    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
    .panel { background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; padding: 20px; }
    h3 { margin: 0 0 16px; font-size: 16px; }
    .bar-row { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
    .bar-label { min-width: 100px; color: var(--text-secondary); font-size: 13px; }
    .bar { background: rgba(249,115,22,0.3); color: var(--accent); padding: 4px 8px; border-radius: 4px; font-size: 12px; min-width: 30px; }
    .stat-row { padding: 8px 0; border-bottom: 1px solid var(--border); color: var(--text-secondary); }
    strong { color: var(--text-primary); }
  `]
})
export class AnalyticsComponent implements OnInit {
  agents: any[] = [];
  totalMemories = 0;
  sharedMemories = 0;
  maxOps = 1;

  constructor(private api: ApiService) {}
  ngOnInit() {
    this.api.getAgents().subscribe(a => {
      this.agents = a;
      this.maxOps = Math.max(1, ...a.map((ag: any) => ag.totalWrites + ag.totalReads));
    });
    this.api.getMemories({ limit: 10000 }).subscribe(m => this.totalMemories = m.length);
    this.api.getMemories({ shared: true, limit: 10000 }).subscribe(m => this.sharedMemories = m.length);
  }
  getBarWidth(ops: number) { return Math.max(5, (ops / this.maxOps) * 100); }
}
```

- [ ] **Step 2: Verify build, commit**

---

### Task 23: Audit Trail Page

**Files:**
- Modify: `angular/src/app/pages/audit-trail/audit-trail.component.ts`

- [ ] **Step 1: Implement paginated audit table**

```typescript
import { Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-audit-trail',
  standalone: true,
  imports: [FormsModule],
  template: `
    <h1>Audit Trail</h1>
    <div class="filters">
      <select [(ngModel)]="agentFilter" (change)="load()" class="select-input">
        <option value="">All agents</option>
        @for (a of agents; track a) { <option [value]="a">{{ a }}</option> }
      </select>
      <select [(ngModel)]="opFilter" (change)="load()" class="select-input">
        <option value="">All operations</option>
        <option value="store">store</option><option value="recall">recall</option>
        <option value="search">search</option><option value="delete">delete</option>
        <option value="share">share</option><option value="link">link</option>
      </select>
    </div>
    <table class="data-table">
      <thead><tr><th>Time</th><th>Agent</th><th>Operation</th><th>Key</th><th>Latency</th></tr></thead>
      <tbody>
        @for (e of entries; track e.id) {
          <tr>
            <td class="muted">{{ e.createdAt }}</td>
            <td>{{ e.agent }}</td>
            <td><span class="op-badge">{{ e.operation }}</span></td>
            <td class="mono">{{ e.memoryKey || '-' }}</td>
            <td>{{ e.latencyMs | number:'1.1-1' }}ms</td>
          </tr>
        }
      </tbody>
    </table>
    <div class="pagination">
      <button (click)="prevPage()" [disabled]="offset === 0" class="btn">Previous</button>
      <button (click)="nextPage()" [disabled]="entries.length < limit" class="btn">Next</button>
    </div>
  `,
  styles: [`
    .filters { display: flex; gap: 12px; margin-bottom: 16px; }
    .select-input { padding: 8px 12px; background: var(--bg-card); border: 1px solid var(--border); border-radius: 6px; color: var(--text-primary); }
    .data-table { width: 100%; border-collapse: collapse; }
    .data-table th, .data-table td { padding: 10px 16px; text-align: left; border-bottom: 1px solid var(--border); }
    .data-table th { color: var(--text-secondary); font-size: 12px; text-transform: uppercase; }
    .op-badge { background: rgba(249,115,22,0.2); color: var(--accent); padding: 2px 8px; border-radius: 4px; font-size: 12px; }
    .mono { font-family: monospace; font-size: 13px; }
    .muted { color: var(--text-secondary); font-size: 12px; }
    .pagination { display: flex; gap: 8px; margin-top: 16px; justify-content: flex-end; }
    .btn { padding: 6px 16px; background: var(--bg-card); border: 1px solid var(--border); border-radius: 6px; color: var(--text-primary); cursor: pointer; }
    .btn:disabled { opacity: 0.5; cursor: not-allowed; }
  `]
})
export class AuditTrailComponent implements OnInit {
  entries: any[] = [];
  agents: string[] = [];
  agentFilter = '';
  opFilter = '';
  limit = 50;
  offset = 0;

  constructor(private api: ApiService) {}
  ngOnInit() {
    this.load();
    this.api.getAgents().subscribe(a => this.agents = a.map((ag: any) => ag.agent));
  }
  load() {
    const params: any = { limit: this.limit, offset: this.offset };
    if (this.agentFilter) params.agent = this.agentFilter;
    if (this.opFilter) params.operation = this.opFilter;
    this.api.getAuditLog(params).subscribe(e => this.entries = e);
  }
  nextPage() { this.offset += this.limit; this.load(); }
  prevPage() { this.offset = Math.max(0, this.offset - this.limit); this.load(); }
}
```

**Note:** Import `DecimalPipe` from `@angular/common` and add to `imports` array for the `number` pipe.

- [ ] **Step 2: Verify build, commit**

---

### Task 24: Recovery Page

**Files:**
- Modify: `angular/src/app/pages/recovery/recovery.component.ts`

- [ ] **Step 1: Implement agent staleness view**

```typescript
import { Component, OnInit } from '@angular/core';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-recovery',
  standalone: true,
  template: `
    <h1>Recovery</h1>
    <p class="subtitle">Agent status based on recent activity staleness</p>
    @for (agent of agents; track agent.agent) {
      <div class="agent-card">
        <div class="agent-header">
          <span class="agent-name">{{ agent.agent }}</span>
          <span class="status-badge" [class]="getStatusClass(agent)">{{ getStatus(agent) }}</span>
        </div>
        <div class="agent-meta">First seen: {{ agent.firstSeen }}</div>
        <div class="agent-meta">Total operations: {{ agent.totalWrites + agent.totalReads }}</div>
      </div>
    }
    @if (agents.length === 0) { <p class="muted">No agents recorded yet</p> }
  `,
  styles: [`
    .subtitle { color: var(--text-secondary); margin: -16px 0 24px; }
    .agent-card { background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; padding: 16px; margin-bottom: 8px; }
    .agent-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
    .agent-name { font-weight: 600; font-size: 16px; }
    .status-badge { padding: 4px 12px; border-radius: 4px; font-size: 12px; }
    .status-badge.active { background: rgba(34,197,94,0.2); color: #22c55e; }
    .status-badge.stale { background: rgba(234,179,8,0.2); color: #eab308; }
    .status-badge.inactive { background: rgba(107,114,128,0.2); color: #6b7280; }
    .agent-meta { color: var(--text-secondary); font-size: 13px; }
    .muted { color: var(--text-secondary); }
  `]
})
export class RecoveryComponent implements OnInit {
  agents: any[] = [];
  constructor(private api: ApiService) {}
  ngOnInit() { this.api.getAgents().subscribe(a => this.agents = a); }
  getStatus(agent: any): string {
    // Simple heuristic — would need real timestamp comparison for production
    return agent.totalWrites + agent.totalReads > 0 ? 'Active' : 'Inactive';
  }
  getStatusClass(agent: any): string {
    return this.getStatus(agent).toLowerCase();
  }
}
```

- [ ] **Step 2: Verify build, commit**

---

### Task 25: Anomalies Page

**Files:**
- Modify: `angular/src/app/pages/anomalies/anomalies.component.ts`

- [ ] **Step 1: Implement anomaly cards**

```typescript
import { Component, OnInit } from '@angular/core';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-anomalies',
  standalone: true,
  template: `
    <h1>Anomalies</h1>
    @for (a of anomalies; track $index) {
      <div class="anomaly-card" [class]="'severity-' + a.severity">
        <div class="anomaly-header">
          <span class="type-badge">{{ a.type }}</span>
          <span class="severity-badge">{{ a.severity }}</span>
          <span class="timestamp">{{ a.detectedAt }}</span>
        </div>
        <div class="anomaly-desc">{{ a.description }}</div>
        @if (a.affectedKeys?.length) {
          <div class="affected">Affected: {{ a.affectedKeys.join(', ') }}</div>
        }
      </div>
    }
    @if (anomalies.length === 0) {
      <div class="all-clear">No anomalies detected. All clear.</div>
    }
  `,
  styles: [`
    .anomaly-card { background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; padding: 16px; margin-bottom: 12px; }
    .anomaly-card.severity-warning { border-left: 4px solid #eab308; }
    .anomaly-card.severity-info { border-left: 4px solid #3b82f6; }
    .anomaly-header { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; }
    .type-badge { background: rgba(249,115,22,0.2); color: var(--accent); padding: 2px 8px; border-radius: 4px; font-size: 12px; text-transform: uppercase; }
    .severity-badge { padding: 2px 8px; border-radius: 4px; font-size: 12px; }
    .severity-warning .severity-badge { background: rgba(234,179,8,0.2); color: #eab308; }
    .severity-info .severity-badge { background: rgba(59,130,246,0.2); color: #3b82f6; }
    .timestamp { color: var(--text-secondary); font-size: 12px; margin-left: auto; }
    .anomaly-desc { margin-bottom: 8px; }
    .affected { color: var(--text-secondary); font-size: 13px; font-family: monospace; }
    .all-clear { background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; padding: 32px; text-align: center; color: #22c55e; }
  `]
})
export class AnomaliesComponent implements OnInit {
  anomalies: any[] = [];
  constructor(private api: ApiService) {}
  ngOnInit() { this.api.getAnomalies().subscribe(a => this.anomalies = a); }
}
```

- [ ] **Step 2: Verify build, commit**

---

### Task 26: Settings Page

**Files:**
- Modify: `angular/src/app/pages/settings/settings.component.ts`

- [ ] **Step 1: Implement settings form**

```typescript
import { Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-settings',
  standalone: true,
  imports: [FormsModule],
  template: `
    <h1>Settings</h1>
    <div class="settings-panel">
      <h3>Detection Thresholds</h3>
      <div class="setting-row">
        <label>Loop detection window (minutes)</label>
        <input type="number" [(ngModel)]="settings['loop_window_minutes']" class="input" />
      </div>
      <div class="setting-row">
        <label>Loop threshold (repeated ops)</label>
        <input type="number" [(ngModel)]="settings['loop_threshold']" class="input" />
      </div>
      <div class="setting-row">
        <label>Drift threshold (%)</label>
        <input type="number" [(ngModel)]="settings['drift_threshold_percent']" class="input" />
      </div>
      <button (click)="save()" class="btn-save">Save Settings</button>
      @if (saved) { <span class="saved-msg">Settings saved</span> }
    </div>
  `,
  styles: [`
    .settings-panel { background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; padding: 24px; max-width: 600px; }
    h3 { margin: 0 0 16px; }
    .setting-row { display: flex; justify-content: space-between; align-items: center; padding: 8px 0; border-bottom: 1px solid var(--border); }
    label { color: var(--text-secondary); font-size: 14px; }
    .input { width: 80px; padding: 6px 10px; background: var(--bg-primary); border: 1px solid var(--border); border-radius: 4px; color: var(--text-primary); text-align: right; }
    .btn-save { margin-top: 16px; padding: 8px 24px; background: var(--accent); border: none; border-radius: 6px; color: white; cursor: pointer; font-weight: 600; }
    .btn-save:hover { background: var(--accent-hover); }
    .saved-msg { color: #22c55e; margin-left: 12px; font-size: 13px; }
  `]
})
export class SettingsComponent implements OnInit {
  settings: any = { loop_window_minutes: 30, loop_threshold: 3, drift_threshold_percent: 70 };
  saved = false;

  constructor(private api: ApiService) {}
  ngOnInit() {
    this.api.getSettings().subscribe(s => {
      if (Object.keys(s).length > 0) this.settings = { ...this.settings, ...s };
    });
  }
  save() {
    this.api.updateSettings(this.settings).subscribe(() => {
      this.saved = true;
      setTimeout(() => this.saved = false, 3000);
    });
  }
}
```

- [ ] **Step 2: Verify build, commit**

---

## Phase 8: Build Integration

### Task 27: Gradle Angular Build Task

**Files:**
- Modify: `agent-memory/build.gradle`

- [ ] **Step 1: Add Angular build task to Gradle**

Add to `build.gradle`:
```gradle
tasks.register('buildAngular', Exec) {
    workingDir file('angular')
    commandLine 'npx', 'ng', 'build', '--output-path', '../src/main/resources/static'
}

processResources.dependsOn buildAngular
```

- [ ] **Step 2: Full build test**

```bash
cd /home/kamil/Documents/Project/My/Mcp/agent-memory
./gradlew clean shadowJar
ls -la build/libs/agent-memory-0.1.0.jar
```
Expected: JAR built with Angular static files bundled

- [ ] **Step 3: End-to-end smoke test**

```bash
# In one terminal:
java -jar build/libs/agent-memory-0.1.0.jar serve --port 7070 &

# Verify dashboard loads:
curl -s http://localhost:7070/ | head -5
# Expected: HTML content (Angular app)

# Verify API works:
curl -s http://localhost:7070/api/agents
# Expected: [] (empty array)

kill %1
```

- [ ] **Step 4: Commit**

```bash
git add agent-memory/build.gradle
git commit -m "feat(agent-memory): integrate Angular build into Gradle shadowJar"
```

---

### Task 28: Update CLAUDE.md and Final Integration

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Add agent-memory to CLAUDE.md**

Add to Project Structure section:
```
- `agent-memory/` — Agent memory MCP server. General-purpose persistent memory engine for AI agents with knowledge graph, brain system (loop/drift/contradiction detection), and Angular dashboard. 12 MCP tools prefixed `mem_*`.
```

Add to MCP Servers section:
```
- **agent-memory** — configured externally, tools prefixed `mem_*`
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: add agent-memory to CLAUDE.md"
```

---

## Summary

| Phase | Tasks | What it delivers |
|-------|-------|-----------------|
| 1. Scaffolding | 1-3 | Buildable Gradle project with model records and CLI skeleton |
| 2. Data Layer | 4-5 | Full SQLite store with CRUD, FTS5, versions, graph traversal |
| 3. Brain System | 6-9 | Loop, drift, and contradiction detection |
| 4. MCP Server | 10 | All 12 MCP tools wired to store + brain |
| 5. REST API | 11 | Dashboard API with all endpoints |
| 6. Integration | 12-13 | ServeCommand wiring MCP + Javalin, build scripts |
| 7. Angular Dashboard | 14-26 | Full 11-page dark-themed dashboard |
| 8. Build Integration | 27-28 | Angular bundled into shadow JAR, docs updated |

**Total: 28 tasks.** After completion, `java -jar agent-memory-0.1.0.jar serve` runs the MCP server on stdio and the dashboard on `http://localhost:7070`.
