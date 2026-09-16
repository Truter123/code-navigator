package com.codenavigator.indexer;

import com.codenavigator.graph.*;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EdgeExtractor}'s {@code REFERENCES_TABLE} edges (Java side) and
 * integration-level tests for the Python side, driven through {@link ProjectIndexer} so the
 * SQL table set ({@link KnownNames}) comes from a real {@code SqlIndexer} pass.
 */
class TableReferenceEdgeTest {

    @TempDir
    Path tempDir;

    private GraphStore store;

    @BeforeAll
    static void configureParser() {
        StaticJavaParser.getParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
    }

    @BeforeEach
    void setUp() {
        store = new GraphStore(tempDir.resolve("test.db"));
    }

    private Node entityNode(String fqn) {
        String name = fqn.substring(fqn.lastIndexOf('.') + 1);
        var node = new Node(fqn, NodeType.ENTITY, name, fqn, "test.java", 1, "", 0L);
        store.saveNode(node);
        return node;
    }

    private void saveSqlTable(String name) {
        store.saveNode(new Node("sql:" + name, NodeType.SQL_TABLE, name, name, "schema.sql", 1, "", 0L));
    }

    // ---- Java: @Table / @Entity ----

    @Test
    void tableAnnotationWithNameYieldsEdge() {
        saveSqlTable("supplier");
        var source = entityNode("com.example.Supplier");

        String code = """
            package com.example;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Table;
            @Entity
            @Table(name = "supplier")
            class Supplier {}
            """;
        var cu = StaticJavaParser.parse(code);
        new EdgeExtractor(Project.CRUD, store, List.of(), Set.of("supplier")).extract(cu, source);

        assertThat(store.findEdgesFrom(source.id()))
            .anyMatch(e -> e.type() == EdgeType.REFERENCES_TABLE && e.targetId().equals("sql:supplier"));
    }

    @Test
    void entityWithoutTableFallsBackToSnakeCaseClassName() {
        saveSqlTable("supplier");
        var source = entityNode("com.example.Supplier");

        String code = """
            package com.example;
            import jakarta.persistence.Entity;
            @Entity
            class Supplier {}
            """;
        var cu = StaticJavaParser.parse(code);
        new EdgeExtractor(Project.CRUD, store, List.of(), Set.of("supplier")).extract(cu, source);

        assertThat(store.findEdgesFrom(source.id()))
            .anyMatch(e -> e.type() == EdgeType.REFERENCES_TABLE && e.targetId().equals("sql:supplier"));
    }

    @Test
    void entityWithTrailingEntitySuffixStripsIt() {
        saveSqlTable("supplier");
        var source = entityNode("com.example.SupplierEntity");

        String code = """
            package com.example;
            import jakarta.persistence.Entity;
            @Entity
            class SupplierEntity {}
            """;
        var cu = StaticJavaParser.parse(code);
        new EdgeExtractor(Project.CRUD, store, List.of(), Set.of("supplier")).extract(cu, source);

        assertThat(store.findEdgesFrom(source.id()))
            .anyMatch(e -> e.type() == EdgeType.REFERENCES_TABLE && e.targetId().equals("sql:supplier"));
    }

    // ---- Java: string literal / text block scans ----

    @Test
    void nativeQueryLiteralYieldsEdge() {
        saveSqlTable("supplier");
        var source = entityNode("com.example.SupplierRepository");

        String code = """
            package com.example;
            interface SupplierRepository {
                @Query(value = "SELECT * FROM supplier s", nativeQuery = true)
                Object findAll();
            }
            """;
        var cu = StaticJavaParser.parse(code);
        new EdgeExtractor(Project.CRUD, store, List.of(), Set.of("supplier")).extract(cu, source);

        assertThat(store.findEdgesFrom(source.id()))
            .anyMatch(e -> e.type() == EdgeType.REFERENCES_TABLE && e.targetId().equals("sql:supplier"));
    }

    @Test
    void textBlockJoinYieldsEdge() {
        saveSqlTable("supplier");
        var source = entityNode("com.example.SupplierRepository");

        String code = """
            package com.example;
            interface SupplierRepository {
                default String sql() {
                    return \"\"\"
                        SELECT o.* FROM orders o
                        JOIN supplier s ON s.id = o.supplier_id
                        \"\"\";
                }
            }
            """;
        var cu = StaticJavaParser.parse(code);
        new EdgeExtractor(Project.CRUD, store, List.of(), Set.of("supplier")).extract(cu, source);

        assertThat(store.findEdgesFrom(source.id()))
            .anyMatch(e -> e.type() == EdgeType.REFERENCES_TABLE && e.targetId().equals("sql:supplier"));
    }

