package com.codenavigator.indexer;

import com.codenavigator.graph.Edge;
import com.codenavigator.graph.EdgeType;
import com.codenavigator.graph.Node;
import com.codenavigator.graph.NodeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlIndexerTest {

    private final SqlIndexer indexer = new SqlIndexer();

    /** prepare() over every file, then collect indexFile()+extractEdges() for each. */
    private List<Node> index(Path root, Path... files) {
        var all = List.of(files);
        indexer.prepare(all, root);
        var nodes = new ArrayList<Node>();
        for (Path f : all) nodes.addAll(indexer.indexFile(f, root));
        return nodes;
    }

    private List<Edge> edges(Path root, Path file, List<Node> allNodes) {
        var filePath = root.relativize(file).toString();
        var fileNodes = allNodes.stream().filter(n -> n.filePath().equals(filePath)).toList();
        return indexer.extractEdges(file, fileNodes, KnownNames.empty());
    }

    @Test
    void createTableProducesSqlTableNode(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("V1__init.sql");
        Files.writeString(f, "CREATE TABLE foo (id INT PRIMARY KEY);\n");
        var nodes = index(dir, f);
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().type()).isEqualTo(NodeType.SQL_TABLE);
        assertThat(nodes.getFirst().name()).isEqualTo("foo");
    }

    @Test
    void createViewProducesDerivesFromEdgesForFromAndJoin(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("V1__init.sql");
        Files.writeString(f, """
            CREATE TABLE foo (id INT PRIMARY KEY);
            CREATE TABLE bar (id INT PRIMARY KEY);
            CREATE VIEW v AS SELECT * FROM foo JOIN bar b ON b.id = foo.id;
            """);
        var nodes = index(dir, f);
        var view = nodes.stream().filter(n -> n.type() == NodeType.SQL_VIEW).findFirst().orElseThrow();
        assertThat(view.name()).isEqualTo("v");

        var edges = edges(dir, f, nodes);
        assertThat(edges).filteredOn(e -> e.type() == EdgeType.DERIVES_FROM
            && e.sourceId().equals("sql:v"))
            .extracting(Edge::targetId)
            .containsExactlyInAnyOrder("sql:foo", "sql:bar");
    }

    @Test
    void inlineAndAlterForeignKeyDedupeToOneEdge(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("V1__init.sql");
        Files.writeString(f, """
            CREATE TABLE bar (id INT PRIMARY KEY);
            CREATE TABLE foo (id INT PRIMARY KEY, bar_id INT REFERENCES bar(id));
            ALTER TABLE foo ADD CONSTRAINT fk FOREIGN KEY (bar_id) REFERENCES bar (id);
            """);
        var nodes = index(dir, f);
        var edges = edges(dir, f, nodes);
        assertThat(edges).filteredOn(e -> e.type() == EdgeType.REFERENCES_FK).hasSize(1);
        assertThat(edges).anyMatch(e -> e.type() == EdgeType.REFERENCES_FK
            && e.sourceId().equals("sql:foo") && e.targetId().equals("sql:bar"));
    }

    @Test
    void flywayNumericOrderMeansV2ProcessedBeforeV10(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("V2__create.sql"), "CREATE TABLE foo (id INT);\n");
        Files.writeString(dir.resolve("V10__rename_and_drop.sql"), """
            ALTER TABLE foo RENAME TO renamed_foo;
            CREATE TABLE ghost (id INT);
            DROP TABLE ghost;
            """);

        var v2 = dir.resolve("V2__create.sql");
        var v10 = dir.resolve("V10__rename_and_drop.sql");
        var nodes = index(dir, v10, v2); // order passed to index() must not matter; prepare() re-sorts

        assertThat(nodes).extracting("name").containsExactly("renamed_foo");
        assertThat(nodes).extracting("type").containsExactly(NodeType.SQL_TABLE);
    }

    @Test
    void quotedAndBareNameFoldToOneNode(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("V1__init.sql");
        Files.writeString(f, """
            CREATE TABLE "Foo" (id INT);
            CREATE VIEW v AS SELECT * FROM foo;
            """);
        var nodes = index(dir, f);
        assertThat(nodes).filteredOn(n -> n.type() == NodeType.SQL_TABLE).extracting("name")
            .containsExactly("foo");

        var edges = edges(dir, f, nodes);
        assertThat(edges).anyMatch(e -> e.type() == EdgeType.DERIVES_FROM
            && e.sourceId().equals("sql:v") && e.targetId().equals("sql:foo"));
    }

    @Test
    void dollarQuotedBlockDoesNotSplitOnInnerSemicolons(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("V1__init.sql");
        Files.writeString(f, """
            CREATE TABLE foo (id INT);
            DO $$
            BEGIN
              UPDATE foo SET id = 1;
              INSERT INTO foo (id) VALUES (2);
            END $$;
            """);
        // No exception, and the DO block produces no spurious SQL node.
        var nodes = index(dir, f);
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().name()).isEqualTo("foo");
    }

    @Test
    void createFunctionWithDollarBodyProducesRoutineWithDerivesFrom(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("V1__init.sql");
        Files.writeString(f, """
            CREATE TABLE foo (id INT);
            CREATE FUNCTION count_foo() RETURNS INT AS $$
              SELECT COUNT(*) FROM foo
            $$ LANGUAGE sql;
            """);
        var nodes = index(dir, f);
        var routine = nodes.stream().filter(n -> n.type() == NodeType.SQL_ROUTINE).findFirst().orElseThrow();
        assertThat(routine.name()).isEqualTo("count_foo");

        var edges = edges(dir, f, nodes);
        assertThat(edges).anyMatch(e -> e.type() == EdgeType.DERIVES_FROM
            && e.sourceId().equals("sql:count_foo") && e.targetId().equals("sql:foo"));
    }

    @Test
    void commentedCreateTableProducesNoNode(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("V1__init.sql");
        Files.writeString(f, """
            -- CREATE TABLE ghost (id INT);
            CREATE TABLE foo (id INT);
            """);
        var nodes = index(dir, f);
        assertThat(nodes).extracting("name").containsExactly("foo");
    }

    @Test
    void schemaQualifiedNameKeepsBareNameAndQualifiedName(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("V1__init.sql");
        Files.writeString(f, "CREATE TABLE schema.foo (id INT);\n");
        var nodes = index(dir, f);
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().name()).isEqualTo("foo");
        assertThat(nodes.getFirst().qualifiedName()).isEqualTo("schema.foo");
    }

    @Test
    void createOrReplaceViewTwiceKeepsOneNodeWithLatestDerivesFrom(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("V1__init.sql");
        Files.writeString(f, """
            CREATE TABLE foo (id INT);
            CREATE TABLE bar (id INT);
            CREATE OR REPLACE VIEW v AS SELECT * FROM foo;
            CREATE OR REPLACE VIEW v AS SELECT * FROM bar;
            """);
        var nodes = index(dir, f);
        assertThat(nodes).filteredOn(n -> n.type() == NodeType.SQL_VIEW).hasSize(1);

        var edges = edges(dir, f, nodes);
        assertThat(edges).filteredOn(e -> e.type() == EdgeType.DERIVES_FROM && e.sourceId().equals("sql:v"))
            .extracting(Edge::targetId)
            .containsExactly("sql:bar");
    }
}
