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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** LIBRARY nodes (one per imported library type) must not flood related, hotspot or dead-code replies. */
class LibraryNodesNoiseTest {

    @TempDir Path tempDir;
    private GraphStore store;
    private CodeNavigatorMcpServer server;

    @BeforeEach
    void setUp() {
        store = new GraphStore(tempDir.resolve("test.db"));
        var traversal = new GraphTraversal(store);
        server = new CodeNavigatorMcpServer(store, traversal, new SearchService(store, traversal));
        var deps = List.of(new Dependency("org.lib", "lib", "1.0"));
        add("com.x.Alpha", "src/main/java/com/x/Alpha.java", deps, "org.lib.Thing", "org.lib.Extra");
        add("com.x.Beta", "src/main/java/com/x/Beta.java", deps, "org.lib.Thing");
        add("com.x.AlphaTest", "src/test/java/com/x/AlphaTest.java", deps, "org.lib.Thing", "org.lib.OnlyTests");
    }

    private void add(String fqn, String file, List<Dependency> deps, String... imports) {
        String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
        var node = new Node(fqn, NodeType.CLASS, simple, fqn, file, 1, "", 0L);
        store.saveNode(node);
        var src = new StringBuilder("package com.x;\n");
        for (String i : imports) src.append("import ").append(i).append(";\n");
        src.append("class ").append(simple).append(" {}\n");
        new EdgeExtractor(Project.CRUD, store, deps).extract(StaticJavaParser.parse(src.toString()), node);
    }

    @AfterEach
    void tearDown() { store.close(); }

    @Test
    void defaultRelatedListsNoLibraryAndDoesNotWalkThroughOne() {
        String reply = server.handleCgRelated(Map.of("symbol", "Alpha"));

        assertThat(reply).doesNotContain("LIBRARY").doesNotContain("Beta");
    }

    @Test
    void explicitDirectionOutKeepsTheStartNodesOwnLibraries() {
        String reply = server.handleCgRelated(Map.of("symbol", "Alpha", "direction", "out", "depth", 1));

        assertThat(reply).contains("Thing LIBRARY").contains("Extra LIBRARY");
    }

    @Test
    void hotspotsNeverListALibrary() {
        assertThat(server.handleCgHealth(Map.of("kind", "hotspots"))).doesNotContain("LIBRARY");
    }

    @Test
    void aLibraryOnlyTestsImportIsNotADeadCodeCandidate() {
        assertThat(server.handleCgHealth(Map.of("kind", "dead"))).doesNotContain("OnlyTests").doesNotContain("LIBRARY");
    }
}