    @Test
    void jpqlEntityNameDoesNotMatchSnakeCaseTable() {
        // The regex matches JPQL "FROM SalesOrder" the same as any other FROM clause, but the
        // candidate is lowercased with no underscore insertion, so "salesorder" never equals the
        // real snake_case table name "sales_order" — no edge, by construction, not by a special case.
        saveSqlTable("sales_order");
        var source = entityNode("com.example.SalesOrderRepository");

        String code = """
            package com.example;
            interface SalesOrderRepository {
                @Query("SELECT s FROM SalesOrder s")
                Object findAll();
            }
            """;
        var cu = StaticJavaParser.parse(code);
        new EdgeExtractor(Project.CRUD, store, List.of(), Set.of("sales_order")).extract(cu, source);

        assertThat(store.findEdgesFrom(source.id()))
            .noneMatch(e -> e.type() == EdgeType.REFERENCES_TABLE);
    }

    @Test
    void tableAnnotationWithNoMatchingSqlNodeYieldsNoEdgeAndNoNode() {
        var source = entityNode("com.example.Ghost");

        String code = """
            package com.example;
            import jakarta.persistence.Table;
            @Table(name = "ghost")
            class Ghost {}
            """;
        var cu = StaticJavaParser.parse(code);
        // Known table set does not contain "ghost" (no SQL_TABLE node was ever saved for it).
        new EdgeExtractor(Project.CRUD, store, List.of(), Set.of("supplier")).extract(cu, source);

        assertThat(store.findEdgesFrom(source.id()))
            .noneMatch(e -> e.type() == EdgeType.REFERENCES_TABLE);
        assertThat(store.findNodeById("sql:ghost")).isEmpty();
    }

    @Test
    void sameTableNamedTwiceInOneClassYieldsOneEdge() {
        saveSqlTable("supplier");
        var source = entityNode("com.example.SupplierRepository");

        String code = """
            package com.example;
            interface SupplierRepository {
                @Query("SELECT * FROM supplier")
                Object findAll();
                @Query("SELECT * FROM supplier WHERE id = 1")
                Object findOne();
            }
            """;
        var cu = StaticJavaParser.parse(code);
        new EdgeExtractor(Project.CRUD, store, List.of(), Set.of("supplier")).extract(cu, source);

        long count = store.findEdgesFrom(source.id()).stream()
            .filter(e -> e.type() == EdgeType.REFERENCES_TABLE && e.targetId().equals("sql:supplier"))
            .count();
        assertThat(count).isEqualTo(1);
    }

    // ---- Python: driven through ProjectIndexer so KnownNames comes from a real SQL pass ----

    @Test
    void pythonFunctionLiteralYieldsEdgeFromFunctionNode() throws Exception {
        var proj = tempDir.resolve("py-table-proj");
        var migrations = proj.resolve("db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__init.sql"), """
            CREATE TABLE supplier (id INT PRIMARY KEY);
            """);
        var pyFile = proj.resolve("app.py");
        Files.writeString(pyFile, """
            def get_suppliers():
                return "select * from supplier"
            """);

        var indexer = new ProjectIndexer(store);
        indexer.indexFull(proj);

        var methodNode = store.findNodesByType(NodeType.METHOD).stream()
            .filter(n -> n.name().equals("get_suppliers"))
            .findFirst().orElseThrow();

        assertThat(store.findEdgesFrom(methodNode.id()))
            .anyMatch(e -> e.type() == EdgeType.REFERENCES_TABLE && e.targetId().equals("sql:supplier"));
    }

    @Test
    void pythonModuleLevelLiteralYieldsEdgeFromModuleNode() throws Exception {
        var proj = tempDir.resolve("py-table-module-proj");
        var migrations = proj.resolve("db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__init.sql"), """
            CREATE TABLE supplier (id INT PRIMARY KEY);
            """);
        var pyFile = proj.resolve("app.py");
        Files.writeString(pyFile, """
            QUERY = "select * from supplier"
            """);

        var indexer = new ProjectIndexer(store);
        indexer.indexFull(proj);

        var moduleNode = store.findNodesByType(NodeType.PY_MODULE).stream()
            .filter(n -> n.filePath().equals("app.py"))
            .findFirst().orElseThrow();

        assertThat(store.findEdgesFrom(moduleNode.id()))
            .anyMatch(e -> e.type() == EdgeType.REFERENCES_TABLE && e.targetId().equals("sql:supplier"));
    }
}
