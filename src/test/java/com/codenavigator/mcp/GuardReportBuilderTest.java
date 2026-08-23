package com.codenavigator.mcp;

import com.codenavigator.graph.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

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
        assertThat(report).contains("Full list: cg_impact");
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
