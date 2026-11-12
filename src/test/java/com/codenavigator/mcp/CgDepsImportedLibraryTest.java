package com.codenavigator.mcp;

import com.codenavigator.graph.*;
import com.codenavigator.indexer.Dependency;
import com.codenavigator.indexer.EdgeExtractor;
import com.codenavigator.search.SearchService;
import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A library whose group id differs from its package ({@code info.picocli} / {@code picocli}) still counts as used. */
class CgDepsImportedLibraryTest {

    @TempDir Path tempDir;
    private GraphStore store;
    private CodeNavigatorMcpServer server;

    @BeforeEach
    void setUp() {
        store = new GraphStore(tempDir.resolve("test.db"));
        var traversal = new GraphTraversal(store);
        server = new CodeNavigatorMcpServer(store, traversal, new SearchService(store, traversal));
    }

    @AfterEach
    void tearDown() { store.close(); }

    @Test
    void aNestedTypeImportedAndUsedAsAnnotationCountsTheLibraryAsUsed() {
        var source = new Node("com.example.cli.RunCommand", NodeType.CLASS, "RunCommand",
            "com.example.cli.RunCommand", "RunCommand.java", 1, "", 0L);
        store.saveNode(source);
        var cu = StaticJavaParser.parse("""
            package com.example.cli;
            import picocli.CommandLine.Command;
            @Command(name = "run")
            class RunCommand {}
            """);
        var deps = java.util.List.of(
            new Dependency("info.picocli", "picocli", "4.7.6"),
            new Dependency("org.xerial", "sqlite-jdbc", "3.47.2.0"));
        new EdgeExtractor(Project.CRUD, store, deps).extract(cu, source);
        store.setConfig("dependencies", "[\"info.picocli:picocli:4.7.6\",\"org.xerial:sqlite-jdbc:3.47.2.0\"]");

        var result = server.handleCgDeps(Map.of());

        assertThat(result).contains("- **info.picocli:picocli** (4.7.6) — 1 type(s) used");
        assertThat(result).contains("1 declared dependency with no used type: org.xerial:sqlite-jdbc");
        assertThat(result).doesNotContain("no used type: info.picocli");
    }
}
