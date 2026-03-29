package com.whatdid.store;

import com.whatdid.model.Commit;
import com.whatdid.model.Repo;
import com.whatdid.model.Session;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class WhatDidStore implements AutoCloseable {

    private final Connection connection;

    public WhatDidStore(Path dbPath) {
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
                CREATE TABLE IF NOT EXISTS repos (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    path TEXT UNIQUE NOT NULL,
                    name TEXT NOT NULL,
                    registered_at TEXT NOT NULL
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS commits (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    repo_id INTEGER NOT NULL,
                    hash TEXT UNIQUE NOT NULL,
                    author TEXT NOT NULL,
                    message TEXT NOT NULL,
                    files_changed INTEGER DEFAULT 0,
                    insertions INTEGER DEFAULT 0,
                    deletions INTEGER DEFAULT 0,
                    committed_at TEXT NOT NULL,
                    scanned_at TEXT NOT NULL,
                    FOREIGN KEY (repo_id) REFERENCES repos(id)
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at TEXT NOT NULL,
                    note TEXT
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS session_commits (
                    session_id INTEGER NOT NULL,
                    commit_id INTEGER NOT NULL,
                    PRIMARY KEY (session_id, commit_id),
                    FOREIGN KEY (session_id) REFERENCES sessions(id),
                    FOREIGN KEY (commit_id) REFERENCES commits(id)
                )""");

            stmt.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS commits_fts USING fts5(
                    message, author,
                    content=commits, content_rowid=rowid
                )""");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_commits_repo ON commits(repo_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_commits_date ON commits(committed_at)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_commits_hash ON commits(hash)");
        }
    }

    public Repo saveRepo(String path, String name) {
        try (var ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO repos (path, name, registered_at) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, path);
            ps.setString(2, name);
            ps.setString(3, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        try (var ps = connection.prepareStatement("SELECT id, path, name, registered_at FROM repos WHERE path = ?")) {
            ps.setString(1, path);
            var rs = ps.executeQuery();
            if (rs.next()) return mapRepo(rs);
            throw new RuntimeException("Failed to save repo: " + path);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Repo> getAllRepos() {
        try (var stmt = connection.createStatement();
             var rs = stmt.executeQuery("SELECT id, path, name, registered_at FROM repos ORDER BY name")) {
            var repos = new ArrayList<Repo>();
            while (rs.next()) repos.add(mapRepo(rs));
            return repos;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void saveCommit(long repoId, String hash, String author, String message,
                           int filesChanged, int insertions, int deletions, Instant committedAt) {
        try (var ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO commits (repo_id, hash, author, message, files_changed, insertions, deletions, committed_at, scanned_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, repoId);
            ps.setString(2, hash);
            ps.setString(3, author);
            ps.setString(4, message);
            ps.setInt(5, filesChanged);
            ps.setInt(6, insertions);
            ps.setInt(7, deletions);
            ps.setString(8, committedAt.toString());
            ps.setString(9, Instant.now().toString());
            int inserted = ps.executeUpdate();

            // Update FTS only if a row was actually inserted
            if (inserted > 0) {
                try (var fts = connection.prepareStatement(
                        "INSERT INTO commits_fts(rowid, message, author) SELECT rowid, message, author FROM commits WHERE hash = ?")) {
                    fts.setString(1, hash);
                    fts.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Commit> getCommitsByRepo(long repoId) {
        try (var ps = connection.prepareStatement(
                "SELECT id, repo_id, hash, author, message, files_changed, insertions, deletions, committed_at, scanned_at FROM commits WHERE repo_id = ? ORDER BY committed_at DESC")) {
            ps.setLong(1, repoId);
            return mapCommits(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Commit> getCommitsSince(Instant since) {
        try (var ps = connection.prepareStatement(
                "SELECT id, repo_id, hash, author, message, files_changed, insertions, deletions, committed_at, scanned_at FROM commits WHERE committed_at >= ? ORDER BY committed_at DESC")) {
            ps.setString(1, since.toString());
            return mapCommits(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Commit> getAllCommits() {
        try (var stmt = connection.createStatement();
             var rs = stmt.executeQuery("SELECT id, repo_id, hash, author, message, files_changed, insertions, deletions, committed_at, scanned_at FROM commits ORDER BY committed_at DESC")) {
            return mapCommits(rs);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Commit> searchCommits(String query) {
        try (var ps = connection.prepareStatement(
                "SELECT c.id, c.repo_id, c.hash, c.author, c.message, c.files_changed, c.insertions, c.deletions, c.committed_at, c.scanned_at FROM commits c JOIN commits_fts f ON c.rowid = f.rowid WHERE commits_fts MATCH ? ORDER BY c.committed_at DESC")) {
            ps.setString(1, query);
            return mapCommits(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Session createSession(String note) {
        try (var ps = connection.prepareStatement(
                "INSERT INTO sessions (created_at, note) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            var now = Instant.now();
            ps.setString(1, now.toString());
            ps.setString(2, note);
            ps.executeUpdate();
            var keys = ps.getGeneratedKeys();
            keys.next();
            return new Session(keys.getLong(1), now, note);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void linkUnsavedCommitsToSession(long sessionId) {
        try (var ps = connection.prepareStatement(
                "INSERT INTO session_commits (session_id, commit_id) SELECT ?, c.id FROM commits c WHERE c.id NOT IN (SELECT commit_id FROM session_commits)")) {
            ps.setLong(1, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Commit> getCommitsBySession(long sessionId) {
        try (var ps = connection.prepareStatement(
                "SELECT c.id, c.repo_id, c.hash, c.author, c.message, c.files_changed, c.insertions, c.deletions, c.committed_at, c.scanned_at FROM commits c JOIN session_commits sc ON c.id = sc.commit_id WHERE sc.session_id = ? ORDER BY c.committed_at DESC")) {
            ps.setLong(1, sessionId);
            return mapCommits(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Session> getAllSessions() {
        try (var stmt = connection.createStatement();
             var rs = stmt.executeQuery("SELECT id, created_at, note FROM sessions ORDER BY created_at DESC")) {
            var sessions = new ArrayList<Session>();
            while (rs.next()) {
                sessions.add(new Session(rs.getLong("id"), Instant.parse(rs.getString("created_at")), rs.getString("note")));
            }
            return sessions;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Repo getRepoById(long id) {
        try (var ps = connection.prepareStatement("SELECT id, path, name, registered_at FROM repos WHERE id = ?")) {
            ps.setLong(1, id);
            var rs = ps.executeQuery();
            if (rs.next()) return mapRepo(rs);
            return null;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws Exception {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    private static Repo mapRepo(ResultSet rs) throws SQLException {
        return new Repo(rs.getLong("id"), rs.getString("path"), rs.getString("name"), Instant.parse(rs.getString("registered_at")));
    }

    private static List<Commit> mapCommits(ResultSet rs) throws SQLException {
        var commits = new ArrayList<Commit>();
        while (rs.next()) {
            commits.add(new Commit(
                rs.getLong("id"), rs.getLong("repo_id"), rs.getString("hash"),
                rs.getString("author"), rs.getString("message"),
                rs.getInt("files_changed"), rs.getInt("insertions"), rs.getInt("deletions"),
                Instant.parse(rs.getString("committed_at")), Instant.parse(rs.getString("scanned_at"))
            ));
        }
        return commits;
    }
}
