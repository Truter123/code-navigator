package com.codenavigator.mcp;

import com.codenavigator.export.ExportService;
import com.codenavigator.graph.*;
import com.codenavigator.search.SearchService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

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
        mcpServer = new CodeNavigatorMcpServer(store, traversal, search);
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
        // One line per root. A lone root shares its reach with nobody, so its line carries it.
        var result = mcpServer.handleCgMap(Map.of());
        assertThat(result).contains("Roots");
        assertThat(result).contains("Order");
        assertThat(result).contains("connected");
        assertThat(result).doesNotContain("Left out");
    }

    @Test
    void cgMapToolCombinesStatusAndRoots() {
        var result = mcpServer.handleCgMapTool(Map.of());
        assertThat(result).contains("7 nodes").contains("DDD").contains("Roots");
        assertThat(result).doesNotContain("more types");
    }

    @Test
    void cgMapLeavesThePathOffTheRootLine() {
        // cg_node gives the path; repeating it on every root line was most of the line's bytes.
        var result = mcpServer.handleCgMap(Map.of());
        assertThat(result).contains("`Order`").doesNotContain("Order.java");
    }

    @Test
    void cgMapStatesTheReachEveryRootSharesOnceInsteadOfOnEachLine() {
        // Isolated roots reach the same set whatever the traversal, so nothing is marked.
        var result = mapOverFresh(
            rootNode(NodeType.AGGREGATE, "Invoice", "Invoice.java"),
            rootNode(NodeType.AGGREGATE, "Payment", "Payment.java"),
            rootNode(NodeType.AGGREGATE, "Refund", "Refund.java"));

        assertThat(result).contains("Roots (3)").contains("All roots reach");
        assertThat(result).containsOnlyOnce("connected");
        assertThat(result).contains("- `Invoice`\n", "- `Payment`\n", "- `Refund`\n");
    }

    @Test
    void cgMapMarksOnlyTheRootsWhoseReachDiffersFromTheCommonOne() {
        // Order sits in the order chain; the two isolated aggregates share one smaller reach.
        store.saveNode(rootNode(NodeType.AGGREGATE, "Invoice", "Invoice.java"));
        store.saveNode(rootNode(NodeType.AGGREGATE, "Payment", "Payment.java"));

        var result = mcpServer.handleCgMap(Map.of());

        assertThat(result).contains("Roots (3)").contains("Roots reach").contains("unless marked");
        assertThat(rootRowOf(result, "Order")).contains("connected");
        assertThat(rootRowOf(result, "Invoice")).doesNotContain("connected");
        assertThat(rootRowOf(result, "Payment")).doesNotContain("connected");
    }

    @Test
    void cgMapLeavesOutRootsUnderSrcTestAndCountsThemInOneLine() {
        store.saveNode(rootNode(NodeType.AGGREGATE, "TestAggregate", "src/test/java/com/TestAggregate.java"));
        store.saveNode(rootNode(NodeType.AGGREGATE, "FixtureAggregate",
            "billing/src/test/java/com/FixtureAggregate.java"));

        var result = mcpServer.handleCgMap(Map.of());

        assertThat(result).contains("Roots (1)").contains("`Order`");
        assertThat(result).doesNotContain("TestAggregate").doesNotContain("FixtureAggregate");
        assertThat(result).contains("Left out: 2 root(s) under src/test/.");
    }

    @Test
    void cgMapFooterNamesTheLimitParameterWhenRootsAreCut() {
        for (String name : List.of("Invoice", "Payment", "Refund", "Ledger")) {
            store.saveNode(rootNode(NodeType.AGGREGATE, name, name + ".java"));
        }

        var cut = mcpServer.handleCgMap(Map.of("limit", 2));

        assertThat(cut).contains("Roots (5)").contains("- … 3 more. Raise `limit`.");
        assertThat(cut.lines().filter(l -> l.startsWith("- `"))).hasSize(2);
        assertThat(mcpServer.handleCgMap(Map.of())).doesNotContain("Raise `limit`");
    }

    @Test
    void cgMapListsTheEightLargestNodeTypesAndCountsTheRest() {
        for (int i = 0; i < 3; i++) store.saveNode(rootNode(NodeType.METHOD, "m" + i, "M.java"));
        for (int i = 0; i < 2; i++) store.saveNode(rootNode(NodeType.ENTITY, "e" + i, "E.java"));
        store.saveNode(rootNode(NodeType.SQL_TABLE, "orders", "V1.sql"));
        store.saveNode(rootNode(NodeType.LIBRARY, "Jackson", ""));

        var reply = mcpServer.handleCgMapTool(Map.of());

        // 11 types: the two larger ones first, then the count-1 types by name until eight are listed.
        var rows = reply.lines().filter(l -> l.matches("- [A-Z_]+: \\d+")).toList();
        assertThat(rows).containsExactly("- METHOD: 3", "- ENTITY: 2", "- AGGREGATE: 1", "- COMMAND: 1",
            "- COMMAND_HANDLER: 1", "- CONTROLLER: 1", "- DOMAIN_EVENT: 1", "- LIBRARY: 1");
        assertThat(reply).contains("- +3 more types");
    }

    private static Node rootNode(NodeType type, String name, String path) {
        return new Node("com." + name, type, name, "com." + name, path, 1, "class " + name, 0);
    }

    private static String rootRowOf(String reply, String name) {
        return reply.lines().filter(l -> l.startsWith("- `" + name + "`")).findFirst().orElseThrow();
    }

    /** cg_map over a fresh store holding only {@code roots}, tier DDD: a graph the shared fixture cannot be. */
    private String mapOverFresh(Node... roots) {
        var fresh = new GraphStore(tempDir.resolve("fresh.db"));
        try {
            fresh.setConfig("tier", "DDD");
            for (Node root : roots) fresh.saveNode(root);
            var traversal = new GraphTraversal(fresh);
            var server = new CodeNavigatorMcpServer(fresh, traversal, new SearchService(fresh, traversal));
            return server.handleCgMap(Map.of());
        } finally {
            fresh.close();
        }
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
    void cgCallersOnANonMethodNodeReadsAsIncomingNotCallers() {
        store.saveNode(new Node("sql:supplier", NodeType.SQL_TABLE, "supplier", "supplier", "V1.sql", 1, "", 0));
        store.saveNode(new Node("SupplierEntity", NodeType.ENTITY, "SupplierEntity", "SupplierEntity",
            "SupplierEntity.java", 1, "", 0));
        store.saveEdge(new Edge("r1", EdgeType.REFERENCES_TABLE, "SupplierEntity", "sql:supplier"));

        var result = mcpServer.handleCgCallers(Map.of("symbol", "supplier"));
        assertThat(result).contains("Incoming to supplier").contains("SupplierEntity");
        assertThat(result).doesNotContain("0 callers found");
    }

    @Test
    void cgNodeFenceLanguagePicksExtension() {
        assertThat(CodeNavigatorMcpServer.fenceLanguage("deploy/clean-restart.sh")).isEqualTo("bash");
        assertThat(CodeNavigatorMcpServer.fenceLanguage("db/V1__init.sql")).isEqualTo("sql");
        assertThat(CodeNavigatorMcpServer.fenceLanguage("tools/build.py")).isEqualTo("python");
        assertThat(CodeNavigatorMcpServer.fenceLanguage("src/app/order.ts")).isEqualTo("typescript");
        assertThat(CodeNavigatorMcpServer.fenceLanguage("scripts/build.groovy")).isEqualTo("groovy");
        assertThat(CodeNavigatorMcpServer.fenceLanguage("lib/model.dart")).isEqualTo("dart");
        assertThat(CodeNavigatorMcpServer.fenceLanguage("com/example/Order.java")).isEqualTo("java");
        assertThat(CodeNavigatorMcpServer.fenceLanguage(null)).isEqualTo("java");
    }

    @Test
    void cgNodeRendersShellScriptSnippetInBashFence() {
        store.saveNode(new Node("deploy.sh", NodeType.SHELL_SCRIPT, "deploy", "deploy.sh",
            "deploy.sh", 1, "#!/usr/bin/env bash\necho hi", 0));

        var result = mcpServer.handleCgNode(Map.of("symbol", "deploy", "includeCode", "true"));
        assertThat(result).contains("```bash").contains("echo hi");
        assertThat(result).doesNotContain("```java");
    }

    @Test
    void cgNode() {
        var result = mcpServer.handleCgNode(Map.of("symbol", "Order"));
        assertThat(result).contains("AGGREGATE");
        assertThat(result).contains("Order.java");
    }

    @Test
    void cgNodeOmitsTheCodeUnlessAsked() {
        // The snippet is opt-in: the Type line and the edges answer most questions without it.
        assertThat(mcpServer.handleCgNode(Map.of("symbol", "Order")))
            .doesNotContain("### Code").doesNotContain("class Order extends AggregateRoot");
        assertThat(mcpServer.handleCgNode(Map.of("symbol", "Order", "includeCode", "true")))
            .contains("### Code").contains("class Order extends AggregateRoot");
    }

    @Test
    void cgNodeCodeIsTheDeclarationOnly() {
        store.saveNode(new Node("com.SalesOrder", NodeType.AGGREGATE, "SalesOrder", "com.SalesOrder",
            "SalesOrder.java", 12, """
            /**
             * An order a customer placed.
             * Quotes are something else.
             */
            @Entity
            @RequestMapping(value = { "/orders", "/sales" })
            public class SalesOrder extends AggregateRoot {

                private UUID id;
                private Money total;""", 0));

        var result = mcpServer.handleCgNode(Map.of("symbol", "SalesOrder", "includeCode", "true"));

        // Annotations and signature survive (a brace inside an annotation does not end the
        // declaration); the Javadoc above and the body below do not.
        assertThat(result).contains("""
            ```java
            @Entity
            @RequestMapping(value = { "/orders", "/sales" })
            public class SalesOrder extends AggregateRoot
            ```
            """);
        assertThat(result).doesNotContain("An order a customer placed").doesNotContain("private UUID id");
    }

    @Test
    void cgNodeCodeEndsAtTheSemicolonOfABodilessSignature() {
        store.saveNode(new Node("com.Svc#run(String)", NodeType.METHOD, "run", "com.Svc#run(String)",
            "Svc.java", 5, "@Override\nvoid run(String task) throws IOException;\nvoid other();", 0));

        var result = mcpServer.handleCgNode(Map.of("symbol", "com.Svc#run(String)", "includeCode", "true"));

        assertThat(result).contains("```java\n@Override\nvoid run(String task) throws IOException;\n```");
        assertThat(result).doesNotContain("other");
    }

    @Test
    void cgNodeTypeLineCarriesTheSupertypes() {
        // Turning the snippet off must not lose "extends AggregateRoot implements ...".
        store.saveNode(new Node("com.AggregateRoot", NodeType.CLASS, "AggregateRoot", "com.AggregateRoot",
            "AggregateRoot.java", 1, "", 0));
        store.saveNode(new Node("com.Auditable", NodeType.INTERFACE, "Auditable", "com.Auditable",
            "Auditable.java", 1, "", 0));
        store.saveNode(new Node("com.Stamped", NodeType.INTERFACE, "Stamped", "com.Stamped",
            "Stamped.java", 1, "", 0));
        store.saveNode(new Node("com.SalesOrder", NodeType.AGGREGATE, "SalesOrder", "com.SalesOrder",
            "SalesOrder.java", 1, "", 0));
        store.saveEdge(new Edge("x1", EdgeType.EXTENDS, "com.SalesOrder", "com.AggregateRoot"));
        store.saveEdge(new Edge("x2", EdgeType.IMPLEMENTS, "com.SalesOrder", "com.Stamped"));
        store.saveEdge(new Edge("x3", EdgeType.IMPLEMENTS, "com.SalesOrder", "com.Auditable"));

        var result = mcpServer.handleCgNode(Map.of("symbol", "SalesOrder"));

        assertThat(result).contains("- **Type:** AGGREGATE extends AggregateRoot implements Auditable, Stamped\n");
    }

    @Test
    void cgNodeEdgesAreOneCappedLinePerEdgeType() {
        store.saveNode(new Node("com.Hub", NodeType.CLASS, "Hub", "com.Hub", "Hub.java", 1, "", 0));
        store.saveNode(new Node("com.Hub#run()", NodeType.METHOD, "run", "com.Hub#run()", "Hub.java", 3, "", 0));
        store.saveNode(new Node("com.Hub#stop()", NodeType.METHOD, "stop", "com.Hub#stop()", "Hub.java", 6, "", 0));
        store.saveEdge(new Edge("hd1", EdgeType.DECLARES_METHOD, "com.Hub", "com.Hub#run()"));
        store.saveEdge(new Edge("hd2", EdgeType.DECLARES_METHOD, "com.Hub", "com.Hub#stop()"));
        store.saveEdge(new Edge("hi", EdgeType.INJECTS, "com.OrderController", "com.Hub"));
        for (int i = 1; i <= 12; i++) {
            String id = "com.Caller#call%02d()".formatted(i);
            store.saveNode(new Node(id, NodeType.METHOD, "call%02d".formatted(i), id, "Caller.java", i, "", 0));
            store.saveEdge(new Edge("hc" + i, EdgeType.CALLS, id, "com.Hub"));
        }

        var result = mcpServer.handleCgNode(Map.of("symbol", "com.Hub"));

        // Twelve callers, eight names, the rest counted, and the call that lists the rest.
        assertThat(result).contains("in CALLS (12): call01, call02, call03, call04, call05, call06, call07, call08, "
            + "… +4 (more: cg_related Hub direction=in depth=1)\n");
        assertThat(result).doesNotContain("call09");
        assertThat(result.lines().filter(l -> l.startsWith("in CALLS")).count()).isEqualTo(1);
        // Under the cap nothing is dropped and no "+N" appears.
        assertThat(result).contains("in INJECTS (1): OrderController\n");
        assertThat(result).contains("out DECLARES_METHOD (2): run, stop\n");
        assertThat(result).doesNotContain("--[");
    }

    @Test
    void cgNodeNamesMethodsWithTheirOwnerClass() {
        store.saveNode(new Node("com.Bus#dispatch(Command)", NodeType.METHOD, "dispatch",
            "com.Bus#dispatch(Command)", "Bus.java", 9, "", 0));
        for (String owner : new String[] {"OrderService", "QuoteService"}) {
            store.saveNode(new Node("com." + owner, NodeType.SERVICE, owner, "com." + owner,
                owner + ".java", 1, "", 0));
        }
        callsBus("OrderService", "create");
        callsBus("QuoteService", "create");
        callsBus("OrderService", "cancel");

        var result = mcpServer.handleCgNode(Map.of("symbol", "com.Bus#dispatch(Command)"));

        // A method is named with its class, unique or not: a bare "create" says nothing about its owner.
        assertThat(result).contains("in CALLS (3): OrderService#cancel, OrderService#create, QuoteService#create\n");
    }

    @Test
    void cgNodeCollapsesOverloadsOfOneClassIntoACount() {
        // Qualifying with the owner does not help when one class declares both: it printed
        // "SalesOrder#addLine, SalesOrder#addLine" on nlp, which reads as a duplicated edge.
        store.saveNode(new Node("com.Svc", NodeType.SERVICE, "Svc", "com.Svc", "Svc.java", 1, "", 0));
        for (String signature : new String[] {"add(UUID)", "add(UUID,int)", "remove()"}) {
            String id = "com.Svc#" + signature;
            store.saveNode(new Node(id, NodeType.METHOD, signature.substring(0, signature.indexOf('(')),
                id, "Svc.java", 5, "", 0));
            store.saveEdge(new Edge("o-" + id, EdgeType.DECLARES_METHOD, "com.Svc", id));
        }

        var result = mcpServer.handleCgNode(Map.of("symbol", "com.Svc"));

        // A class's own methods need no owner: the page is the owner.
        assertThat(result).contains("out DECLARES_METHOD (3): add ×2, remove\n");

        // Qualified, overloads of one caller collapse the same way.
        callsFrom("com.Svc#remove()", "src/main/java/com/Client.java", "Client", "go(UUID)", "go(UUID,int)");
        assertThat(mcpServer.handleCgNode(Map.of("symbol", "com.Svc#remove()")))
            .contains("in CALLS (2): Client#go ×2\n");
    }

    @Test
    void cgNodeEdgeLinesListProductionCodeBeforeTestsAndPointAtCgRelated() {
        store.saveNode(new Node("com.Hub", NodeType.CLASS, "Hub", "com.Hub", "src/main/java/com/Hub.java", 1, "", 0));
        callsFrom("com.Hub", "src/main/java/com/Billing.java", "Billing", "charge()", "refund()");
        callsFrom("com.Hub", "src/main/java/com/Orders.java", "Orders", "place()", "cancel()");
        callsFrom("com.Hub", "src/main/java/com/Quotes.java", "Quotes", "create()", "cancel()");
        callsFrom("com.Hub", "src/main/java/com/Stock.java", "Stock", "reserve()", "release()");
        callsFrom("com.Hub", "src/main/java/com/Zeta.java", "Zeta", "run()");
        // "AaaTest" sorts before every production name; it must not lead, or fill, the line.
        callsFrom("com.Hub", "src/test/java/com/AaaTest.java", "AaaTest", "a()", "b()", "c()", "d()", "e()");

        var result = mcpServer.handleCgNode(Map.of("symbol", "com.Hub"));

        // 9 production callers + 5 tests: eight production names, then 6 dropped (1 + the tests).
        assertThat(result).contains("in CALLS (14, 5 in tests): Billing#charge, Billing#refund, Orders#cancel, "
            + "Orders#place, Quotes#cancel, Quotes#create, Stock#release, Stock#reserve, "
            + "… +6 (more: cg_related Hub direction=in depth=1)\n");
        assertThat(result).doesNotContain("AaaTest");

        // Under the cap nothing is dropped, and the tests still come last.
        store.saveNode(new Node("com.Small", NodeType.CLASS, "Small", "com.Small", "src/main/java/com/Small.java", 1, "", 0));
        callsFrom("com.Small", "src/test/java/com/BbbTest.java", "BbbTest", "t()");
        callsFrom("com.Small", "src/main/java/com/Prod.java", "Prod", "go()");
        assertThat(mcpServer.handleCgNode(Map.of("symbol", "com.Small")))
            .contains("in CALLS (2, 1 in tests): Prod#go, BbbTest#t\n");
    }

    @Test
    void cgNodePointsAtCgRelatedByTheFullIdOfAMethod() {
        store.saveNode(new Node("com.Bus#dispatch(Command)", NodeType.METHOD, "dispatch",
            "com.Bus#dispatch(Command)", "Bus.java", 9, "", 0));
        callsFrom("com.Bus#dispatch(Command)", "src/main/java/com/Many.java", "Many",
            "m1()", "m2()", "m3()", "m4()", "m5()", "m6()", "m7()", "m8()", "m9()");

        var result = mcpServer.handleCgNode(Map.of("symbol", "com.Bus#dispatch(Command)"));

        assertThat(result).contains("in CALLS (9): Many#m1, Many#m2, Many#m3, Many#m4, Many#m5, Many#m6, Many#m7, "
            + "Many#m8, … +1 (more: cg_related com.Bus#dispatch(Command) direction=in depth=1)\n");
    }

    /** One class in {@code path} whose methods, given as signatures, each call {@code hubId}. */
    private void callsFrom(String hubId, String path, String owner, String... signatures) {
        store.saveNode(new Node("com." + owner, NodeType.SERVICE, owner, "com." + owner, path, 1, "", 0));
        for (String signature : signatures) {
            String id = "com." + owner + "#" + signature;
            store.saveNode(new Node(id, NodeType.METHOD, signature.substring(0, signature.indexOf('(')),
                id, path, 5, "", 0));
            store.saveEdge(new Edge("decl-" + id, EdgeType.DECLARES_METHOD, "com." + owner, id));
            store.saveEdge(new Edge("call-" + id + "-" + hubId, EdgeType.CALLS, id, hubId));
        }
    }

    /** {@code com.<owner>#<method>()}, declared by its class and calling {@code Bus#dispatch}. */
    private void callsBus(String owner, String method) {
        String id = "com." + owner + "#" + method + "()";
        store.saveNode(new Node(id, NodeType.METHOD, method, id, owner + ".java", 5, "", 0));
        store.saveEdge(new Edge("decl-" + id, EdgeType.DECLARES_METHOD, "com." + owner, id));
        store.saveEdge(new Edge("call-" + id, EdgeType.CALLS, id, "com.Bus#dispatch(Command)"));
    }

    @Test
    void cgNodeTypeLineTakesSupertypesFromTheDeclarationWhenNoEdgeNamesThem() {
        // nlp's index has no EXTENDS edge for "extends AggregateRoot<SalesOrderId>", so the Type line
        // read only AGGREGATE. The declaration says it: generics stay whole, a comma inside them does
        // not split a name, and a type parameter's own bound ("T extends Money") is not a supertype.
        store.saveNode(new Node("com.SalesOrder", NodeType.AGGREGATE, "SalesOrder", "com.SalesOrder",
            "SalesOrder.java", 12, """
            /**
             * Never extends Nothing.
             */
            @Entity
            public class SalesOrder<T extends Money>
                    extends AggregateRoot<SalesOrderId>
                    implements Auditable, Comparable<SalesOrder<T>>, Handler<A, B> {
                private UUID id;""", 0));
        // A generic method's bound is not a supertype either.
        store.saveNode(new Node("com.Svc#max(Comparable)", NodeType.METHOD, "max", "com.Svc#max(Comparable)",
            "Svc.java", 5, "<T extends Comparable<T>> T max(T a, T b) {", 0));

        assertThat(mcpServer.handleCgNode(Map.of("symbol", "SalesOrder"))).contains(
            "- **Type:** AGGREGATE extends AggregateRoot<SalesOrderId> "
                + "implements Auditable, Comparable<SalesOrder<T>>, Handler<A, B>\n");
        assertThat(mcpServer.handleCgNode(Map.of("symbol", "com.Svc#max(Comparable)")))
            .contains("- **Type:** METHOD\n");
    }

    @Test
    void cgNodeTypeLineKeepsAnEdgeOverTheDeclarationForItsKind() {
        store.saveNode(new Node("com.AggregateRoot", NodeType.CLASS, "AggregateRoot", "com.AggregateRoot",
            "AggregateRoot.java", 1, "", 0));
        store.saveNode(new Node("com.SalesOrder", NodeType.AGGREGATE, "SalesOrder", "com.SalesOrder",
            "SalesOrder.java", 1,
            "public class SalesOrder extends AggregateRoot<SalesOrderId> implements Auditable {", 0));
        store.saveEdge(new Edge("x1", EdgeType.EXTENDS, "com.SalesOrder", "com.AggregateRoot"));

        // The EXTENDS edge names its target; IMPLEMENTS has no edge, so the declaration fills it in.
        assertThat(mcpServer.handleCgNode(Map.of("symbol", "SalesOrder")))
            .contains("- **Type:** AGGREGATE extends AggregateRoot implements Auditable\n");
    }

    @Test
    void cgNodeWithIncludeCodeFalse() {
        var result = mcpServer.handleCgNode(Map.of("symbol", "Order", "includeCode", "false"));
        assertThat(result).contains("AGGREGATE");
        assertThat(result).doesNotContain("class Order extends AggregateRoot");
    }

    @Test
    void cgNodeListsControllerEndpoints() {
        // The class-level base path is already folded into methods.annotations by MethodExtractor,
        // so cg_node only has to render, sort by path, and skip methods with no HTTP annotation.
        store.saveMethod(new GraphStore.MethodRecord("com.OrderController", "create",
            "Order", "CreateOrderCommand cmd", "POST /orders", "public"));
        store.saveMethod(new GraphStore.MethodRecord("com.OrderController", "get",
            "Order", "String id", "GET /orders/{id}", "public"));
        store.saveMethod(new GraphStore.MethodRecord("com.OrderController", "helper",
            "void", "", null, "private"));

        var result = mcpServer.handleCgNode(Map.of("symbol", "OrderController"));

        assertThat(result).contains("### Endpoints");
        assertThat(result).contains("GET /orders/{id} → get");
        assertThat(result).contains("POST /orders → create");
        assertThat(result).doesNotContain("helper");
        // Sorted by path: /orders before /orders/{id}.
        assertThat(result.indexOf("POST /orders →")).isLessThan(result.indexOf("GET /orders/{id} →"));
    }

    @Test
    void cgNodeOmitsEndpointsSectionForNonController() {
        var result = mcpServer.handleCgNode(Map.of("symbol", "Order"));
        assertThat(result).doesNotContain("### Endpoints");
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
    void cgMethodResolvesPyModuleOwnerByBareOrQualifiedName() {
        store.saveNode(new Node("tools/build.py", NodeType.PY_MODULE, "build", "tools.build",
            "tools/build.py", 1, "", 0));
        store.saveNode(new Node("tools/build.py#build_or_update_graph()", NodeType.METHOD,
            "build_or_update_graph", "tools/build.py#build_or_update_graph()", "tools/build.py",
            468, "def build_or_update_graph():", 0));
        store.saveEdge(new Edge("dm", EdgeType.DECLARES_METHOD, "tools/build.py",
            "tools/build.py#build_or_update_graph()"));

        var bare = mcpServer.handleCgMethod(Map.of("symbol", "build#build_or_update_graph"));
        assertThat(bare).contains("tools/build.py#build_or_update_graph()").contains("468");

        var qualified = mcpServer.handleCgMethod(Map.of("symbol", "tools.build#build_or_update_graph"));
        assertThat(qualified).contains("tools/build.py#build_or_update_graph()");
    }

    @Test
    void cgMethodResolvesShellScriptOwner() {
        store.saveNode(new Node("clean-restart.sh", NodeType.SHELL_SCRIPT, "clean-restart",
            "clean-restart.sh", "clean-restart.sh", 1, "", 0));
        store.saveNode(new Node("clean-restart.sh#confirm()", NodeType.METHOD, "confirm",
            "clean-restart.sh#confirm()", "clean-restart.sh", 10, "confirm() {", 0));
        store.saveEdge(new Edge("dm", EdgeType.DECLARES_METHOD, "clean-restart.sh",
            "clean-restart.sh#confirm()"));

        var result = mcpServer.handleCgMethod(Map.of("symbol", "clean-restart#confirm"));
        assertThat(result).contains("clean-restart.sh#confirm()");
    }

    @Test
    void cgMethodStillResolvesJavaClassOwner() {
        addMethodGraph();

        var result = mcpServer.handleCgMethod(Map.of("symbol", "Order#complete"));
        assertThat(result).contains("com.Order#complete(UUID)");
    }

    @Test
    void classLevelQueriesHideMethodsAndMethodLevelQueriesShowThem() {
        addMethodGraph();

        // Asking about a class gets a class-level answer...
        var classLevel = mcpServer.handleCgImpact(Map.of("symbol", "Order", "depth", 1));
        assertThat(methodRows(classLevel)).isEmpty();

        // ...asking about a method gets a method-level one, without needing a flag.
        var methodLevel = mcpServer.handleCgImpact(
            Map.of("symbol", "com.Order#complete(UUID)", "depth", 1));
        assertThat(methodRows(methodLevel)).isNotEmpty();

        // And granularity overrides the default either way.
        var forced = mcpServer.handleCgImpact(
            Map.of("symbol", "Order", "depth", 1, "granularity", "method"));
        assertThat(methodRows(forced)).isNotEmpty();
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
    void outputTruncatedAt6kChars() {
        String longText = "x".repeat(20_000);
        String truncated = CodeNavigatorMcpServer.truncateOutput(longText, "cg_guard");
        assertThat(truncated.length()).isLessThanOrEqualTo(6_400);
        assertThat(truncated).contains("not shown");
    }

    @Test
    void truncationCutsWholeLinesAndSaysHowManyWereDropped() {
        // A blind substring severed the last entry mid-text and gave no hint anything was missing,
        // so a beheaded list read exactly like a complete one.
        String many = "line of output text\n".repeat(2_000);
        String truncated = CodeNavigatorMcpServer.truncateOutput(many, "cg_guard");

        assertThat(truncated).contains("more line(s) not shown");
        assertThat(truncated).contains("Narrow with");
        // Every rendered line is intact.
        var body = truncated.substring(0, truncated.indexOf("\n\n… "));
        assertThat(body.lines()).allMatch(l -> l.equals("line of output text"));
    }

    @Test
    void replyAtTheCapPassesThroughAndALongerOneIsCutAtALine() {
        String line = "y".repeat(99) + "\n";
        String atCap = line.repeat(60);                                        // exactly 6,000 chars
        assertThat(CodeNavigatorMcpServer.truncateOutput(atCap, "cg_guard")).isEqualTo(atCap);

        String cut = CodeNavigatorMcpServer.truncateOutput(line.repeat(70), "cg_guard");   // 7,000 chars
        assertThat(cut).contains("10 more line(s) not shown (60 of 70)");
        var body = cut.substring(0, cut.indexOf("\n\n… "));
        assertThat(body.length()).isLessThanOrEqualTo(6_000);
        assertThat(body.lines()).hasSize(60).allMatch(l -> l.length() == 99);
    }

    /** {@code count} packages that each call into one shared package, so {@code hub} is the most coupled. */
    private void addCoupledPackages(int count) {
        store.saveNode(new Node("hub.Hub", NodeType.SERVICE, "Hub", "hub.Hub", "hub/Hub.java", 1, "", 0));
        for (int i = 1; i <= count; i++) {
            String id = "lib.p" + i + ".Svc" + i;
            store.saveNode(new Node(id, NodeType.SERVICE, "Svc" + i, id, "lib/Svc" + i + ".java", 1, "", 0));
            store.saveEdge(new Edge("pe" + i, EdgeType.CALLS_METHOD, id, "hub.Hub"));
        }
    }

    /** {@code pairs} package pairs that call each other; the last pair has one more edge than the rest. */
    private void addPackageCycles(int pairs) {
        String prefix = "org.example.platform.subsystem.feature";
        for (int i = 1; i <= pairs; i++) {
            String a = prefix + i + ".alpha.A" + i;
            String b = prefix + i + ".beta.B" + i;
            store.saveNode(new Node(a, NodeType.SERVICE, "A" + i, a, "a/A" + i + ".java", 1, "", 0));
            store.saveNode(new Node(b, NodeType.SERVICE, "B" + i, b, "b/B" + i + ".java", 1, "", 0));
            store.saveEdge(new Edge("ca" + i, EdgeType.CALLS_METHOD, a, b));
            store.saveEdge(new Edge("cb" + i, EdgeType.CALLS_METHOD, b, a));
            if (i == pairs) store.saveEdge(new Edge("cx" + i, EdgeType.INJECTS, a, b));
        }
    }

    @Test
    void cgPackagesLimitCapsTheRowsAndSaysHowManyAreLeft() {
        addCoupledPackages(12);                                                // 14 packages with com and hub

        var result = mcpServer.handleCgPackages(Map.of("limit", 5));

        assertThat(result.lines().filter(l -> l.startsWith("- ")).count()).isEqualTo(5);
        assertThat(result).contains("… 9 more. Raise `limit`");
        assertThat(mcpServer.handleCgPackages(Map.of("limit", 20))).doesNotContain("more. Raise");
    }

    @Test
    void cgPackagesShowsTenRowsByDefaultMostCoupledFirst() {
        addCoupledPackages(12);

        var rows = mcpServer.handleCgPackages(Map.of()).lines().filter(l -> l.startsWith("- ")).toList();

        assertThat(rows).hasSize(10);
        assertThat(rows.get(0)).startsWith("- hub (1 nodes)").contains("<- 12 packages (12 edges)");
    }

    @Test
    void cgPackagesListsCyclesFirstMostEntangledFirstAndLimitCapsThemToo() {
        addPackageCycles(3);

        var result = mcpServer.handleCgPackages(Map.of("limit", 2));

        int rows = result.indexOf("## Package Dependencies");
        assertThat(result).startsWith("## Circular Dependencies (3)");
        assertThat(rows).isPositive();
        var cycles = result.substring(0, rows);
        var lines = cycles.lines().filter(l -> l.startsWith("- ")).toList();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("feature3");                         // the pair with the most edges
        assertThat(cycles).contains("… 1 more. Raise `limit`");
    }

    @Test
    void cgPackagesDefaultReplyStaysUnderTheOutputCap() {
        addPackageCycles(40);                                                  // 80 long-named packages, 40 cycles

        var result = mcpServer.handleCgPackages(Map.of());

        assertThat(result.length()).isLessThan(6_000);
        assertThat(result.lines().filter(l -> l.startsWith("- ")).count()).isEqualTo(20);   // 10 cycles + 10 rows
        assertThat(result).contains("… 30 more. Raise `limit`");               // cycles
        assertThat(result).contains("… 71 more. Raise `limit`");               // 81 packages
    }

    /** A hub with {@code leaves} callers and one node behind the first leaf, to cut chains by depth. */
    private void addExportFan(int leaves) {
        store.saveNode(new Node("fan.Hub", NodeType.SERVICE, "Hub", "fan.Hub", "fan/Hub.java", 1, "", 0));
        for (int i = 1; i <= leaves; i++) {
            String id = "fan.Leaf" + i;
            store.saveNode(new Node(id, NodeType.SERVICE, "Leaf" + i, id, "fan/Leaf" + i + ".java", 1, "", 0));
            store.saveEdge(new Edge("fe" + i, EdgeType.CALLS_METHOD, id, "fan.Hub"));
        }
        store.saveNode(new Node("fan.Deep", NodeType.SERVICE, "Deep", "fan.Deep", "fan/Deep.java", 1, "", 0));
        store.saveEdge(new Edge("fed", EdgeType.CALLS_METHOD, "fan.Deep", "fan.Leaf1"));
    }

    @Test
    void cgExportOverTheCapIsRefusedWithItsSizeNotCutInHalf() {
        addExportFan(150);

        for (String format : List.of("json", "mermaid", "plantuml")) {
            var result = mcpServer.handleCgExport(Map.of("format", format));

            assertThat(result).startsWith("Export refused");
            assertThat(result.length()).isLessThan(500);
            assertThat(result).contains("`symbol`", "`depth`");
            var size = Pattern.compile("would be ([\\d,]+) chars").matcher(result);
            assertThat(size.find()).isTrue();
            assertThat(Integer.parseInt(size.group(1).replace(",", ""))).isGreaterThan(6_000);
        }
    }

    @Test
    void cgExportDepthNarrowsAChainBelowTheCap() {
        addExportFan(150);

        var wholeChain = mcpServer.handleCgExport(Map.of("format", "mermaid", "symbol", "fan.Leaf1"));
        assertThat(wholeChain).startsWith("Export refused").contains("Pass `depth`");

        var narrowed = mcpServer.handleCgExport(Map.of("format", "mermaid", "symbol", "fan.Leaf1", "depth", 1));
        assertThat(narrowed).contains("graph LR", "Leaf1", "Hub", "Deep").doesNotContain("Leaf2");

        var alone = mcpServer.handleCgExport(Map.of("format", "mermaid", "symbol", "fan.Leaf1", "depth", 0));
        assertThat(alone).contains("Leaf1").doesNotContain("Hub");

        var tooWide = mcpServer.handleCgExport(Map.of("format", "mermaid", "symbol", "fan.Leaf1", "depth", 2));
        assertThat(tooWide).startsWith("Export refused").contains("Lower `depth`");
    }

    @Test
    void cgExportUnderTheCapIsTheWholeDocumentUnchanged() {
        var service = new ExportService();

        assertThat(mcpServer.handleCgExport(Map.of("format", "mermaid")))
            .isEqualTo(service.toMermaid(store.getAllNodes(), store.getAllEdges()));
        assertThat(mcpServer.handleCgExport(Map.of("format", "plantuml")))
            .isEqualTo(service.toPlantUml(store.getAllNodes(), store.getAllEdges()));
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

        assertThat(listed(result)).isEqualTo(1);
        assertThat(result).contains("more").contains("Raise `limit`");
    }

    @Test
    void cgChain_honoursLimit() {
        var result = mcpServer.handleCgChain(Map.of("symbol", "CreateOrderCommand", "limit", 1));

        assertThat(listed(result)).isEqualTo(1);
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

        assertThat(listed(result)).isEqualTo(1);
        assertThat(result).contains("more").contains("Raise `limit`");
    }

    @Test
    void cgDead_honoursLimit() {
        store.saveNode(new Node("com.DeadServiceA", NodeType.SERVICE, "DeadServiceA",
            "com.DeadServiceA", "DeadA.java", 1, "class DeadServiceA", 0));
        store.saveNode(new Node("com.DeadServiceB", NodeType.SERVICE, "DeadServiceB",
            "com.DeadServiceB", "DeadB.java", 1, "class DeadServiceB", 0));

        var result = mcpServer.handleCgDead(Map.of("limit", 1));

        assertThat(listed(result)).isEqualTo(1);
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

    // ---- default limit is 10; the "… N more" footer appears exactly when the list is cut ----

    private static final int ALL = 1000;

    /** WidgetHub, 12 nodes that point at it and 12 it points at, each in a file of its own. */
    private void addWidgets() {
        store.saveNode(new Node("com.WidgetHub", NodeType.SERVICE, "WidgetHub",
            "com.WidgetHub", "WidgetHub.java", 1, "class WidgetHub", 0));
        for (int i = 0; i < 12; i++) {
            String up = String.format("WidgetUp%02d", i);
            String down = String.format("WidgetDown%02d", i);
            store.saveNode(new Node("com." + up, NodeType.SERVICE, up,
                "com." + up, up + ".java", 1, "class " + up, 0));
            store.saveNode(new Node("com." + down, NodeType.SERVICE, down,
                "com." + down, down + ".java", 1, "class " + down, 0));
            store.saveEdge(new Edge("wu" + i, EdgeType.DISPATCHES_COMMAND, "com." + up, "com.WidgetHub"));
            store.saveEdge(new Edge("wd" + i, EdgeType.DISPATCHES_COMMAND, "com.WidgetHub", "com." + down));
        }
    }

    /** Rows of a directory-grouped reply: the indented lines under each directory line. */
    private static long listed(String result) {
        return result.lines().filter(l -> l.startsWith("  ")).count();
    }

    /** Rows that name a METHOD: the type is the row's last word. */
    private static List<String> methodRows(String reply) {
        return reply.lines().filter(l -> l.startsWith("  ") && l.endsWith(" METHOD")).toList();
    }

    private static Map<String, Object> withLimit(Map<String, Object> args, int limit) {
        var copy = new HashMap<>(args);
        copy.put("limit", limit);
        return copy;
    }

    /**
     * Without {@code limit} the list stops at 10 and says how many it dropped; {@code limit} moves
     * the cut; once nothing is cut there is no footer.
     */
    private void assertStopsAtTenByDefault(Function<Map<String, Object>, String> tool, Map<String, Object> args) {
        int total = (int) listed(tool.apply(withLimit(args, ALL)));
        assertThat(total).isGreaterThan(10);

        var byDefault = tool.apply(args);
        assertThat(listed(byDefault)).isEqualTo(10);
        assertThat(byDefault).contains("… " + (total - 10) + " more").contains("Raise `limit`");

        var oneShort = tool.apply(withLimit(args, total - 1));
        assertThat(listed(oneShort)).isEqualTo(total - 1);
        assertThat(oneShort).contains("… 1 more");

        var everything = tool.apply(withLimit(args, total));
        assertThat(listed(everything)).isEqualTo(total);
        assertThat(everything).doesNotContain("…");
    }

    @Test
    void cgSearch_stopsAtTenByDefault() {
        addWidgets();
        assertStopsAtTenByDefault(mcpServer::handleCgSearchTool, Map.of("query", "Widget"));
    }

    @Test
    void cgSearchFiles_stopsAtTenByDefault() {
        for (int i = 0; i < 12; i++) store.saveIndexedFile(String.format("Widget%02d.java", i), 0L, "");
        assertStopsAtTenByDefault(mcpServer::handleCgSearchTool,
            Map.of("query", "Widget", "files", "true"));
    }

    @Test
    void cgContext_stopsAtTenFilesByDefault() {
        addWidgets();
        assertStopsAtTenByDefault(mcpServer::handleCgContext, Map.of("task", "widget"));
    }

    @Test
    void cgRelatedBoth_stopsAtTenByDefault() {
        addWidgets();
        assertStopsAtTenByDefault(mcpServer::handleCgRelated,
            Map.of("symbol", "WidgetHub", "direction", "both", "depth", 1));
    }

    @Test
    void cgRelatedIn_stopsAtTenByDefault() {
        addWidgets();
        assertStopsAtTenByDefault(mcpServer::handleCgRelated,
            Map.of("symbol", "WidgetHub", "direction", "in"));
    }

    @Test
    void cgRelatedOut_stopsAtTenByDefault() {
        addWidgets();
        assertStopsAtTenByDefault(mcpServer::handleCgRelated,
            Map.of("symbol", "WidgetHub", "direction", "out"));
    }

    @Test
    void cgRelatedChain_stopsAtTenByDefault() {
        addWidgets();
        assertStopsAtTenByDefault(mcpServer::handleCgRelated,
            Map.of("symbol", "WidgetHub", "depth", 21));
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

    // ---- Directory-grouped rows: the directory once, then File.java:line Name TYPE ----

    /** Rebuilds the repo-relative {@code path:line} of every row: the directory line above it plus the file. */
    private static List<String> locationsInOrder(String reply) {
        var locations = new ArrayList<String>();
        String dir = "";
        for (String line : reply.lines().toList()) {
            if (line.startsWith("  ")) {
                locations.add(dir + line.strip().split(" ")[0]);
            } else if (line.endsWith("/")) {
                dir = line.equals("./") ? "" : line;
            }
        }
        return locations;
    }

    private void addGadgets(String... pathsInInsertionOrder) {
        for (int i = 0; i < pathsInInsertionOrder.length; i++) {
            String name = String.format("Gadget%02d", i);
            store.saveNode(new Node("com." + name, NodeType.SERVICE, name, "com." + name,
                pathsInInsertionOrder[i], i + 1, "class " + name, 0));
        }
    }

    private List<Node> rankedSearch(String query) {
        return new SearchService(store, new GraphTraversal(store)).search(query);
    }

    @Test
    void groupedReplyRebuildsEveryRepoRelativePathAndLine() {
        addGadgets("src/main/java/com/x/a/Gadget00.java", "src/main/java/com/x/a/Gadget01.java",
            "src/main/java/com/x/b/Gadget02.java", "Gadget03.java");

        var reply = mcpServer.handleCgSearch(Map.of("query", "Gadget", "limit", 50));

        var expected = rankedSearch("Gadget").stream()
            .map(n -> n.filePath() + ":" + n.lineNumber()).toList();
        assertThat(expected).hasSize(4);
        assertThat(locationsInOrder(reply)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(reply.lines().filter(l -> l.startsWith("  "))).hasSize(4)
            .noneMatch(l -> l.contains("**") || l.contains("`"));
    }

    @Test
    void rankedRowsKeepTheirRankWhenDirectoriesAlternate() {
        addGadgets("src/main/java/com/x/a/Gadget00.java", "src/main/java/com/x/b/Gadget01.java",
            "src/main/java/com/x/a/Gadget02.java", "src/main/java/com/x/b/Gadget03.java");
        var ranked = rankedSearch("Gadget");
        var dirs = ranked.stream()
            .map(n -> n.filePath().substring(0, n.filePath().lastIndexOf('/') + 1)).toList();
        // Precondition: the ranking really interleaves the two directories, else nothing could move.
        assertThat(dirs).containsSubsequence(
            "src/main/java/com/x/a/", "src/main/java/com/x/b/", "src/main/java/com/x/a/");

        var reply = mcpServer.handleCgSearch(Map.of("query", "Gadget", "limit", 50));

        assertThat(locationsInOrder(reply)).containsExactlyElementsOf(
            ranked.stream().map(n -> n.filePath() + ":" + n.lineNumber()).toList());
    }
}
