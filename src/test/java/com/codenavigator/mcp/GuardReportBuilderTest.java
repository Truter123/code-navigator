package com.codenavigator.mcp;

import com.codenavigator.graph.*;
import com.codenavigator.search.SearchService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class GuardReportBuilderTest {

    @TempDir Path tempDir;
    private GraphStore graphStore;
    private GraphTraversal traversal;

    @BeforeEach
    void setUp() {
        graphStore = new GraphStore(tempDir.resolve("graph.db"));
        traversal = new GraphTraversal(graphStore);
        buildCqrsGraph();
    }

    @AfterEach
    void tearDown() {
        graphStore.close();
    }

    private void buildCqrsGraph() {
        graphStore.saveNode(new Node("com.OrderController", NodeType.CONTROLLER, "OrderController",
            "com.OrderController", "Ctrl.java", 1, "@RestController class OrderController", 0));
        graphStore.saveNode(new Node("com.CreateOrderCommand", NodeType.COMMAND, "CreateOrderCommand",
            "com.CreateOrderCommand", "Cmd.java", 1, "record CreateOrderCommand", 0));
        graphStore.saveNode(new Node("com.CreateOrderCommandHandler", NodeType.COMMAND_HANDLER,
            "CreateOrderCommandHandler", "com.CreateOrderCommandHandler", "H.java", 1, "class Handler", 0));
        graphStore.saveNode(new Node("com.Order", NodeType.AGGREGATE, "Order",
            "com.Order", "Order.java", 1, "class Order extends AggregateRoot", 0));
        graphStore.saveNode(new Node("com.OrderCreatedEvent", NodeType.DOMAIN_EVENT, "OrderCreatedEvent",
            "com.OrderCreatedEvent", "Event.java", 1, "class OrderCreatedEvent", 0));
        graphStore.saveNode(new Node("com.OrderViewProjectionHandler", NodeType.PROJECTION_HANDLER,
            "OrderViewProjectionHandler", "com.OrderViewProjectionHandler", "Proj.java", 1, "class Proj", 0));
        graphStore.saveNode(new Node("com.OrderView", NodeType.VIEW, "OrderView",
            "com.OrderView", "View.java", 1, "@Entity class OrderView", 0));

        graphStore.saveEdge(new Edge("e1", EdgeType.DISPATCHES_COMMAND, "com.OrderController", "com.CreateOrderCommand"));
        graphStore.saveEdge(new Edge("e2", EdgeType.HANDLES, "com.CreateOrderCommandHandler", "com.CreateOrderCommand"));
        graphStore.saveEdge(new Edge("e3", EdgeType.LOADS_AGGREGATE, "com.CreateOrderCommandHandler", "com.Order"));
        graphStore.saveEdge(new Edge("e4", EdgeType.EMITS_EVENT, "com.Order", "com.OrderCreatedEvent"));
        graphStore.saveEdge(new Edge("e5", EdgeType.PROJECTS_EVENT, "com.OrderViewProjectionHandler", "com.OrderCreatedEvent"));
        graphStore.saveEdge(new Edge("e6", EdgeType.UPDATES_VIEW, "com.OrderViewProjectionHandler", "com.OrderView"));

        graphStore.setConfig("tier", "DDD");
    }


    @Test
    void build_containsBlastRadiusSection() {
        var builder = new GuardReportBuilder(graphStore, traversal);
        var report = builder.build("com.Order", 2);
        assertThat(report).contains("## Guard");
        assertThat(report).contains("Blast radius");
        // DDD-significant neighbours are named in full.
        assertThat(report).contains("OrderCreatedEvent");
        assertThat(report).contains("CreateOrderCommandHandler");
    }


    @Test
    void build_summarisesNonSignificantNodesRatherThanListingThem() {
        var builder = new GuardReportBuilder(graphStore, traversal);
        var report = builder.build("com.Order", 2);
        assertThat(report).contains("DDD-significant");
        assertThat(report).contains("More: cg_related").doesNotContain("Full list").doesNotContain("cg_impact");
    }

    @Test
    void build_countsTestSourcesSeparately() {
        graphStore.saveNode(new Node("com.OrderTest", NodeType.CLASS, "OrderTest",
            "com.OrderTest", "src/test/java/com/OrderTest.java", 1, "class OrderTest", 0));
        graphStore.saveEdge(new Edge("t1", EdgeType.CALLS_METHOD, "com.OrderTest", "com.Order"));

        var builder = new GuardReportBuilder(graphStore, traversal);
        var report = builder.build("com.Order", 1);

        assertThat(report).contains("Test sources: 1");
    }

    @Test
    void build_countsRepoRelativeTestDirectoriesAsTestSources() {
        // Node paths are stored relative to the project root: no leading slash, and neither file
        // is named *Test.java.
        graphStore.saveNode(new Node("com.OrderFixtures", NodeType.CLASS, "OrderFixtures",
            "com.OrderFixtures", "src/test/java/com/OrderFixtures.java", 1, "class OrderFixtures", 0));
        graphStore.saveNode(new Node("e2e.Checkout", NodeType.CLASS, "Checkout",
            "e2e.Checkout", "e2e/support/checkout.ts", 1, "class Checkout", 0));
        graphStore.saveEdge(new Edge("t1", EdgeType.CALLS_METHOD, "com.OrderFixtures", "com.Order"));
        graphStore.saveEdge(new Edge("t2", EdgeType.CALLS_METHOD, "e2e.Checkout", "com.Order"));

        var builder = new GuardReportBuilder(graphStore, traversal);
        var report = builder.build("com.Order", 1);

        assertThat(report).contains("Test sources: 2").doesNotContain("Other:");
    }

    @Test
    void build_pointsAtTheCallThatListsTheBlastRadius() {
        var builder = new GuardReportBuilder(graphStore, traversal);
        var report = builder.build("com.Order", 2);

        var pointer = Pattern.compile("More: (cg_\\w+)\\(symbol, depth (\\d+), limit (\\d+)\\)").matcher(report);
        assertThat(pointer.find()).as(report).isTrue();
        assertThat(pointer.group(1)).isEqualTo("cg_related");

        // Run the pointer as printed: the count the guard gave is the limit that shows every row.
        var server = new CodeNavigatorMcpServer(graphStore, traversal, new SearchService(graphStore, traversal));
        var listing = server.handleCgRelated(Map.of("symbol", "com.Order",
            "depth", Integer.parseInt(pointer.group(2)), "limit", Integer.parseInt(pointer.group(3))));

        assertThat(listing).contains(pointer.group(3) + " affected node(s)")
            .contains("OrderCreatedEvent", "CreateOrderCommandHandler", "OrderViewProjectionHandler")
            .doesNotContain("more.");
    }

    @Test
    void build_theMorePointerCountsTheRowsItsCallReturnsNotTheMethodsItLeavesOut() {
        // Three methods in range. cg_related lists a class's blast radius as types, so the limit the
        // guard prints is the number of those rows, not the node count it states above.
        for (String m : new String[] {"a", "b", "c"}) {
            String id = "com.Order#" + m + "()";
            graphStore.saveNode(new Node(id, NodeType.METHOD, m, id, "Order.java", 1, "void " + m + "()", 0));
            graphStore.saveEdge(new Edge("d" + m, EdgeType.DECLARES_METHOD, "com.Order", id));
        }
        var report = new GuardReportBuilder(graphStore, traversal).build("com.Order", 2);

        var pointer = Pattern.compile("(cg_\\w+)\\(symbol, depth (\\d+), limit (\\d+)\\)").matcher(report);
        assertThat(pointer.find()).as(report).isTrue();
        int promised = Integer.parseInt(pointer.group(3));
        var server = new CodeNavigatorMcpServer(graphStore, traversal, new SearchService(graphStore, traversal));
        var listing = server.handleCgRelated(Map.of("symbol", "com.Order",
            "depth", Integer.parseInt(pointer.group(2)), "limit", promised));

        assertThat(listing).contains(promised + " affected node(s)").doesNotContain("more.");
        assertThat(report).contains("Blast radius (depth 2): " + (promised + 3) + " node(s)")
            .contains("methods left out");
    }

    @Test
    void build_whenOnlyMethodsAreInRangeThePointerAsksForEveryGranularity() {
        graphStore.saveNode(new Node("com.Lonely", NodeType.CLASS, "Lonely", "com.Lonely", "Lonely.java", 1, "class Lonely", 0));
        for (String m : new String[] {"x", "y"}) {
            String id = "com.Lonely#" + m + "()";
            graphStore.saveNode(new Node(id, NodeType.METHOD, m, id, "Lonely.java", 2, "void " + m + "()", 0));
            graphStore.saveEdge(new Edge("l" + m, EdgeType.DECLARES_METHOD, "com.Lonely", id));
        }
        var report = new GuardReportBuilder(graphStore, traversal).build("com.Lonely", 1);

        var pointer = Pattern.compile("(cg_\\w+)\\(symbol, depth (\\d+), granularity all, limit (\\d+)\\)").matcher(report);
        assertThat(pointer.find()).as(report).isTrue();
        var server = new CodeNavigatorMcpServer(graphStore, traversal, new SearchService(graphStore, traversal));
        var listing = server.handleCgRelated(Map.of("symbol", "com.Lonely", "depth", 1,
            "granularity", "all", "limit", Integer.parseInt(pointer.group(3))));

        assertThat(listing).contains(pointer.group(3) + " affected node(s)");
    }

    @Test
    void build_listsDddSignificantNodesAsRowsUnderTheirDirectory() {
        graphStore.saveNode(new Node("com.sales.Shipped", NodeType.DOMAIN_EVENT, "Shipped",
            "com.sales.Shipped", "src/main/java/com/sales/Shipped.java", 3, "class Shipped", 0));
        graphStore.saveEdge(new Edge("s1", EdgeType.EMITS_EVENT, "com.Order", "com.sales.Shipped"));

        var builder = new GuardReportBuilder(graphStore, traversal);
        var report = builder.build("com.Order", 2);

        assertThat(report).contains("src/main/java/com/sales/\n  Shipped.java:3 Shipped DOMAIN_EVENT\n");
        assertThat(report).doesNotContain("**").doesNotContain("`Shipped`");
    }

    @Test
    void build_callsOutDddHighlightTypes() {
        var builder = new GuardReportBuilder(graphStore, traversal);
        var report = builder.build("com.Order", 2);
        // DDD-significant types must be called out with a warning marker
        assertThat(report).contains("AGGREGATE");
        assertThat(report).contains("DOMAIN_EVENT");
        assertThat(report).contains("COMMAND");
        assertThat(report).contains("PROJECTION_HANDLER");
    }



    @Test
    void build_nonDddTierShowsBlastRadiusOnly() {
        graphStore.setConfig("tier", "GENERIC");
        var builder = new GuardReportBuilder(graphStore, traversal);
        var report = builder.build("com.Order", 2);
        assertThat(report).contains("Blast radius");
    }

    @Test
    void build_symbolWithNoImpactedNodes() {
        // A leaf node with no edges produces a valid but minimal report
        graphStore.saveNode(new Node("com.Leaf", NodeType.SERVICE, "Leaf",
            "com.Leaf", "Leaf.java", 5, "class Leaf", 0));
        graphStore.setConfig("tier", "DDD");

        var builder = new GuardReportBuilder(graphStore, traversal);
        var report = builder.build("com.Leaf", 2);
        assertThat(report).contains("## Guard");
        assertThat(report).contains("0 node(s)");
        assertThat(report).doesNotContain("cg_related"); // nothing to list, so nothing to point at
    }

    @Test
    void build_genericTierShowsOnlyBlastRadius() {
        graphStore.setConfig("tier", "GENERIC");
        var builder = new GuardReportBuilder(graphStore, traversal);
        var report = builder.build("com.Order", 2);
        assertThat(report).contains("Blast radius");
        assertThat(report).contains("GENERIC");
    }

    @Test
    void build_nullTierDegradesSafely() {
        // No tier set at all (getConfig returns null)
        var freshStore = new GraphStore(tempDir.resolve("notier.db"));
        freshStore.saveNode(new Node("com.NoTierNode", NodeType.CLASS, "NoTierNode",
            "com.NoTierNode", "X.java", 1, "class X", 0));
        var freshTraversal = new GraphTraversal(freshStore);
        var builder = new GuardReportBuilder(freshStore, freshTraversal);
        var report = builder.build("com.NoTierNode", 2);
        assertThat(report).contains("Blast radius");
        freshStore.close();
    }

    @Test
    void build_depthZeroShowsNoImpact() {
        var builder = new GuardReportBuilder(graphStore, traversal);
        var report = builder.build("com.Order", 0);
        assertThat(report).contains("0 node(s)");
    }
}
