package com.codenavigator.mcp;

import com.codenavigator.cli.ProjectPaths;
import com.codenavigator.export.ExportService;
import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.GraphTraversal;
import com.codenavigator.graph.Node;
import com.codenavigator.graph.NodeType;
import com.codenavigator.graph.PackageNames;
import com.codenavigator.indexer.ProjectIndexer;
import com.codenavigator.search.SearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A nested Java type is {@code <package>.<Outer>.<Inner>}: its package is the outer type's package,
 * it answers to {@code Outer.Inner}, and an index without the format marker still answers.
 */
class NestedTypeIdsTest {

    private static final String A = """
        package p;

        public class A {
            public static class Chain {
                public Chain next() { return this; }
            }

            public static class B {
                public static class C {
                    public int value() { return 1; }
                }
            }
        }
        """;
    private static final String Z = """
        package p;

        public class Z {
            public static class Chain {
                public Chain next() { return this; }
            }
        }
        """;
    private static final String USER = """
        package q;

        import p.A;

        public class User {
            private A a = new A();
        }
        """;

    @TempDir Path tempDir;
    private Path root;
    private GraphStore defaultStore;
    private CodeNavigatorMcpServer server;

    @BeforeEach
    void setUp() throws Exception {
        defaultStore = new GraphStore(tempDir.resolve("default.db"));
        var traversal = new GraphTraversal(defaultStore);
        server = new CodeNavigatorMcpServer(defaultStore, traversal, new SearchService(defaultStore, traversal));
        root = tempDir.resolve("proj");
        write(root, "build.gradle", "plugins { id 'java' }\n");
        write(root, "src/main/java/p/A.java", A);
        write(root, "src/main/java/p/Z.java", Z);
        write(root, "src/main/java/q/User.java", USER);
        Files.createDirectories(ProjectPaths.codeGraphDir(root));
        try (var store = new GraphStore(ProjectPaths.graphDb(root))) {
            new ProjectIndexer(store).indexFull(root);
        }
    }

    @AfterEach
    void tearDown() { defaultStore.close(); }

    private static void write(Path base, String rel, String content) throws Exception {
        Path p = base.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private String call(Path project, String tool, Map<String, Object> more) {
        var args = new HashMap<String, Object>(more);
        args.put("projectPath", project.toString());
        return server.reply(tool, args);
    }

    private String node(String symbol) { return call(root, "cg_node", Map.of("symbol", symbol)); }

    // ---- A: package of a nested type ----

    @Test
    void packagesListTheOuterTypesPackageNotTheOuterType() {
        String reply = call(root, "cg_health", Map.of("kind", "packages", "limit", 50));

        assertThat(reply).contains("(2 packages)").contains("- p (").contains("- q (");
        assertThat(reply).doesNotContain("p.A").doesNotContain("p.A.B").doesNotContain("p.Z");
    }

    @Test
    void exportGroupsNestedTypesInTheOuterTypesPackage() {
        try (var store = new GraphStore(ProjectPaths.graphDb(root))) {
            var service = new ExportService();
            String mermaid = service.toMermaid(store.getAllNodes(), store.getAllEdges());
            String plantUml = service.toPlantUml(store.getAllNodes(), store.getAllEdges());

            assertThat(mermaid).contains("subgraph p\n").doesNotContain("subgraph p.A");
            assertThat(plantUml).contains("package \"p\" {").doesNotContain("package \"p.A");
        }
    }

    @Test
    void otherLanguagesKeepTheirPackage() {
        var module = new Node("tools.build", NodeType.PY_MODULE, "build", "tools.build", "tools/build.py", 1, "", 0);
        var pyClass = new Node("tools.build.Builder", NodeType.PY_CLASS, "Builder", "tools.build.Builder", "tools/build.py", 2, "", 0);
        var ts = new Node("FooService", NodeType.FE_SERVICE, "FooService", "app.FooService", "src/app/foo.service.ts", 1, "", 0);
        var java = new Node("p.A", NodeType.CLASS, "A", "p.A", "src/p/A.java", 1, "", 0);
        var inner = new Node("p.A.B", NodeType.CLASS, "B", "p.A.B", "src/p/A.java", 2, "", 0);
        var innermost = new Node("p.A.B.C", NodeType.CLASS, "C", "p.A.B.C", "src/p/A.java", 3, "", 0);
        var packages = new PackageNames(List.of(module, pyClass, ts, java, inner, innermost));

        assertThat(packages.of(pyClass.qualifiedName())).isEqualTo("tools.build");
        assertThat(packages.of(module.qualifiedName())).isEqualTo("tools");
        assertThat(packages.of(ts.qualifiedName())).isEqualTo("app");
        assertThat(packages.of("p.A")).isEqualTo("p");
        assertThat(packages.of("p.A.B")).isEqualTo("p");
        assertThat(packages.of("p.A.B.C")).isEqualTo("p");
    }

    // ---- B: Outer.Inner symbols ----

    @Test
    void outerDotInnerResolvesToTheNestedType() {
        assertThat(node("A.Chain")).contains("p.A.Chain");
        assertThat(node("p.A.Chain")).contains("p.A.Chain");
        assertThat(node("p.A.B.C")).contains("p.A.B.C");
        assertThat(node("B.C")).contains("p.A.B.C");
    }

    @Test
    void bareInnerNameNamesTheChosenOneAndTheRunnerUp() {
        String reply = node("Chain");

        assertThat(reply).contains("_Resolved to").contains("p.A.Chain").contains("p.Z.Chain");
    }

    @Test
    void outerDotInnerMethodResolves() {
        assertThat(node("A.Chain#next()")).contains("p.A.Chain#next()");
        assertThat(node("A.Chain#next")).contains("p.A.Chain#next()");
        assertThat(node("p.A.Chain#next")).contains("p.A.Chain#next()");
        assertThat(node("Z.Chain#next")).contains("p.Z.Chain#next()").doesNotContain("p.A.Chain");
    }

    @Test
    void aPartialSegmentDoesNotMatch() {
        assertThat(node("XA.Chain")).doesNotContain("p.A.Chain");
        assertThat(node("XA.Chain#next")).doesNotContain("p.A.Chain");
        assertThat(node("ter.Chain")).doesNotContain("p.Z.Chain");
    }

    // ---- C: a store without the format marker still answers ----

    @Test
    void aStoreWithoutTheMarkerAnswersToolCalls() throws Exception {
        Path old = tempDir.resolve("old");
        Files.createDirectories(ProjectPaths.codeGraphDir(old));
        try (var store = new GraphStore(ProjectPaths.graphDb(old))) {
            store.saveNode(new Node("p.Chain", NodeType.CLASS, "Chain", "p.Chain", "src/main/java/p/A.java", 3, "", 0));
        }

        String map = call(old, "cg_map", Map.of());
        String node = call(old, "cg_node", Map.of("symbol", "p.Chain"));

        assertThat(map).doesNotContain("No code-navigator index").doesNotContain("Error");
        assertThat(node).contains("p.Chain").doesNotContain("Error");
    }
}
