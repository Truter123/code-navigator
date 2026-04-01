package com.agentmemory.store;

import com.agentmemory.model.*;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public class MemoryStore {

    private final Connection connection;

    @FunctionalInterface
    interface SqlMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    public MemoryStore(Path dbPath) {
        try {
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA foreign_keys=ON");
            }
            initSchema();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize MemoryStore", e);
        }
    }

    private void initSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS memories (
                    id TEXT PRIMARY KEY,
                    key TEXT NOT NULL,
                    value TEXT,
                    agent TEXT NOT NULL,
                    project TEXT,
                    shared INTEGER DEFAULT 0,
                    importance REAL DEFAULT 0.5,
                    tags_text TEXT,
                    deleted_at TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    UNIQUE(key, agent, project)
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS memory_tags (
                    memory_id TEXT NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
                    tag TEXT NOT NULL,
                    PRIMARY KEY(memory_id, tag)
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS memory_versions (
                    id TEXT PRIMARY KEY,
                    memory_id TEXT NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
                    value TEXT,
                    version INTEGER NOT NULL,
                    created_at TEXT NOT NULL
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS memory_links (
                    id TEXT PRIMARY KEY,
                    source_id TEXT NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
                    target_id TEXT NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
                    relation TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    UNIQUE(source_id, target_id, relation)
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS goals (
                    id TEXT PRIMARY KEY,
                    agent TEXT NOT NULL,
                    project TEXT,
                    description TEXT NOT NULL,
                    status TEXT DEFAULT 'active',
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS audit_log (
                    id TEXT PRIMARY KEY,
                    agent TEXT NOT NULL,
                    operation TEXT NOT NULL,
                    memory_key TEXT,
                    details TEXT,
                    latency_ms REAL,
                    created_at TEXT NOT NULL
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS settings (
                    key TEXT PRIMARY KEY,
                    value TEXT
                )
            """);

            stmt.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(
                    key, value, tags_text,
                    content=memories, content_rowid=rowid
                )
            """);

            // Indexes
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_memories_key ON memories(key)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_memories_agent ON memories(agent)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_memories_project ON memories(project)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_memories_deleted ON memories(deleted_at)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_tags_tag ON memory_tags(tag)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_versions_memory ON memory_versions(memory_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_goals_agent ON goals(agent)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_goals_status ON goals(status)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_audit_agent ON audit_log(agent)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_audit_operation ON audit_log(operation)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_log(created_at)");
        }
    }

    // --- Memory CRUD ---

    public void upsert(String key, String value, String agent, String project,
                       List<String> tags, double importance, boolean shared) {
        String now = Instant.now().toString();
        String tagsText = tags != null && !tags.isEmpty() ? String.join(",", tags) : null;

        // Check for existing memory
        Optional<Memory> existing = recall(key, agent, project);

        if (existing.isPresent()) {
            Memory old = existing.get();
            // Save old value as version
            int nextVersion = getVersions(key, agent, project).size() + 1;
            executeUpdate("""
                INSERT INTO memory_versions (id, memory_id, value, version, created_at)
                VALUES (?, ?, ?, ?, ?)
            """, UUID.randomUUID().toString(), old.id(), old.value(), nextVersion, now);

            // Delete old FTS entry
            executeUpdate("""
                INSERT INTO memory_fts(memory_fts, rowid, key, value, tags_text)
                SELECT 'delete', rowid, key, value, tags_text FROM memories WHERE id = ?
            """, old.id());

            // Update memory
            executeUpdate("""
                UPDATE memories SET value = ?, importance = ?, shared = ?, tags_text = ?, updated_at = ?
                WHERE id = ?
            """, value, importance, shared ? 1 : 0, tagsText, now, old.id());

            // Insert new FTS entry
            executeUpdate("""
                INSERT INTO memory_fts(rowid, key, value, tags_text)
                SELECT rowid, key, value, tags_text FROM memories WHERE id = ?
            """, old.id());

            // Update tags
            executeUpdate("DELETE FROM memory_tags WHERE memory_id = ?", old.id());
            if (tags != null) {
                for (String tag : tags) {
                    executeUpdate("INSERT INTO memory_tags (memory_id, tag) VALUES (?, ?)",
                            old.id(), tag);
                }
            }
        } else {
            String id = UUID.randomUUID().toString();
            executeUpdate("""
                INSERT INTO memories (id, key, value, agent, project, shared, importance, tags_text, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, key, value, agent, project, shared ? 1 : 0, importance, tagsText, now, now);

            // Insert FTS
            executeUpdate("""
                INSERT INTO memory_fts(rowid, key, value, tags_text)
                SELECT rowid, key, value, tags_text FROM memories WHERE id = ?
            """, id);

            // Insert tags
            if (tags != null) {
                for (String tag : tags) {
                    executeUpdate("INSERT INTO memory_tags (memory_id, tag) VALUES (?, ?)", id, tag);
                }
            }
        }
    }

    public Optional<Memory> recall(String key, String agent, String project) {
        String sql;
        Object[] params;
        if (project == null) {
            sql = """
                SELECT m.* FROM memories m
                WHERE m.key = ? AND m.agent = ? AND m.project IS NULL AND m.deleted_at IS NULL
            """;
            params = new Object[]{key, agent};
        } else {
            sql = """
                SELECT m.* FROM memories m
                WHERE m.key = ? AND m.agent = ? AND m.project = ? AND m.deleted_at IS NULL
            """;
            params = new Object[]{key, agent, project};
        }
        List<Memory> results = query(sql, this::mapMemoryWithTags, params);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<Memory> recallByKey(String key) {
        List<Memory> results = query("""
            SELECT * FROM memories WHERE key = ? AND deleted_at IS NULL LIMIT 1
        """, this::mapMemoryWithTags, key);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<Memory> recallAllByKey(String key) {
        return query("""
            SELECT * FROM memories WHERE key = ? AND deleted_at IS NULL
        """, this::mapMemoryWithTags, key);
    }

    public Optional<Memory> recallByKeyAndAgent(String key, String agent) {
        List<Memory> results = query("""
            SELECT * FROM memories WHERE key = ? AND agent = ? AND deleted_at IS NULL LIMIT 1
        """, this::mapMemoryWithTags, key, agent);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public void softDelete(String key, String agent, String project) {
        String now = Instant.now().toString();
        if (project == null) {
            executeUpdate("""
                UPDATE memories SET deleted_at = ?, updated_at = ?
                WHERE key = ? AND agent = ? AND project IS NULL
            """, now, now, key, agent);
        } else {
            executeUpdate("""
                UPDATE memories SET deleted_at = ?, updated_at = ?
                WHERE key = ? AND agent = ? AND project = ?
            """, now, now, key, agent, project);
        }
    }

    public void softDeleteByKeyAndAgent(String key, String agent) {
        String now = Instant.now().toString();
        executeUpdate("""
            UPDATE memories SET deleted_at = ?, updated_at = ?
            WHERE key = ? AND agent = ?
        """, now, now, key, agent);
    }

    public List<Memory> list(String agent, String project, List<String> tags,
                              Boolean shared, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT DISTINCT m.* FROM memories m");
        List<Object> params = new ArrayList<>();

        if (tags != null && !tags.isEmpty()) {
            sql.append(" JOIN memory_tags mt ON m.id = mt.memory_id");
        }

        sql.append(" WHERE m.deleted_at IS NULL");

        if (agent != null) {
            sql.append(" AND m.agent = ?");
            params.add(agent);
        }
        if (project != null) {
            sql.append(" AND m.project = ?");
            params.add(project);
        }
        if (tags != null && !tags.isEmpty()) {
            sql.append(" AND mt.tag IN (");
            for (int i = 0; i < tags.size(); i++) {
                sql.append(i > 0 ? ", ?" : "?");
                params.add(tags.get(i));
            }
            sql.append(")");
        }
        if (shared != null) {
            sql.append(" AND m.shared = ?");
            params.add(shared ? 1 : 0);
        }

        sql.append(" ORDER BY m.updated_at DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        return query(sql.toString(), this::mapMemoryWithTags, params.toArray());
    }

    public List<Memory> search(String queryText, String agent, String project,
                                List<String> tags, int limit) {
        StringBuilder sql = new StringBuilder("""
            SELECT m.* FROM memories m
            JOIN memory_fts f ON m.rowid = f.rowid
            WHERE memory_fts MATCH ?
            AND m.deleted_at IS NULL
        """);
        List<Object> params = new ArrayList<>();
        params.add(queryText);

        if (agent != null) {
            sql.append(" AND m.agent = ?");
            params.add(agent);
        }
        if (project != null) {
            sql.append(" AND m.project = ?");
            params.add(project);
        }

        sql.append(" LIMIT ?");
        params.add(limit);

        return query(sql.toString(), this::mapMemoryWithTags, params.toArray());
    }

    public void share(String key, String agent, String project) {
        String now = Instant.now().toString();
        executeUpdate("""
            UPDATE memories SET shared = 1, updated_at = ?
            WHERE key = ? AND agent = ? AND project = ? AND deleted_at IS NULL
        """, now, key, agent, project);
    }

    public Optional<Memory> findById(String id) {
        List<Memory> results = query("""
            SELECT * FROM memories WHERE id = ? AND deleted_at IS NULL
        """, this::mapMemoryWithTags, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    // --- Versions ---

    public List<MemoryVersion> getVersions(String key, String agent, String project) {
        String memSql;
        Object[] memParams;
        if (project == null) {
            memSql = "SELECT id FROM memories WHERE key = ? AND agent = ? AND project IS NULL";
            memParams = new Object[]{key, agent};
        } else {
            memSql = "SELECT id FROM memories WHERE key = ? AND agent = ? AND project = ?";
            memParams = new Object[]{key, agent, project};
        }
        List<String> ids = query(memSql, rs -> rs.getString("id"), memParams);
        if (ids.isEmpty()) return List.of();

        return query("""
            SELECT * FROM memory_versions WHERE memory_id = ? ORDER BY version ASC
        """, rs -> new MemoryVersion(
                rs.getString("id"),
                rs.getString("memory_id"),
                rs.getString("value"),
                rs.getInt("version"),
                rs.getString("created_at")
        ), ids.get(0));
    }

    // --- Goals ---

    public void setGoals(String agent, List<String> descriptions, String project) {
        String now = Instant.now().toString();
        // Abandon old active goals for this agent
        executeUpdate("""
            UPDATE goals SET status = 'abandoned', updated_at = ?
            WHERE agent = ? AND status = 'active'
        """, now, agent);

        for (String desc : descriptions) {
            executeUpdate("""
                INSERT INTO goals (id, agent, project, description, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'active', ?, ?)
            """, UUID.randomUUID().toString(), agent, project, desc, now, now);
        }
    }

    public List<Goal> getGoals(String agent, String status) {
        StringBuilder sql = new StringBuilder("SELECT * FROM goals WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (agent != null) {
            sql.append(" AND agent = ?");
            params.add(agent);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            params.add(status);
        }

        sql.append(" ORDER BY created_at DESC");

        return query(sql.toString(), rs -> new Goal(
                rs.getString("id"),
                rs.getString("agent"),
                rs.getString("project"),
                rs.getString("description"),
                rs.getString("status"),
                rs.getString("created_at"),
                rs.getString("updated_at")
        ), params.toArray());
    }

    // --- Audit ---

    public void logAudit(String agent, String operation, String memoryKey,
                          String details, double latencyMs) {
        executeUpdate("""
            INSERT INTO audit_log (id, agent, operation, memory_key, details, latency_ms, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """, UUID.randomUUID().toString(), agent, operation, memoryKey, details,
                latencyMs, Instant.now().toString());
    }

    public List<AuditEntry> getAuditLog(String agent, String operation,
                                          String from, String to, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM audit_log WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (agent != null) {
            sql.append(" AND agent = ?");
            params.add(agent);
        }
        if (operation != null) {
            sql.append(" AND operation = ?");
            params.add(operation);
        }
        if (from != null) {
            sql.append(" AND created_at >= ?");
            params.add(from);
        }
        if (to != null) {
            sql.append(" AND created_at <= ?");
            params.add(to);
        }

        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        return query(sql.toString(), rs -> new AuditEntry(
                rs.getString("id"),
                rs.getString("agent"),
                rs.getString("operation"),
                rs.getString("memory_key"),
                rs.getString("details"),
                rs.getDouble("latency_ms"),
                rs.getString("created_at")
        ), params.toArray());
    }

    // --- Settings ---

    public void setSetting(String key, String value) {
        executeUpdate("""
            INSERT INTO settings (key, value) VALUES (?, ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
        """, key, value);
    }

    public String getSetting(String key) {
        List<String> results = query("SELECT value FROM settings WHERE key = ?",
                rs -> rs.getString("value"), key);
        return results.isEmpty() ? null : results.get(0);
    }

    public Map<String, String> getAllSettings() {
        Map<String, String> map = new LinkedHashMap<>();
        List<String[]> rows = query("SELECT key, value FROM settings",
                rs -> new String[]{rs.getString("key"), rs.getString("value")});
        for (String[] row : rows) {
            map.put(row[0], row[1]);
        }
        return map;
    }

    // --- Metrics ---

    public List<String> getKnownAgents() {
        return query("SELECT DISTINCT agent FROM audit_log ORDER BY agent",
                rs -> rs.getString("agent"));
    }

    // --- Internal helpers ---

    void executeUpdate(String sql, Object... params) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("executeUpdate failed: " + sql, e);
        }
    }

    <T> List<T> query(String sql, SqlMapper<T> mapper, Object... params) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            List<T> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapper.map(rs));
                }
            }
            return results;
        } catch (SQLException e) {
            throw new RuntimeException("query failed: " + sql, e);
        }
    }

    private Memory mapMemoryWithTags(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        List<String> tags = query("SELECT tag FROM memory_tags WHERE memory_id = ? ORDER BY tag",
                tagRs -> tagRs.getString("tag"), id);
        return new Memory(
                id,
                rs.getString("key"),
                rs.getString("value"),
                rs.getString("agent"),
                rs.getString("project"),
                rs.getInt("shared") == 1,
                rs.getDouble("importance"),
                rs.getString("tags_text"),
                rs.getString("deleted_at"),
                rs.getString("created_at"),
                rs.getString("updated_at"),
                tags
        );
    }
}
