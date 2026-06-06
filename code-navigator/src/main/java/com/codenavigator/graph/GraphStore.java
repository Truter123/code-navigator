package com.codenavigator.graph;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class GraphStore implements AutoCloseable {

    private final Connection connection;

    public GraphStore(Path dbPath) {
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
                CREATE TABLE IF NOT EXISTS project_config (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS nodes (
                    id TEXT PRIMARY KEY,
                    type TEXT,
                    name TEXT,
                    qualified_name TEXT,
                    file_path TEXT,
                    line_number INTEGER,
                    code_snippet TEXT,
                    last_modified INTEGER
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS edges (
                    id TEXT PRIMARY KEY,
                    type TEXT,
                    source_id TEXT,
                    target_id TEXT
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS indexed_files (
                    file_path TEXT PRIMARY KEY,
                    last_modified INTEGER,
                    checksum TEXT
                )""");

            stmt.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS nodes_fts USING fts5(
                    name, qualified_name, code_snippet,
                    content=nodes, content_rowid=rowid
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS methods (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    node_id TEXT REFERENCES nodes(id) ON DELETE CASCADE,
                    name TEXT NOT NULL,
                    return_type TEXT,
                    parameters TEXT,
                    annotations TEXT,
                    visibility TEXT
                )""");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_methods_node ON methods(node_id)");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_edges_source ON edges(source_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_edges_target ON edges(target_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_nodes_type ON nodes(type)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_nodes_name ON nodes(name)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_nodes_file ON nodes(file_path)");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS co_change (
                    file_a TEXT NOT NULL,
                    file_b TEXT NOT NULL,
                    count  INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (file_a, file_b),
                    CHECK (file_a < file_b)
                )""");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_co_change_a ON co_change(file_a)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_co_change_b ON co_change(file_b)");
        }
    }

    // ---- Node operations ----

    public void saveNode(Node node) {
        try {
            // Delete old FTS entry if node already exists
            deleteNodeFtsEntry(node.id());

            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT OR REPLACE INTO nodes (id, type, name, qualified_name, file_path, line_number, code_snippet, last_modified)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
                ps.setString(1, node.id());
                ps.setString(2, node.type().name());
                ps.setString(3, node.name());
                ps.setString(4, node.qualifiedName());
                ps.setString(5, node.filePath());
                ps.setInt(6, node.lineNumber());
                ps.setString(7, node.codeSnippet());
                ps.setLong(8, node.lastModified());
                ps.executeUpdate();
            }

            // Insert new FTS entry
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO nodes_fts (rowid, name, qualified_name, code_snippet)
                    SELECT rowid, name, qualified_name, code_snippet FROM nodes WHERE id = ?""")) {
                ps.setString(1, node.id());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save node: " + node.id(), e);
        }
    }

    public Optional<Node> findNodeById(String id) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM nodes WHERE id = ?")) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(mapNode(rs));
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find node: " + id, e);
        }
    }

    public List<Node> findNodesByType(NodeType type) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM nodes WHERE type = ?")) {
            ps.setString(1, type.name());
            return mapNodes(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find nodes by type: " + type, e);
        }
    }

    public List<Node> findNodesByName(String name) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM nodes WHERE name = ?")) {
            ps.setString(1, name);
            return mapNodes(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find nodes by name: " + name, e);
        }
    }

    public List<Node> findNodesByFilePath(String filePath) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM nodes WHERE file_path = ?")) {
            ps.setString(1, filePath);
            return mapNodes(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find nodes by file path: " + filePath, e);
        }
    }

    public List<Node> getAllNodes() {
        try (Statement stmt = connection.createStatement()) {
            return mapNodes(stmt.executeQuery("SELECT * FROM nodes"));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get all nodes", e);
        }
    }

    public void deleteNode(String id) {
        try {
            deleteNodeFtsEntry(id);

            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM edges WHERE source_id = ? OR target_id = ?")) {
                ps.setString(1, id);
                ps.setString(2, id);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM nodes WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete node: " + id, e);
        }
    }

    public void deleteNodesByFilePath(String filePath) {
        try {
            // Get node ids first for FTS and edge cleanup
            List<String> nodeIds = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("SELECT id FROM nodes WHERE file_path = ?")) {
                ps.setString(1, filePath);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    nodeIds.add(rs.getString("id"));
                }
            }

            for (String nodeId : nodeIds) {
                deleteNode(nodeId);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete nodes by file path: " + filePath, e);
        }
    }

    // ---- Edge operations ----

    public void saveEdge(Edge edge) {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT OR REPLACE INTO edges (id, type, source_id, target_id)
                VALUES (?, ?, ?, ?)""")) {
            ps.setString(1, edge.id());
            ps.setString(2, edge.type().name());
            ps.setString(3, edge.sourceId());
            ps.setString(4, edge.targetId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save edge: " + edge.id(), e);
        }
    }

    public List<Edge> findEdgesFrom(String sourceId) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM edges WHERE source_id = ?")) {
            ps.setString(1, sourceId);
            return mapEdges(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find edges from: " + sourceId, e);
        }
    }

    public List<Edge> findEdgesTo(String targetId) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM edges WHERE target_id = ?")) {
            ps.setString(1, targetId);
            return mapEdges(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find edges to: " + targetId, e);
        }
    }

    public List<Edge> findEdgesFromByType(String sourceId, EdgeType type) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM edges WHERE source_id = ? AND type = ?")) {
            ps.setString(1, sourceId);
            ps.setString(2, type.name());
            return mapEdges(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find edges from " + sourceId + " by type " + type, e);
        }
    }

    public List<Edge> findEdgesToByType(String targetId, EdgeType type) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM edges WHERE target_id = ? AND type = ?")) {
            ps.setString(1, targetId);
            ps.setString(2, type.name());
            return mapEdges(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find edges to " + targetId + " by type " + type, e);
        }
    }

    public List<Edge> getAllEdges() {
        try (Statement stmt = connection.createStatement()) {
            return mapEdges(stmt.executeQuery("SELECT * FROM edges"));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get all edges", e);
        }
    }

    public List<Node> findDeadNodes(NodeType typeFilter) {
        String sql;
        if (typeFilter != null) {
            sql = """
                SELECT n.* FROM nodes n
                LEFT JOIN edges e ON e.target_id = n.id
                WHERE e.id IS NULL AND n.type = ?
                ORDER BY n.type, n.name""";
        } else {
            sql = """
                SELECT n.* FROM nodes n
                LEFT JOIN edges e ON e.target_id = n.id
                WHERE e.id IS NULL
                AND n.type NOT IN ('CONFIGURATION', 'ENTITY', 'ENUM', 'RECORD', 'FE_MODEL')
                ORDER BY n.type, n.name""";
        }
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (typeFilter != null) {
                ps.setString(1, typeFilter.name());
            }
            return mapNodes(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find dead nodes", e);
        }
    }

    public void deleteAllEdges() {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM edges");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete all edges", e);
        }
    }

    // ---- Method operations ----

    public void saveMethod(MethodRecord method) {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO methods (node_id, name, return_type, parameters, annotations, visibility)
                VALUES (?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, method.nodeId());
            ps.setString(2, method.name());
            ps.setString(3, method.returnType());
            ps.setString(4, method.parameters());
            ps.setString(5, method.annotations());
            ps.setString(6, method.visibility());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save method: " + method.name(), e);
        }
    }

    public void deleteMethodsByNodeId(String nodeId) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM methods WHERE node_id = ?")) {
            ps.setString(1, nodeId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete methods for node: " + nodeId, e);
        }
    }

    public List<MethodRecord> findMethodsByNodeId(String nodeId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT node_id, name, return_type, parameters, annotations, visibility FROM methods WHERE node_id = ? ORDER BY name")) {
            ps.setString(1, nodeId);
            return mapMethods(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find methods for node: " + nodeId, e);
        }
    }

    public List<MethodRecord> findMethodsByNodeIds(List<String> nodeIds) {
        if (nodeIds.isEmpty()) return List.of();
        String placeholders = String.join(",", nodeIds.stream().map(id -> "?").toList());
        String sql = "SELECT node_id, name, return_type, parameters, annotations, visibility FROM methods WHERE node_id IN (" + placeholders + ") ORDER BY node_id, name";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < nodeIds.size(); i++) {
                ps.setString(i + 1, nodeIds.get(i));
            }
            return mapMethods(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find methods for nodes", e);
        }
    }

    private List<MethodRecord> mapMethods(ResultSet rs) throws SQLException {
        List<MethodRecord> methods = new ArrayList<>();
        while (rs.next()) {
            methods.add(new MethodRecord(
                rs.getString("node_id"), rs.getString("name"), rs.getString("return_type"),
                rs.getString("parameters"), rs.getString("annotations"), rs.getString("visibility")));
        }
        return methods;
    }

    // ---- FTS5 search ----

    public List<Node> searchFts(String query) {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT n.* FROM nodes n
                JOIN nodes_fts fts ON n.rowid = fts.rowid
                WHERE nodes_fts MATCH ?""")) {
            ps.setString(1, query);
            return mapNodes(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("FTS search failed for query: " + query, e);
        }
    }

    /** Substring search on name and qualified_name using LIKE */
    public List<Node> searchLike(String pattern) {
        String like = "%" + pattern + "%";
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM nodes WHERE name LIKE ? OR qualified_name LIKE ?")) {
            ps.setString(1, like);
            ps.setString(2, like);
            return mapNodes(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("LIKE search failed for pattern: " + pattern, e);
        }
    }

    // ---- Indexed files ----

    public void saveIndexedFile(String filePath, long lastModified, String checksum) {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT OR REPLACE INTO indexed_files (file_path, last_modified, checksum)
                VALUES (?, ?, ?)""")) {
            ps.setString(1, filePath);
            ps.setLong(2, lastModified);
            ps.setString(3, checksum);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save indexed file: " + filePath, e);
        }
    }

    public long getIndexedFileModifiedTime(String filePath) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT last_modified FROM indexed_files WHERE file_path = ?")) {
            ps.setString(1, filePath);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getLong("last_modified");
            }
            return -1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get indexed file time: " + filePath, e);
        }
    }

    public String getIndexedFileChecksum(String filePath) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT checksum FROM indexed_files WHERE file_path = ?")) {
            ps.setString(1, filePath);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String checksum = rs.getString("checksum");
                return checksum != null ? checksum : "";
            }
            return "";
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get indexed file checksum: " + filePath, e);
        }
    }

    public void removeIndexedFile(String filePath) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM indexed_files WHERE file_path = ?")) {
            ps.setString(1, filePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove indexed file: " + filePath, e);
        }
    }

    // ---- Config ----

    public void setConfig(String key, String value) {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT OR REPLACE INTO project_config (key, value) VALUES (?, ?)""")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set config: " + key, e);
        }
    }

    public String getConfig(String key) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT value FROM project_config WHERE key = ?")) {
            ps.setString(1, key);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("value");
            }
            return null;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get config: " + key, e);
        }
    }

    // ---- Hotspot queries ----

    public record MethodRecord(String nodeId, String name, String returnType,
                                String parameters, String annotations, String visibility) {}

    public record NodeCount(Node node, int count) {}

    public List<NodeCount> findTopFanIn(int limit) {
        String sql = """
            SELECT n.*, COUNT(e.id) as edge_count
            FROM nodes n JOIN edges e ON e.target_id = n.id
            GROUP BY n.id ORDER BY edge_count DESC LIMIT ?""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            return mapNodeCounts(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find top fan-in", e);
        }
    }

    public List<NodeCount> findTopFanOut(int limit) {
        String sql = """
            SELECT n.*, COUNT(e.id) as edge_count
            FROM nodes n JOIN edges e ON e.source_id = n.id
            GROUP BY n.id ORDER BY edge_count DESC LIMIT ?""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            return mapNodeCounts(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find top fan-out", e);
        }
    }

    // ---- Co-change coupling ----

    public record CoChangePair(String otherFile, int count) {}

    /**
     * Upserts a co-change observation between two files.
     * Always stores canonical ordering (file_a < file_b).
     */
    public void upsertCoChange(String fileX, String fileY) {
        String fileA = fileX.compareTo(fileY) <= 0 ? fileX : fileY;
        String fileB = fileX.compareTo(fileY) <= 0 ? fileY : fileX;
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO co_change (file_a, file_b, count) VALUES (?, ?, 1)
                ON CONFLICT(file_a, file_b) DO UPDATE SET count = count + 1""")) {
            ps.setString(1, fileA);
            ps.setString(2, fileB);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert co-change: " + fileX + " <-> " + fileY, e);
        }
    }

    /**
     * Returns top co-changed files for a given file, sorted by count descending.
     */
    public List<CoChangePair> topCoupled(String filePath, int limit) {
        String sql = """
            SELECT
                CASE WHEN file_a = ? THEN file_b ELSE file_a END AS other_file,
                count
            FROM co_change
            WHERE file_a = ? OR file_b = ?
            ORDER BY count DESC
            LIMIT ?""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, filePath);
            ps.setString(2, filePath);
            ps.setString(3, filePath);
            ps.setInt(4, limit);
            ResultSet rs = ps.executeQuery();
            List<CoChangePair> results = new ArrayList<>();
            while (rs.next()) {
                results.add(new CoChangePair(rs.getString("other_file"), rs.getInt("count")));
            }
            return results;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query top coupled for: " + filePath, e);
        }
    }

    // ---- Aggregate queries ----

    public int getNodeCount() {
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM nodes");
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get node count", e);
        }
    }

    public int getEdgeCount() {
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM edges");
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get edge count", e);
        }
    }

    public int getFileCount() {
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM indexed_files");
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get file count", e);
        }
    }

    public List<String> getAllIndexedFiles() {
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT file_path FROM indexed_files ORDER BY file_path");
            List<String> files = new ArrayList<>();
            while (rs.next()) {
                files.add(rs.getString("file_path"));
            }
            return files;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get all indexed files", e);
        }
    }

    // ---- AutoCloseable ----

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to close database connection", e);
        }
    }

    // ---- Private helpers ----

    private void deleteNodeFtsEntry(String nodeId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO nodes_fts (nodes_fts, rowid, name, qualified_name, code_snippet)
                SELECT 'delete', rowid, name, qualified_name, code_snippet FROM nodes WHERE id = ?""")) {
            ps.setString(1, nodeId);
            ps.executeUpdate();
        }
    }

    private Node mapNode(ResultSet rs) throws SQLException {
        return new Node(
            rs.getString("id"),
            NodeType.valueOf(rs.getString("type")),
            rs.getString("name"),
            rs.getString("qualified_name"),
            rs.getString("file_path"),
            rs.getInt("line_number"),
            rs.getString("code_snippet"),
            rs.getLong("last_modified")
        );
    }

    private List<Node> mapNodes(ResultSet rs) throws SQLException {
        List<Node> nodes = new ArrayList<>();
        while (rs.next()) {
            nodes.add(mapNode(rs));
        }
        return nodes;
    }

    private List<NodeCount> mapNodeCounts(ResultSet rs) throws SQLException {
        List<NodeCount> results = new ArrayList<>();
        while (rs.next()) {
            results.add(new NodeCount(mapNode(rs), rs.getInt("edge_count")));
        }
        return results;
    }

    private Edge mapEdge(ResultSet rs) throws SQLException {
        return new Edge(
            rs.getString("id"),
            EdgeType.valueOf(rs.getString("type")),
            rs.getString("source_id"),
            rs.getString("target_id")
        );
    }

    private List<Edge> mapEdges(ResultSet rs) throws SQLException {
        List<Edge> edges = new ArrayList<>();
        while (rs.next()) {
            edges.add(mapEdge(rs));
        }
        return edges;
    }
}
