package com.codenavigator.graph;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

            // Types started from outside the code (static main, picocli @Command). A separate table,
            // not a nodes column: CREATE IF NOT EXISTS opens an old index unchanged, and INSERT OR
            // REPLACE on nodes cannot wipe it.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS entry_points (
                    node_id TEXT PRIMARY KEY
                )""");

            // The indexed class a METHOD's declared return type resolves to, so a call chain
            // a.b().c() can be resolved through a file that is not parsed in this run. Rows only for
            // a return type that names exactly one indexed type. Additive, like entry_points.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS method_return_class (
                    method_id TEXT PRIMARY KEY,
                    class_id TEXT NOT NULL
                )""");

            // SHA-256 of a Java file's declared shape (see JavaFileShape): what a later sync compares
            // to tell a body-only edit from a change other files can see.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS file_shapes (
                    file_path TEXT PRIMARY KEY,
                    shape_hash TEXT NOT NULL
                )""");

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

    private int batchDepth;

    /**
     * Run {@code work} as a single transaction.
     *
     * <p>Saves otherwise autocommit, which under WAL means an fsync per row — the dominant cost
     * when an index writes tens of thousands of nodes and edges. Nesting is safe: only the
     * outermost call commits.
     */
    public void batch(Runnable work) {
        if (batchDepth > 0) {          // already inside a transaction
            work.run();
            return;
        }
        try {
            connection.setAutoCommit(false);
            batchDepth++;
            work.run();
            connection.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Batch failed", e);
        } catch (RuntimeException e) {
            try { connection.rollback(); } catch (SQLException ignored) { }
            throw e;
        } finally {
            batchDepth--;
            try { connection.setAutoCommit(true); } catch (SQLException ignored) { }
        }
    }

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

            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM entry_points WHERE node_id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM method_return_class WHERE method_id = ?")) {
                ps.setString(1, id);
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

            // The file is gone or about to be re-indexed; its shape goes with its nodes.
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM file_shapes WHERE file_path = ?")) {
                ps.setString(1, filePath);
                ps.executeUpdate();
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

    // ---- Dead-code candidates (tiered, supertype/entry-point/test aware) ----

    /**
     * Node types a framework invokes reflectively or by auto-registration: HTTP dispatch, an
     * event/command/query bus, DI container wiring. Nothing in the graph will ever reference
     * these directly even when they're fully live, so a bare "no incoming edges" reading is
     * expected, not a dead-code signal.
     */
    private static final Set<NodeType> FRAMEWORK_ENTRY_POINT_TYPES = Set.of(
        NodeType.CONTROLLER, NodeType.EVENT_LISTENER, NodeType.PROJECTION_HANDLER,
        NodeType.EVENT_APPLIER, NodeType.COMMAND_HANDLER, NodeType.QUERY_HANDLER
    );

    /**
     * Node types that are entry points by construction — run by a human, cron, CI or Docker
     * (scripts), wired by a build/DI tool (configuration), or a placeholder for an external
     * binary the indexer never claims to see calls into (a {@code RUNS} target). An empty
     * incoming-edge set carries no signal at all here, unlike {@link #FRAMEWORK_ENTRY_POINT_TYPES}
     * where it is still worth a LOW-confidence mention: these are dropped from the candidate set
     * entirely rather than listed.
     */
    private static final Set<NodeType> EXCLUDED_ROOT_TYPES = Set.of(
        NodeType.CONFIGURATION, NodeType.SHELL_SCRIPT, NodeType.SHELL_COMMAND, NodeType.PY_MODULE
    );

    /**
     * SQL nodes with no incoming {@code REFERENCES_TABLE}/{@code DERIVES_FROM}/{@code
     * REFERENCES_FK} edge are a useful LOW-confidence signal ("no code references this table"),
     * distinct from the ordinary HIGH/MEDIUM tiering below — nothing in {@link
     * #HIGH_CONFIDENCE_TYPES} applies to a table, and it is not a framework entry point either.
     */
    private static final Set<NodeType> SQL_REFERENCE_TYPES = Set.of(
        NodeType.SQL_TABLE, NodeType.SQL_VIEW, NodeType.SQL_ROUTINE
    );

    /**
     * Node types whose references the indexer reliably captures as graph edges (DI injection,
     * direct method calls) — a miss here is a stronger dead-code signal. Everything else (DTOs,
     * interfaces, enums, records, events, commands/queries, template/route-driven frontend
     * artifacts, ...) can plausibly be reached through JSON/JPA (de)serialization, generic
     * lookups, or template/selector wiring the indexer doesn't model as an edge, so it lands in
     * {@link DeadCodeConfidence#MEDIUM} instead.
     */
    private static final Set<NodeType> HIGH_CONFIDENCE_TYPES = Set.of(
        NodeType.CLASS, NodeType.SERVICE, NodeType.REPOSITORY, NodeType.MAPPER,
        NodeType.VIEW, NodeType.EVENT_PUBLISHER, NodeType.AGGREGATE,
        NodeType.FE_SERVICE, NodeType.FE_CLASS, NodeType.FE_CONSTANT
    );

    public record DeadCodeCandidate(Node node, DeadCodeConfidence confidence, String reason) {}

    /**
     * Like {@link #findDeadNodes(NodeType)}, but supertype-aware, entry-point-aware and
     * test-aware, with each result tiered by how confident the signal actually is. A node is
     * dropped entirely (not "dead" at all) when something it {@code IMPLEMENTS}/{@code EXTENDS}
     * is itself referenced — the classic "callers inject the interface, so the impl class shows
     * zero incoming edges" false positive.
     */
    public List<DeadCodeCandidate> findDeadNodeCandidates(NodeType typeFilter) {
        List<DeadCodeCandidate> result = new ArrayList<>();

        for (Node node : findNoIncomingEdgeNodes(typeFilter)) {
            if (EXCLUDED_ROOT_TYPES.contains(node.type())) continue;
            if (node.type() == NodeType.METHOD && MethodIds.isConstructor(node.id())) continue;  // frameworks call constructors
            if (isTestSource(node.filePath())) {
                result.add(new DeadCodeCandidate(node, DeadCodeConfidence.TEST_SOURCE,
                    "Test source — JUnit/Vitest invoke it reflectively, so it has no incoming " +
                    "graph edges by construction. Not dead code."));
                continue;
            }
            if (hasLiveSupertype(node.id(), new HashSet<>())) {
                // Referenced only through an IMPLEMENTS/EXTENDS supertype (e.g. callers inject
                // the interface). Genuinely live — not a dead-code candidate at all.
                continue;
            }
            if (FRAMEWORK_ENTRY_POINT_TYPES.contains(node.type())) {
                result.add(new DeadCodeCandidate(node, DeadCodeConfidence.LOW,
                    "Framework entry point (" + node.type() + ") — dispatched by reflection or " +
                    "auto-registration, so it will never show incoming graph edges even when live."));
                continue;
            }
            if (SQL_REFERENCE_TYPES.contains(node.type())) {
                result.add(new DeadCodeCandidate(node, DeadCodeConfidence.LOW,
                    "No code references this table — nothing indexed emits a REFERENCES_TABLE/" +
                    "DERIVES_FROM/REFERENCES_FK edge to it. Worth a look, but a table can be used " +
                    "by manual SQL, an external tool, or code outside this project."));
                continue;
            }
            boolean entryPoint = isMainOrCommandClass(node);
            DeadCodeConfidence confidence = HIGH_CONFIDENCE_TYPES.contains(node.type()) && !entryPoint
                ? DeadCodeConfidence.HIGH : DeadCodeConfidence.MEDIUM;
            String reason = confidence == DeadCodeConfidence.HIGH
                ? "No incoming edges, no live supertype, not a framework entry point — " +
                  "this node type's references are reliably captured as edges, so this is a " +
                  "strong candidate. Still verify before deleting."
                : "No incoming edges, no live supertype, not a framework entry point — but this " +
                  "node type is commonly reached via reflection/serialization/generic lookup " +
                  "that the indexer may not capture as an edge. Verify manually.";
            result.add(new DeadCodeCandidate(node, confidence, reason));
        }

        for (Node node : findUsedOnlyByTestsNodes(typeFilter)) {
            if (EXCLUDED_ROOT_TYPES.contains(node.type())) continue;
            if (node.type() == NodeType.METHOD && MethodIds.isConstructor(node.id())) continue;
            // A library a test imports (JUnit, AssertJ) is a dependency, not code to remove.
            if (node.type() == NodeType.LIBRARY) continue;
            result.add(new DeadCodeCandidate(node, DeadCodeConfidence.USED_ONLY_BY_TESTS,
                "Has incoming edges, but every one of them originates in test code — unused by " +
                "production code as far as the graph shows. May be intentional test scaffolding, " +
                "or the last caller keeping otherwise-dead code reachable."));
        }

        return result;
    }

    /** Record whether a type is an entry point (static {@code main}, picocli {@code @Command}). */
    public void setEntryPoint(String nodeId, boolean entryPoint) {
        String sql = entryPoint ? "INSERT OR IGNORE INTO entry_points (node_id) VALUES (?)"
                                : "DELETE FROM entry_points WHERE node_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, nodeId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set entry point: " + nodeId, e);
        }
    }

    private boolean isMainOrCommandClass(Node node) {
        return isEntryPoint(node.id());
    }

    public boolean isEntryPoint(String nodeId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM entry_points WHERE node_id = ?")) {
            ps.setString(1, nodeId);
            return ps.executeQuery().next();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check entry point: " + nodeId, e);
        }
    }

    // ---- Return classes and file shapes (facts a single-file re-extraction needs) ----

    public void saveReturnClass(String methodId, String classId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO method_return_class (method_id, class_id) VALUES (?, ?)")) {
            ps.setString(1, methodId);
            ps.setString(2, classId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save return class: " + methodId, e);
        }
    }

    public Optional<String> findReturnClass(String methodId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT class_id FROM method_return_class WHERE method_id = ?")) {
            ps.setString(1, methodId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find return class: " + methodId, e);
        }
    }

    /** Every stored (method id -> return class id), for tests and snapshots. */
    public java.util.Map<String, String> getAllReturnClasses() {
        var result = new java.util.TreeMap<String, String>();
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT method_id, class_id FROM method_return_class");
            while (rs.next()) result.put(rs.getString(1), rs.getString(2));
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read return classes", e);
        }
    }

    /** Drop the rows of every method declared by {@code classId}; a re-extracted file replaces them. */
    public void deleteReturnClassesOfClass(String classId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM method_return_class WHERE substr(method_id, 1, length(?)) = ?")) {
            String prefix = classId + MethodIds.SEPARATOR;
            ps.setString(1, prefix);
            ps.setString(2, prefix);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete return classes of: " + classId, e);
        }
    }

    public void deleteAllReturnClasses() {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM method_return_class");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete return classes", e);
        }
    }

    public void saveFileShape(String filePath, String shapeHash) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO file_shapes (file_path, shape_hash) VALUES (?, ?)")) {
            ps.setString(1, filePath);
            ps.setString(2, shapeHash);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save file shape: " + filePath, e);
        }
    }

    public Optional<String> findFileShape(String filePath) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT shape_hash FROM file_shapes WHERE file_path = ?")) {
            ps.setString(1, filePath);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find file shape: " + filePath, e);
        }
    }

    /** Every stored (file path -> shape hash), for tests and snapshots. */
    public java.util.Map<String, String> getAllFileShapes() {
        var result = new java.util.TreeMap<String, String>();
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT file_path, shape_hash FROM file_shapes");
            while (rs.next()) result.put(rs.getString(1), rs.getString(2));
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read file shapes", e);
        }
    }

    public void deleteAllFileShapes() {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM file_shapes");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete file shapes", e);
        }
    }

    /** Clear the entry-point side-table; indexFull re-derives it from the sources. */
    public void deleteAllEntryPoints() {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM entry_points");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete entry points", e);
        }
    }

    /** Same "no incoming edges" query as {@link #findDeadNodes}, but without the hardcoded
     *  type exclusion — every type is eligible, and confidence tiering does the ranking instead. */
    private List<Node> findNoIncomingEdgeNodes(NodeType typeFilter) {
        String sql = typeFilter != null
            ? """
                SELECT n.* FROM nodes n
                LEFT JOIN edges e ON e.target_id = n.id
                WHERE e.id IS NULL AND n.type = ?
                ORDER BY n.type, n.name"""
            : """
                SELECT n.* FROM nodes n
                LEFT JOIN edges e ON e.target_id = n.id
                WHERE e.id IS NULL
                ORDER BY n.type, n.name""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (typeFilter != null) {
                ps.setString(1, typeFilter.name());
            }
            return mapNodes(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find nodes with no incoming edges", e);
        }
    }

    /** Template for {@link #findUsedOnlyByTestsNodes}: %1$s is the (optional) "n.type = ?  AND"
     *  clause, %2$s is the not-test-source predicate applied to the candidate node itself, %3$s
     *  the same predicate applied to each incoming edge's source node. */
    private static final String USED_ONLY_BY_TESTS_SQL = """
        SELECT n.* FROM nodes n
        WHERE %1$s %2$s
        AND EXISTS (SELECT 1 FROM edges e WHERE e.target_id = n.id)
        AND NOT EXISTS (
            SELECT 1 FROM edges e2
            JOIN nodes src ON src.id = e2.source_id
            WHERE e2.target_id = n.id AND %3$s
        )
        ORDER BY n.type, n.name""";

    /** Main-source nodes that have incoming edges, but only from test-source callers. */
    private List<Node> findUsedOnlyByTestsNodes(NodeType typeFilter) {
        String typeClause = typeFilter != null ? "n.type = ? AND" : "";
        String sql = USED_ONLY_BY_TESTS_SQL.formatted(
            typeClause, NOT_TEST_SOURCE_SQL.formatted("n"), NOT_TEST_SOURCE_SQL.formatted("src"));
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (typeFilter != null) {
                ps.setString(1, typeFilter.name());
            }
            return mapNodes(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find used-only-by-tests nodes", e);
        }
    }

    /** SQL predicate fragment: "<alias> is NOT a test-source node". Must stay in sync with
     *  {@link #isTestSource(String)} — same rule, one in SQL for set queries, one in Java for
     *  per-node checks. %1$s is the table alias to apply it to. */
    private static final String NOT_TEST_SOURCE_SQL = """
        (%1$s.file_path NOT LIKE '%%src/test/%%'
         AND %1$s.file_path NOT LIKE '%%Test.java'
         AND %1$s.file_path NOT LIKE '%%Tests.java'
         AND %1$s.file_path NOT LIKE '%%.spec.ts')""";

    /** src/test/, *Test.java, *Tests.java, *.spec.ts — JUnit/Vitest invoke these reflectively,
     *  so an empty incoming-edge set is expected and carries no dead-code signal. */
    private static boolean isTestSource(String filePath) {
        if (filePath == null) return false;
        String p = filePath.replace('\\', '/');
        return p.contains("src/test/") || p.endsWith("Test.java")
            || p.endsWith("Tests.java") || p.endsWith(".spec.ts");
    }

    /** Walks IMPLEMENTS/EXTENDS edges transitively from {@code nodeId}; true if any reachable
     *  supertype is itself referenced by something other than an IMPLEMENTS/EXTENDS edge
     *  (typically callers that inject/depend on the interface rather than the implementation).
     *  Cycle-safe via visited; depth-capped as a cheap safety net against pathological graphs. */
    private boolean hasLiveSupertype(String nodeId, Set<String> visited) {
        if (!visited.add(nodeId) || visited.size() > 50) return false;
        List<Edge> superEdges = new ArrayList<>(findEdgesFromByType(nodeId, EdgeType.IMPLEMENTS));
        superEdges.addAll(findEdgesFromByType(nodeId, EdgeType.EXTENDS));
        for (Edge edge : superEdges) {
            if (hasNonInheritanceIncomingEdge(edge.targetId())) return true;
            if (hasLiveSupertype(edge.targetId(), visited)) return true;
        }
        return false;
    }

    /** True if {@code nodeId} has an incoming edge that isn't just IMPLEMENTS/EXTENDS. Excluding
     *  those is what makes {@link #hasLiveSupertype} meaningful: an interface with a single
     *  implementor always has an incoming IMPLEMENTS edge from that implementor alone, which
     *  would otherwise make every interface look "live" regardless of whether anything actually
     *  depends on it. */
    private boolean hasNonInheritanceIncomingEdge(String nodeId) {
        return findEdgesTo(nodeId).stream()
            .anyMatch(e -> e.type() != EdgeType.IMPLEMENTS && e.type() != EdgeType.EXTENDS);
    }

    /**
     * Remove every node and its FTS rows. A full index starts from here so that no node of an id
     * scheme an older jar produced outlives it: ids are the key, so a re-save replaces a node but
     * never removes one that is no longer produced. Edges, entry points and return classes are
     * cleared separately by the caller.
     */
    public void deleteAllNodes() {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("INSERT INTO nodes_fts(nodes_fts) VALUES('delete-all')");
            stmt.execute("DELETE FROM nodes");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete all nodes", e);
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

    /**
     * Clear the method signature side-table. Rows carry an autoincrement id rather than a natural
     * key, so re-indexing appends duplicates unless they are removed first.
     */
    public void deleteAllMethods() {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM methods");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete methods", e);
        }
    }

    /**
     * Delete every edge whose source is a node of the file (class and METHOD nodes alike, METHOD
     * nodes carry the file path). Inbound edges from other files are left alone: the incremental
     * sync re-emits only what the changed file owns.
     */
    public void deleteEdgesOwnedByFile(String filePath) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM edges WHERE source_id IN (SELECT id FROM nodes WHERE file_path = ?)")) {
            ps.setString(1, filePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete edges owned by file: " + filePath, e);
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
        return findTopFanIn(limit, null);
    }

    /** Top fan-in among the nodes under {@code packagePrefix} (that package and its sub-packages); null means all. */
    public List<NodeCount> findTopFanIn(int limit, String packagePrefix) {
        return topByEdges("target_id", limit, packagePrefix);
    }

    public List<NodeCount> findTopFanOut(int limit) {
        return findTopFanOut(limit, null);
    }

    /** Top fan-out among the nodes under {@code packagePrefix} (that package and its sub-packages); null means all. */
    public List<NodeCount> findTopFanOut(int limit, String packagePrefix) {
        return topByEdges("source_id", limit, packagePrefix);
    }

    /** Top fan-out among the non-method nodes under {@code packagePrefix}: the types, not their methods. */
    public List<NodeCount> findTopFanOutTypes(int limit, String packagePrefix) {
        return topByEdges("source_id", limit, packagePrefix, true);
    }

    private List<NodeCount> topByEdges(String endColumn, int limit, String packagePrefix) {
        return topByEdges(endColumn, limit, packagePrefix, false);
    }

    private List<NodeCount> topByEdges(String endColumn, int limit, String packagePrefix, boolean typesOnly) {
        // A library is not code to change: every importer points at it, so it would top the fan-in list.
        String where = (packagePrefix == null ? "" : "n.qualified_name LIKE ? ESCAPE '\\' AND ")
            + (typesOnly ? "n.type <> 'METHOD' AND " : "") + "n.type <> 'LIBRARY' ";
        String scope = where.isEmpty() ? "" : "WHERE " + where;
        String sql = "SELECT n.*, COUNT(e.id) as edge_count FROM nodes n JOIN edges e ON e." + endColumn
            + " = n.id " + scope + "GROUP BY n.id ORDER BY edge_count DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int next = 1;
            if (packagePrefix != null) {
                ps.setString(next++, packagePrefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + ".%");
            }
            ps.setInt(next, limit);
            return mapNodeCounts(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find top fan-" + (endColumn.equals("target_id") ? "in" : "out"), e);
        }
    }

    // ---- Co-change coupling ----

    public record CoChangePair(String otherFile, int count) {}

    /**
     * Clear mined co-change pairs. upsertCoChange increments an existing count, so a second index
     * run doubles every pair unless the table is emptied first.
     */
    public void deleteAllCoChange() {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM co_change");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete co-change data", e);
        }
    }

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
