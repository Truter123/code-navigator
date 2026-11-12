package com.codenavigator.mcp;

import com.codenavigator.cli.ProjectPaths;
import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.GraphTraversal;
import com.codenavigator.graph.NodeType;
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
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The single-symbol guard counts what depends on the symbol, not everything within reach of it. */
class GuardDirectionTest {

    private static final String TARGET = """
        package com.y;

        import org.lib.Thing;

        public class Target {
            private Helper helper = new Helper();

            public int run() {
                return helper.help();
            }
        }
        """;
    private static final String HELPER = """
        package com.y;

        public class Helper {
            public int help() {
                return 1;
            }
        }
        """;
    private static final String CALLER = """
        package com.y;

        public class Caller {
            private Target target = new Target();

            public int go() {
                return target.run();
            }

            public int idle() {
                return 0;
            }
        }
        """;
    private static final String BYSTANDER = """
        package com.y;

        public class Bystander {
            private Helper helper = new Helper();

            public int work() {
                return helper.help();
            }
        }
        """;
    private static final String TARGET_TEST = """
        package com.y;

        public class TargetTest {
            private Target target = new Target();

            public void runs() {
                target.run();
            }
        }
        """;
    private static final String LONELY = """
        package com.y;

        public class Lonely {
            public int alone() {
                return 1;
            }
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
        write("build.gradle", "dependencies {\n    implementation 'org.lib:lib:1.0'\n}\n");
        write("src/main/java/com/y/Target.java", TARGET);
        write("src/main/java/com/y/Helper.java", HELPER);
        write("src/main/java/com/y/Caller.java", CALLER);
        write("src/main/java/com/y/Bystander.java", BYSTANDER);
        write("src/main/java/com/y/Lonely.java", LONELY);
        write("src/test/java/com/y/TargetTest.java", TARGET_TEST);
        Files.createDirectories(ProjectPaths.codeGraphDir(root));
        try (var store = new GraphStore(ProjectPaths.graphDb(root))) {
            new ProjectIndexer(store).indexFull(root);
        }
    }

    @AfterEach
    void tearDown() { defaultStore.close(); }

    private void write(String rel, String content) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private String call(String tool, Map<String, Object> more) {
        var args = new HashMap<String, Object>(more);
        args.put("projectPath", root.toString());
        return server.reply(tool, args);
    }

    private String guard(String symbol) { return call("cg_guard", Map.of("symbol", symbol)); }

    private static String line(String reply, String prefix) {
        return reply.lines().filter(l -> l.startsWith(prefix)).findFirst().orElse("");
    }

    private static int count(String reply) {
        var m = Pattern.compile("### Blast radius \\(depth 2\\): (\\d+) node").matcher(reply);
        assertThat(m.find()).as(reply).isTrue();
        return Integer.parseInt(m.group(1));
    }

    private int stored(String... ids) {
        try (var store = new GraphStore(ProjectPaths.graphDb(root))) {
            for (String id : ids) assertThat(store.findNodeById(id)).as(id).isPresent();
        }
        return ids.length;
    }

    @Test
    void aClassCountsOnlyItsDependents() {
        int expected = stored("com.y.Caller", "com.y.Caller#go()", "com.y.TargetTest", "com.y.TargetTest#runs()");
        try (var store = new GraphStore(ProjectPaths.graphDb(root))) {
            assertThat(store.findNodesByName("Thing").stream().anyMatch(n -> n.type() == NodeType.LIBRARY))
                .as("fixture has a LIBRARY node").isTrue();
        }

        String reply = guard("Target");

        assertThat(count(reply)).isEqualTo(expected);
        String other = line(reply, "Other:");
        assertThat(other).doesNotContain("LIBRARY");
        assertThat(other.replace("Other:", "").split(",\\s*")).hasSize(2);
        assertThat(line(reply, "Test sources:")).isEqualTo("Test sources: 2");
        assertThat(reply).doesNotContain("Helper").doesNotContain("Bystander").doesNotContain("idle");
    }

    @Test
    void aMethodCountsItsCallersNotTheirSiblings() {
        int expected = stored("com.y.Caller#go()", "com.y.TargetTest#runs()");

        String reply = guard("Target#run()");

        assertThat(count(reply)).isEqualTo(expected);
        assertThat(line(reply, "Test sources:")).isEqualTo("Test sources: 1");
        assertThat(line(reply, "Other:")).isEqualTo("Other: METHOD 1");
        assertThat(reply).doesNotContain("idle").doesNotContain("Helper").doesNotContain("Bystander");
    }

    @Test
    void theFooterCallListsTheDependentsItPromises() {
        String reply = guard("Target");
        var m = Pattern.compile("More: cg_related\\(symbol, direction in, depth 2, limit (\\d+)\\)").matcher(reply);
        assertThat(m.find()).as(reply).isTrue();
        int rows = Integer.parseInt(m.group(1));

        String related = call("cg_related", Map.of("symbol", "Target", "direction", "in", "depth", 2, "limit", rows));

        assertThat(related).contains(rows + " incoming found");
        assertThat(related).contains("Caller").contains("TargetTest");
        assertThat(related).doesNotContain("Helper").doesNotContain("Bystander").doesNotContain("more.");
        assertThat(rows).isEqualTo(List.of("Caller", "TargetTest").size());
    }

    private CodeNavigatorMcpServer storeServer(GraphStore store) {
        var traversal = new GraphTraversal(store);
        return new CodeNavigatorMcpServer(store, traversal, new SearchService(store, traversal));
    }

    private static void node(GraphStore store, String id, NodeType type) {
        String name = id.substring(id.lastIndexOf('.') + 1);
        store.saveNode(new com.codenavigator.graph.Node(id, type, name, id,
            "src/main/java/com/z/" + name + ".java", 1, "class " + name, 0));
    }

    @Test
    void aDomainEventDependentsAreTheAggregateAndTheHandlersLinkedToIt() {
        try (var store = new GraphStore(tempDir.resolve("ddd.db"))) {
            node(store, "com.z.Shipped", NodeType.DOMAIN_EVENT);
            node(store, "com.z.Order", NodeType.AGGREGATE);
            node(store, "com.z.ShipHandler", NodeType.COMMAND_HANDLER);
            node(store, "com.z.ShippedProjection", NodeType.PROJECTION_HANDLER);
            node(store, "com.z.Unrelated", NodeType.COMMAND_HANDLER);
            store.saveEdge(new com.codenavigator.graph.Edge("1", com.codenavigator.graph.EdgeType.EMITS_EVENT, "com.z.Order", "com.z.Shipped"));
            store.saveEdge(new com.codenavigator.graph.Edge("2", com.codenavigator.graph.EdgeType.APPLIES_EVENT, "com.z.ShipHandler", "com.z.Shipped"));
            store.saveEdge(new com.codenavigator.graph.Edge("3", com.codenavigator.graph.EdgeType.PROJECTS_EVENT, "com.z.ShippedProjection", "com.z.Shipped"));
            store.setConfig("tier", "DDD");
            var srv = storeServer(store);

            String reply = srv.reply("cg_guard", Map.of("symbol", "Shipped"));

            assertThat(reply).contains("### Blast radius (depth 2): 3 node(s)");
            String significant = reply.substring(reply.indexOf("DDD-significant (3):"));
            assertThat(significant).contains("Order.java:1 Order AGGREGATE")
                .contains("ShipHandler.java:1 ShipHandler COMMAND_HANDLER")
                .contains("ShippedProjection.java:1 ShippedProjection PROJECTION_HANDLER")
                .doesNotContain("Unrelated");

            var m = Pattern.compile("More: cg_related\\(symbol, direction in, depth 2, limit (\\d+)\\)").matcher(reply);
            assertThat(m.find()).as(reply).isTrue();
            String related = srv.reply("cg_related", Map.of("symbol", "Shipped", "direction", "in",
                "depth", 2, "limit", Integer.parseInt(m.group(1))));
            assertThat(related).contains("3 incoming found").contains("Order.java:1 Order AGGREGATE")
                .contains("ShipHandler.java:1 ShipHandler COMMAND_HANDLER")
                .contains("ShippedProjection.java:1 ShippedProjection PROJECTION_HANDLER");
        }
    }

    @Test
    void relatedFollowsExtendsInItsOwnDirection() {
        try (var store = new GraphStore(tempDir.resolve("ext.db"))) {
            node(store, "com.z.Base", NodeType.CLASS);
            node(store, "com.z.Derived", NodeType.CLASS);
            store.saveEdge(new com.codenavigator.graph.Edge("1", com.codenavigator.graph.EdgeType.EXTENDS, "com.z.Derived", "com.z.Base"));
            var srv = storeServer(store);

            assertThat(srv.reply("cg_related", Map.of("symbol", "Base", "direction", "in")))
                .contains("1 incoming found").contains("Derived.java:1 Derived CLASS");
            assertThat(srv.reply("cg_related", Map.of("symbol", "Derived", "direction", "out")))
                .contains("1 outgoing found").contains("Base.java:1 Base CLASS");
        }
    }

    @Test
    void aSymbolNothingDependsOnHasNoFooter() {
        String reply = guard("Lonely");

        assertThat(reply).contains("### Blast radius (depth 2): 0 node(s)").doesNotContain("More:");
    }
}
