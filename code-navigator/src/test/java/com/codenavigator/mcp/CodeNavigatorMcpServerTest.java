package com.codenavigator.mcp;

import com.codenavigator.domain.DomainSqliteStore;
import com.codenavigator.domain.DomainToolHandlers;
import com.codenavigator.graph.*;
import com.codenavigator.search.SearchService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CodeNavigatorMcpServerTest {

    @TempDir Path tempDir;
    private GraphStore store;
    private CodeNavigatorMcpServer mcpServer;

    @BeforeEach
    void setUp() {
        store = new GraphStore(tempDir.resolve("test.db"));
        var traversal = new GraphTraversal(store);
        var search = new SearchService(store, traversal);
        var domainHandlers = new DomainToolHandlers(new DomainSqliteStore(tempDir.resolve("domain.db")), null);
        mcpServer = new CodeNavigatorMcpServer(store, traversal, search, domainHandlers);
        buildTestGraph();
    }

    @AfterEach
    void tearDown() { store.close(); }

    private void buildTestGraph() {
        store.saveNode(new Node("com.OrderController", NodeType.CONTROLLER, "OrderController",
            "com.OrderController", "Ctrl.java", 1, "@RestController class OrderController", 0));
        store.saveNode(new Node("com.CreateOrderCommand", NodeType.COMMAND, "CreateOrderCommand",
            "com.CreateOrderCommand", "Cmd.java", 1, "record CreateOrderCommand", 0));
        store.saveNode(new Node("com.CreateOrderCommandHandler", NodeType.COMMAND_HANDLER,
            "CreateOrderCommandHandler", "com.CreateOrderCommandHandler", "H.java", 1, "class Handler", 0));
        store.saveNode(new Node("com.Order", NodeType.AGGREGATE, "Order",
            "com.Order", "Order.java", 1, "class Order extends AggregateRoot", 0));
        store.saveNode(new Node("com.OrderCreatedEvent", NodeType.DOMAIN_EVENT, "OrderCreatedEvent",
            "com.OrderCreatedEvent", "Event.java", 1, "class OrderCreatedEvent", 0));
        store.saveNode(new Node("com.OrderViewProjectionHandler", NodeType.PROJECTION_HANDLER,
            "OrderViewProjectionHandler", "com.OrderViewProjectionHandler", "Proj.java", 1, "class Proj", 0));
        store.saveNode(new Node("com.OrderView", NodeType.VIEW, "OrderView",
            "com.OrderView", "View.java", 1, "@Entity class OrderView", 0));

        store.saveEdge(new Edge("e1", EdgeType.DISPATCHES_COMMAND, "com.OrderController", "com.CreateOrderCommand"));
        store.saveEdge(new Edge("e2", EdgeType.HANDLES, "com.CreateOrderCommandHandler", "com.CreateOrderCommand"));
        store.saveEdge(new Edge("e3", EdgeType.LOADS_AGGREGATE, "com.CreateOrderCommandHandler", "com.Order"));
        store.saveEdge(new Edge("e4", EdgeType.EMITS_EVENT, "com.Order", "com.OrderCreatedEvent"));
        store.saveEdge(new Edge("e5", EdgeType.PROJECTS_EVENT, "com.OrderViewProjectionHandler", "com.OrderCreatedEvent"));
        store.saveEdge(new Edge("e6", EdgeType.UPDATES_VIEW, "com.OrderViewProjectionHandler", "com.OrderView"));

        store.setConfig("tier", "DDD");
    }

    @Test
    void cgChain() {
        var result = mcpServer.handleCgChain(Map.of("symbol", "CreateOrderCommand"));
        assertThat(result).contains("OrderController", "CreateOrderCommand",
            "CreateOrderCommandHandler", "Order", "OrderCreatedEvent");
    }

    @Test
    void cgImpact() {
        var result = mcpServer.handleCgImpact(Map.of("symbol", "OrderCreatedEvent", "depth", 1));
        assertThat(result).contains("Order", "OrderViewProjectionHandler");
    }

    @Test
    void cgContext() {
        var result = mcpServer.handleCgContext(Map.of("task", "modify order creation"));
        assertThat(result).contains("Order");
    }

    @Test
    void cgSearch() {
        var result = mcpServer.handleCgSearch(Map.of("query", "Order"));
        assertThat(result).contains("OrderController", "CreateOrderCommand");
    }

    @Test
    void cgOverview() {
        var result = mcpServer.handleCgOverview(Map.of("name", "Order"));
        assertThat(result).contains("Order", "AGGREGATE");
    }

    @Test
    void cgMap() {
        var result = mcpServer.handleCgMap(Map.of());
        assertThat(result).contains("Order", "AGGREGATE");
    }

    @Test
    void cgChainSymbolNotFound() {
        var result = mcpServer.handleCgChain(Map.of("symbol", "NonexistentClass"));
        assertThat(result).contains("not found");
    }

    @Test
    void cgCallers() {
        var result = mcpServer.handleCgCallers(Map.of("symbol", "CreateOrderCommand"));
        assertThat(result).contains("OrderController");
    }

    @Test
    void cgCallees() {
        var result = mcpServer.handleCgCallees(Map.of("symbol", "OrderController"));
        assertThat(result).contains("CreateOrderCommand");
    }

    @Test
    void cgNode() {
        var result = mcpServer.handleCgNode(Map.of("symbol", "Order"));
        assertThat(result).contains("AGGREGATE");
        assertThat(result).contains("Order.java");
        assertThat(result).contains("class Order extends AggregateRoot");
    }

    @Test
    void cgNodeWithIncludeCodeFalse() {
        var result = mcpServer.handleCgNode(Map.of("symbol", "Order", "includeCode", "false"));
        assertThat(result).contains("AGGREGATE");
        assertThat(result).doesNotContain("class Order extends AggregateRoot");
    }

    @Test
    void cgStatus() {
        var result = mcpServer.handleCgStatus(Map.of());
        assertThat(result).contains("7 nodes");
        assertThat(result).contains("6 edges");
        assertThat(result).contains("DDD");
    }

    @Test
    void cgFiles() {
        store.saveIndexedFile("Ctrl.java", 0L, "");
        store.saveIndexedFile("Cmd.java", 0L, "");
        store.saveIndexedFile("H.java", 0L, "");
        store.saveIndexedFile("Order.java", 0L, "");
        store.saveIndexedFile("Event.java", 0L, "");
        store.saveIndexedFile("Proj.java", 0L, "");
        store.saveIndexedFile("View.java", 0L, "");

        var result = mcpServer.handleCgFiles(Map.of());
        assertThat(result).contains("7 file(s)");
        assertThat(result).contains("Ctrl.java");
        assertThat(result).contains("Order.java");
    }

    @Test
    void cgCallersSymbolNotFound() {
        var result = mcpServer.handleCgCallers(Map.of("symbol", "NonexistentClass"));
        assertThat(result).contains("not found");
    }

    @Test
    void cgCalleesSymbolNotFound() {
        var result = mcpServer.handleCgCallees(Map.of("symbol", "NonexistentClass"));
        assertThat(result).contains("not found");
    }

    @Test
    void handleCgSearchWithProjectPath(@TempDir Path otherProject) {
        var otherDbPath = otherProject.resolve("navigators/code").resolve("code-navigator.db");
        otherDbPath.getParent().toFile().mkdirs();

        try (var otherStore = new GraphStore(otherDbPath)) {
            otherStore.saveNode(new Node("other.Widget", NodeType.CLASS, "Widget",
                "other.Widget", "Widget.java", 1, "class Widget", 0));
        }

        var result = mcpServer.handleCgSearch(
            Map.of("query", "Widget", "projectPath", otherProject.toAbsolutePath().toString()));
        assertThat(result).contains("Widget");
    }

    @Test
    void cgHotspots() {
        var result = mcpServer.handleCgHotspots(Map.of("limit", 3));
        assertThat(result).contains("Hotspots");
        assertThat(result).contains("Fan-In");
        assertThat(result).contains("Fan-Out");
    }

    @Test
    void cgDead() {
        store.saveNode(new Node("com.DeadService", NodeType.SERVICE, "DeadService",
            "com.DeadService", "Dead.java", 1, "class DeadService", 0));

        var result = mcpServer.handleCgDead(Map.of());
        assertThat(result).contains("DeadService");
        assertThat(result).contains("Potentially Dead Code");
    }

    @Test
    void cgDeadWithTypeFilter() {
        store.saveNode(new Node("com.DeadService", NodeType.SERVICE, "DeadService",
            "com.DeadService", "Dead.java", 1, "class DeadService", 0));

        var result = mcpServer.handleCgDead(Map.of("type", "SERVICE"));
        assertThat(result).contains("DeadService");

        var result2 = mcpServer.handleCgDead(Map.of("type", "REPOSITORY"));
        assertThat(result2).doesNotContain("DeadService");
    }

    @Test
    void cgPackages() {
        var result = mcpServer.handleCgPackages(Map.of());
        assertThat(result).contains("Package Dependencies");
        assertThat(result).contains("com");
    }

    @Test
    void cgPackagesDetectsCircularDeps() {
        store.saveNode(new Node("pkg.a.Foo", NodeType.SERVICE, "Foo", "pkg.a.Foo", "a/Foo.java", 1, "", 0));
        store.saveNode(new Node("pkg.b.Bar", NodeType.SERVICE, "Bar", "pkg.b.Bar", "b/Bar.java", 1, "", 0));
        store.saveEdge(new Edge("ex1", EdgeType.CALLS_METHOD, "pkg.a.Foo", "pkg.b.Bar"));
        store.saveEdge(new Edge("ex2", EdgeType.CALLS_METHOD, "pkg.b.Bar", "pkg.a.Foo"));

        var result = mcpServer.handleCgPackages(Map.of());
        assertThat(result).contains("Circular");
        assertThat(result).contains("pkg.a");
        assertThat(result).contains("pkg.b");
    }

    @Test
    void cgExportJson() {
        var result = mcpServer.handleCgExport(Map.of("format", "json"));
        assertThat(result).contains("\"tier\":\"DDD\"");
        assertThat(result).contains("OrderController");
    }

    @Test
    void cgExportMermaid() {
        var result = mcpServer.handleCgExport(Map.of("format", "mermaid"));
        assertThat(result).contains("graph LR");
        assertThat(result).contains("DISPATCHES_COMMAND");
    }

    @Test
    void cgExportPlantUml() {
        var result = mcpServer.handleCgExport(Map.of("format", "plantuml"));
        assertThat(result).contains("@startuml");
        assertThat(result).contains("<<CONTROLLER>>");
    }

    @Test
    void cgExportWithSymbolScope() {
        var result = mcpServer.handleCgExport(Map.of("format", "json", "symbol", "Order"));
        assertThat(result).contains("Order");
        assertThat(result).contains("CreateOrderCommand");
    }

    @Test
    void cgExportInvalidFormat() {
        var result = mcpServer.handleCgExport(Map.of("format", "xml"));
        assertThat(result).contains("Unknown format");
    }

    @Test
    void outputTruncatedAt15kChars() {
        String longText = "x".repeat(20_000);
        String truncated = CodeNavigatorMcpServer.truncateOutput(longText);
        assertThat(truncated.length()).isLessThanOrEqualTo(15_100);
        assertThat(truncated).contains("truncated");
    }

    @Test
    void cgGuard_containsBlastRadiusAndAggregate() {
        // Order is an AGGREGATE; depth 2 from it reaches Command, Handler, Event, Projection, View
        var result = mcpServer.handleCgGuard(Map.of("symbol", "Order", "depth", 2));
        assertThat(result).contains("## Guard Report");
        assertThat(result).contains("Blast Radius");
        assertThat(result).contains("OrderCreatedEvent");
        assertThat(result).contains("AGGREGATE");
        assertThat(result).contains("DOMAIN_EVENT");
    }

    @Test
    void cgGuard_containsBoundedContextSection() {
        // Domain store in the test setUp has no data, so sections appear but say "No ... registered"
        var result = mcpServer.handleCgGuard(Map.of("symbol", "Order"));
        assertThat(result).contains("Bounded Contexts Touched");
        assertThat(result).contains("Matching Domain Rules");
    }

    @Test
    void cgGuard_symbolNotFound() {
        var result = mcpServer.handleCgGuard(Map.of("symbol", "NonexistentClass"));
        assertThat(result).contains("not found");
    }

    @Test
    void cgGuard_defaultDepthIsTwo() {
        // No "depth" arg — should still produce a report (not crash)
        var result = mcpServer.handleCgGuard(Map.of("symbol", "Order"));
        assertThat(result).contains("## Guard Report");
        assertThat(result).contains("depth 2");
    }

    // ---- cg_coupling tests ----

    @Test
    void cgCoupling_returnsTopCoupledFiles() {
        // Seed co-change data directly via store
        store.upsertCoChange("Ctrl.java", "Cmd.java");
        store.upsertCoChange("Ctrl.java", "Cmd.java");
        store.upsertCoChange("Ctrl.java", "H.java");

        var result = mcpServer.handleCgCoupling(Map.of("symbol", "OrderController"));
        assertThat(result).contains("Cmd.java");
        assertThat(result).contains("2");
        assertThat(result).contains("H.java");
    }

    @Test
    void cgCoupling_byFilePath() {
        store.upsertCoChange("Ctrl.java", "Order.java");
        store.upsertCoChange("Ctrl.java", "Order.java");
        store.upsertCoChange("Ctrl.java", "Order.java");

        var result = mcpServer.handleCgCoupling(Map.of("file", "Ctrl.java"));
        assertThat(result).contains("Order.java");
        assertThat(result).contains("3");
    }

    @Test
    void cgCoupling_symbolNotFound_showsError() {
        var result = mcpServer.handleCgCoupling(Map.of("symbol", "NonExistentClass"));
        assertThat(result).contains("not found");
    }

    @Test
    void cgCoupling_noCouplingData_showsEmptyMessage() {
        // OrderController exists in graph but no co-change data
        var result = mcpServer.handleCgCoupling(Map.of("symbol", "OrderController"));
        assertThat(result).contains("No co-change data");
    }

    @Test
    void cgImpact_annotatesCoChangeCounts() {
        // OrderController (Ctrl.java) is the queried symbol.
        // Order (Order.java) is in the impact set.
        // Seed coupling between Ctrl.java and Order.java.
        store.upsertCoChange("Ctrl.java", "Order.java");
        store.upsertCoChange("Ctrl.java", "Order.java");

        var result = mcpServer.handleCgImpact(Map.of("symbol", "OrderController", "depth", 3));
        // Order.java should appear annotated with co-change count
        assertThat(result).contains("Order");
        assertThat(result).containsPattern("co-change.*2|2.*co-change");
    }

    // ── Library boundary visibility ──

    @Test
    void cgCalleesIncludesLibraryNodes() {
        // Add a LIBRARY node and a USES_LIBRARY edge from OrderController
        store.saveNode(new Node(
            "org.springframework.web.bind.annotation.RestController",
            NodeType.LIBRARY, "RestController",
            "org.springframework.web.bind.annotation.RestController",
            "", 0, "org.springframework.boot:spring-boot-starter-web", 0));
        store.saveEdge(new Edge("lib-e1", EdgeType.USES_LIBRARY,
            "com.OrderController", "org.springframework.web.bind.annotation.RestController"));

        var result = mcpServer.handleCgCallees(Map.of("symbol", "OrderController", "depth", 1));
        assertThat(result).contains("RestController");
        assertThat(result).contains("LIBRARY");
    }

    @Test
    void cgImpactIncludesLibraryNodes() {
        // Add a LIBRARY node and USES_LIBRARY edges from two different nodes
        store.saveNode(new Node(
            "org.springframework.web.bind.annotation.RequestMapping",
            NodeType.LIBRARY, "RequestMapping",
            "org.springframework.web.bind.annotation.RequestMapping",
            "", 0, "org.springframework.boot:spring-boot-starter-web", 0));
        store.saveEdge(new Edge("lib-e2", EdgeType.USES_LIBRARY,
            "com.OrderController", "org.springframework.web.bind.annotation.RequestMapping"));

        var result = mcpServer.handleCgImpact(Map.of("symbol", "OrderController", "depth", 1));
        assertThat(result).contains("RequestMapping");
    }
}
