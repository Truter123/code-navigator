package com.codenavigator.mcp;

import com.codenavigator.embedding.NoopEmbeddingProvider;
import com.codenavigator.graph.*;
import com.codenavigator.search.SearchService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeNavigatorMcpServerTest {

    @TempDir Path tempDir;
    private GraphStore store;
    private CodeNavigatorMcpServer mcpServer;

    @BeforeEach
    void setUp() {
        store = new GraphStore(tempDir.resolve("test.db"));
        var traversal = new GraphTraversal(store);
        var search = new SearchService(store, traversal);
        mcpServer = new CodeNavigatorMcpServer(store, traversal, search, new NoopEmbeddingProvider());
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
        // One line per root with a reach count, not a per-type breakdown block.
        var result = mcpServer.handleCgMap(Map.of());
        assertThat(result).contains("Roots");
        assertThat(result).contains("Order");
        assertThat(result).contains("connected");
    }

    @Test
    void cgMapToolCombinesStatusAndRoots() {
        var result = mcpServer.handleCgMapTool(Map.of());
        assertThat(result).contains("7 nodes").contains("DDD").contains("Roots");
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

    // ---- projectPath routing ----
    // Before this was fixed, resolveStore() was called by only 2 of 19 cg_* handlers, so every
    // other tool silently answered from the default project no matter what projectPath said.

    /** Index a throwaway project holding a single CLASS node named {@code Widget}. */
    private static void seedOtherProject(Path root) {
        var dbPath = root.resolve("navigators/code").resolve("code-navigator.db");
        dbPath.getParent().toFile().mkdirs();
        try (var otherStore = new GraphStore(dbPath)) {
            otherStore.saveNode(new Node("other.Widget", NodeType.CLASS, "Widget",
                "other.Widget", "Widget.java", 1, "class Widget", 0));
            otherStore.saveIndexedFile("Widget.java", 0L, "");
            otherStore.setConfig("tier", "GENERIC");
        }
    }

    @Test
    void cgStatusHonoursProjectPath(@TempDir Path otherProject) {
        seedOtherProject(otherProject);
        var args = Map.<String, Object>of("projectPath", otherProject.toAbsolutePath().toString());

        var result = mcpServer.handleCgStatus(args);

        // The other project has exactly 1 node; the default test graph has 7.
        assertThat(result).contains("**Nodes:** 1 nodes");
        assertThat(result).contains("**Tier:** GENERIC");
        assertThat(result).doesNotContain("AGGREGATE");
    }

    @Test
    void cgStatusWithoutProjectPathUsesDefaultProject() {
        var result = mcpServer.handleCgStatus(Map.of());

        assertThat(result).contains("**Nodes:** 7 nodes");
        assertThat(result).contains("**Tier:** DDD");
    }

    @Test
    void cgNodeHonoursProjectPath(@TempDir Path otherProject) {
        seedOtherProject(otherProject);
        var args = Map.<String, Object>of(
            "symbol", "Widget", "projectPath", otherProject.toAbsolutePath().toString());

        assertThat(mcpServer.handleCgNode(args)).contains("Widget", "other.Widget");
        // Symbols from the default project must not resolve against the other project.
        assertThat(mcpServer.handleCgNode(Map.of(
            "symbol", "Order", "projectPath", otherProject.toAbsolutePath().toString())))
            .contains("not found");
    }

    @Test
    void cgFilesHonoursProjectPath(@TempDir Path otherProject) {
        seedOtherProject(otherProject);

        var result = mcpServer.handleCgFiles(
            Map.of("projectPath", otherProject.toAbsolutePath().toString()));

        assertThat(result).contains("Widget.java");
        assertThat(result).doesNotContain("Order.java");
    }

    @Test
    void unindexedProjectPathIsAnErrorNotASilentFallback(@TempDir Path emptyDir) {
        var args = Map.<String, Object>of("projectPath", emptyDir.toAbsolutePath().toString());

        // Falling back to the default project would make a typo indistinguishable from an
        // empty project — the failure mode this whole change exists to remove.
        assertThatThrownBy(() -> mcpServer.handleCgStatus(args))
            .isInstanceOf(CodeNavigatorMcpServer.ProjectNotIndexedException.class)
            .hasMessageContaining("No code-navigator index")
            .hasMessageContaining("init");
    }



    @Test
    void scopesAreCachedPerProject(@TempDir Path otherProject) {
        seedOtherProject(otherProject);
        var args = Map.<String, Object>of("projectPath", otherProject.toAbsolutePath().toString());

        assertThat(mcpServer.resolveScope(args)).isSameAs(mcpServer.resolveScope(args));
        assertThat(mcpServer.resolveScope(Map.of())).isSameAs(mcpServer.resolveScope(Map.of()));
        assertThat(mcpServer.resolveScope(args)).isNotSameAs(mcpServer.resolveScope(Map.of()));
    }

    @Test
    void relativeAndTrailingSlashPathsResolveToOneScope(@TempDir Path otherProject) {
        seedOtherProject(otherProject);
        String plain = otherProject.toAbsolutePath().toString();

        var viaPlain = mcpServer.resolveScope(Map.of("projectPath", plain));
        var viaSlash = mcpServer.resolveScope(Map.of("projectPath", plain + "/"));
        var viaDot = mcpServer.resolveScope(Map.of("projectPath", plain + "/."));

        assertThat(viaSlash).isSameAs(viaPlain);
        assertThat(viaDot).isSameAs(viaPlain);
    }

    @Test
    void blankProjectPathFallsBackToDefaultScope() {
        assertThat(mcpServer.resolveScope(Map.of("projectPath", "   ")))
            .isSameAs(mcpServer.resolveScope(Map.of()));
    }

    // ---- Symbol ambiguity ----

    /**
     * The TypeScript indexer stores bare simple names as node ids, so a frontend model can own the
     * id "Order" while the backend aggregate merely has that name. Resolving on the id first made
     * every question about the aggregate answer from the edge-less frontend model.
     */
    private void addFrontendNamesake() {
        store.saveNode(new Node("Order", NodeType.FE_MODEL, "Order",
            "Order", "src/app/order.model.ts", 15, "export interface Order {}", 0));
    }

    @Test
    void bareNameResolvesToTheConnectedNodeNotAnEdgelessNamesake() {
        addFrontendNamesake();

        var result = mcpServer.handleCgNode(Map.of("symbol", "Order", "includeCode", "false"));

        assertThat(result).contains("com.Order");
        assertThat(result).contains("AGGREGATE");
        assertThat(result).doesNotContain("order.model.ts");
    }

    @Test
    void ambiguousSymbolNamesTheNodeItUsedAndTheAlternatives() {
        addFrontendNamesake();

        var result = mcpServer.handleCgImpact(Map.of("symbol", "Order", "depth", 1));

        assertThat(result).contains("Resolved to AGGREGATE `com.Order`");
        assertThat(result).contains("also matched: FE_MODEL Order");
        // The aggregate is connected, so the impact set is not empty the way the namesake's is.
        assertThat(result).doesNotContain("0 affected node(s)");
    }

    @Test
    void unambiguousSymbolCarriesNoAmbiguityNote() {
        var result = mcpServer.handleCgImpact(Map.of("symbol", "Order", "depth", 1));

        assertThat(result).doesNotContain("Resolved to");
    }

    @Test
    void fullyQualifiedIdStillWinsOutright() {
        addFrontendNamesake();

        var result = mcpServer.handleCgNode(Map.of("symbol", "com.Order", "includeCode", "false"));

        assertThat(result).contains("com.Order");
        assertThat(result).doesNotContain("Resolved to");
    }

    // ---- Method-level tools ----

    private void addMethodGraph() {
        store.saveNode(new Node("com.Order#complete(UUID)", NodeType.METHOD, "complete",
            "com.Order#complete(UUID)", "Order.java", 42, "public void complete(UUID by)", 0));
        store.saveNode(new Node("com.Order#cancel(UUID)", NodeType.METHOD, "cancel",
            "com.Order#cancel(UUID)", "Order.java", 55, "public void cancel(UUID by)", 0));
        store.saveNode(new Node("com.CreateOrderCommandHandler#handle(CreateOrderCommand)",
            NodeType.METHOD, "handle", "com.CreateOrderCommandHandler#handle(CreateOrderCommand)",
            "H.java", 20, "public void handle(CreateOrderCommand c)", 0));
        store.saveEdge(new Edge("dm1", EdgeType.DECLARES_METHOD, "com.Order", "com.Order#complete(UUID)"));
        store.saveEdge(new Edge("dm2", EdgeType.DECLARES_METHOD, "com.Order", "com.Order#cancel(UUID)"));
        store.saveEdge(new Edge("dm3", EdgeType.DECLARES_METHOD, "com.CreateOrderCommandHandler",
            "com.CreateOrderCommandHandler#handle(CreateOrderCommand)"));
        store.saveEdge(new Edge("cl1", EdgeType.CALLS,
            "com.CreateOrderCommandHandler#handle(CreateOrderCommand)", "com.Order#complete(UUID)"));
    }

    @Test
    void cgMethodDescribesOneMethodWithCallersAndCallees() {
        addMethodGraph();

        var result = mcpServer.handleCgMethod(Map.of("symbol", "Order#complete"));

        assertThat(result).contains("Order#complete(UUID)");
        assertThat(result).contains("Declared by:").contains("com.Order").contains("AGGREGATE");
        assertThat(result).contains("Order.java:42");
        assertThat(result).contains("Called by").contains("CreateOrderCommandHandler#handle");
    }

    @Test
    void cgMethodListsOverloadsInsteadOfPickingOne() {
        addMethodGraph();
        store.saveNode(new Node("com.Order#complete(UUID,String)", NodeType.METHOD, "complete",
            "com.Order#complete(UUID,String)", "Order.java", 48, "", 0));

        var result = mcpServer.handleCgMethod(Map.of("symbol", "Order#complete"));

        assertThat(result).contains("2 methods match");
        assertThat(result).contains("com.Order#complete(UUID)");
        assertThat(result).contains("com.Order#complete(UUID,String)");
    }

    @Test
    void cgMethodOnAnUnknownSymbolExplainsTheExpectedForm() {
        var result = mcpServer.handleCgMethod(Map.of("symbol", "Nope#missing"));

        assertThat(result).contains("No method matching").contains("Class#method");
    }

    @Test
    void classLevelQueriesHideMethodsAndMethodLevelQueriesShowThem() {
        addMethodGraph();

        // Asking about a class gets a class-level answer...
        var classLevel = mcpServer.handleCgImpact(Map.of("symbol", "Order", "depth", 1));
        assertThat(classLevel).doesNotContain("**METHOD**");

        // ...asking about a method gets a method-level one, without needing a flag.
        var methodLevel = mcpServer.handleCgImpact(
            Map.of("symbol", "com.Order#complete(UUID)", "depth", 1));
        assertThat(methodLevel).contains("**METHOD**");

        // And granularity overrides the default either way.
        var forced = mcpServer.handleCgImpact(
            Map.of("symbol", "Order", "depth", 1, "granularity", "method"));
        assertThat(forced).contains("**METHOD**");
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
        assertThat(result).contains("Dead-Code Candidates");
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
        assertThat(truncated.length()).isLessThanOrEqualTo(15_400);
        assertThat(truncated).contains("not shown");
    }

    @Test
    void truncationCutsWholeLinesAndSaysHowManyWereDropped() {
        // A blind substring severed the last entry mid-text and gave no hint anything was missing,
        // so a beheaded list read exactly like a complete one.
        String many = "line of output text\n".repeat(2_000);
        String truncated = CodeNavigatorMcpServer.truncateOutput(many);

        assertThat(truncated).contains("more line(s) not shown");
        assertThat(truncated).contains("Narrow with");
        // Every rendered line is intact.
        var body = truncated.substring(0, truncated.indexOf("\n\n… "));
        assertThat(body.lines()).allMatch(l -> l.equals("line of output text"));
    }

    @Test
    void cgGuard_containsBlastRadiusAndAggregate() {
        // Order is an AGGREGATE; depth 2 from it reaches Command, Handler, Event, Projection, View
        var result = mcpServer.handleCgGuard(Map.of("symbol", "Order", "depth", 2));
        assertThat(result).contains("## Guard");
        assertThat(result).contains("Blast radius");
        assertThat(result).contains("OrderCreatedEvent");
        assertThat(result).contains("AGGREGATE");
        assertThat(result).contains("DOMAIN_EVENT");
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
        assertThat(result).contains("## Guard");
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

    // ---- limit honoured (S3) ----

    @Test
    void cgImpact_honoursLimit() {
        var result = mcpServer.handleCgImpact(Map.of("symbol", "OrderCreatedEvent", "depth", 1, "limit", 1));

        long bulletLines = result.lines().filter(l -> l.startsWith("- **")).count();
        assertThat(bulletLines).isEqualTo(1);
        assertThat(result).contains("more").contains("Raise `limit`");
    }

    @Test
    void cgChain_honoursLimit() {
        var result = mcpServer.handleCgChain(Map.of("symbol", "CreateOrderCommand", "limit", 1));

        long bulletLines = result.lines().filter(l -> l.startsWith("- `")).count();
        assertThat(bulletLines).isEqualTo(1);
        assertThat(result).contains("more").contains("Raise `limit`");
    }

    @Test
    void cgFiles_honoursLimit() {
        store.saveIndexedFile("Ctrl.java", 0L, "");
        store.saveIndexedFile("Cmd.java", 0L, "");
        store.saveIndexedFile("H.java", 0L, "");
        store.saveIndexedFile("Order.java", 0L, "");
        store.saveIndexedFile("Event.java", 0L, "");
        store.saveIndexedFile("Proj.java", 0L, "");
        store.saveIndexedFile("View.java", 0L, "");

        var result = mcpServer.handleCgFiles(Map.of("limit", 1));

        long bulletLines = result.lines().filter(l -> l.startsWith("- ")).count();
        assertThat(bulletLines).isEqualTo(1);
        assertThat(result).contains("more").contains("Raise `limit`");
    }

    @Test
    void cgDead_honoursLimit() {
        store.saveNode(new Node("com.DeadServiceA", NodeType.SERVICE, "DeadServiceA",
            "com.DeadServiceA", "DeadA.java", 1, "class DeadServiceA", 0));
        store.saveNode(new Node("com.DeadServiceB", NodeType.SERVICE, "DeadServiceB",
            "com.DeadServiceB", "DeadB.java", 1, "class DeadServiceB", 0));

        var result = mcpServer.handleCgDead(Map.of("limit", 1));

        long bulletLines = result.lines().filter(l -> l.startsWith("- `")).count();
        assertThat(bulletLines).isEqualTo(1);
        assertThat(result).contains("more candidate line(s) not shown").contains("Raise `limit`");
    }

    @Test
    void cgCoupling_honoursLimit() {
        store.upsertCoChange("Ctrl.java", "Cmd.java");
        store.upsertCoChange("Ctrl.java", "Cmd.java");
        store.upsertCoChange("Ctrl.java", "H.java");

        var result = mcpServer.handleCgCoupling(Map.of("symbol", "OrderController", "limit", 1));

        long bulletLines = result.lines().filter(l -> l.matches("^\\s*\\d+\\..*")).count();
        assertThat(bulletLines).isEqualTo(1);
        assertThat(result).contains("more co-changed file(s)").contains("Raise `limit`");
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
