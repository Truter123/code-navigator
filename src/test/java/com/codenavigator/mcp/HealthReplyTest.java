package com.codenavigator.mcp;

import com.codenavigator.graph.*;
import com.codenavigator.search.SearchService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code cg_health}'s package views: a cycle line names each package once, and a {@code package}
 * prefix scopes both {@code packages} and {@code hotspots} to one package and its sub-packages.
 * "What is rotten in package X" had no tool that answered it: {@code packages} listed cycles that
 * repeated every name three times, and {@code hotspots} ranked the whole project.
 */
class HealthReplyTest {

    @TempDir Path tempDir;
    private GraphStore store;
    private CodeNavigatorMcpServer server;
    private int edges;

    @BeforeEach
    void setUp() {
        store = new GraphStore(tempDir.resolve("health.db"));
        var traversal = new GraphTraversal(store);
        server = new CodeNavigatorMcpServer(store, traversal, new SearchService(store, traversal));
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private void node(String id) {
        String name = id.substring(id.lastIndexOf('.') + 1);
        store.saveNode(new Node(id, NodeType.SERVICE, name, id, id.replace('.', '/') + ".java", 1, "", 0));
    }

    private void calls(String from, String to) {
        store.saveEdge(new Edge("e" + ++edges, EdgeType.CALLS_METHOD, from, to));
    }

    /** {@code pairs} package pairs that call each other; the last pair has one more edge alpha to beta. */
    private void addCycles(int pairs) {
        String prefix = "org.example.platform.subsystem.feature";
        for (int i = 1; i <= pairs; i++) {
            String a = prefix + i + ".alpha.A" + i;
            String b = prefix + i + ".beta.B" + i;
            node(a);
            node(b);
            calls(a, b);
            calls(b, a);
            if (i == pairs) store.saveEdge(new Edge("x" + i, EdgeType.INJECTS, a, b));
        }
    }

    @Test
    void aCycleLineNamesEachPackageOnceAndWritesASharedPrefixOnce() {
        addCycles(3);
        node("xx.One.A");
        node("yy.Two.B");
        calls("xx.One.A", "yy.Two.B");
        calls("yy.Two.B", "xx.One.A");

        var lines = server.handleCgPackages(Map.of("limit", 10)).lines().toList();

        assertThat(lines).contains(
            "- org.example.platform.subsystem.feature3.{alpha <-> beta} (2 / 1 edges)",
            "- org.example.platform.subsystem.feature1.{alpha <-> beta} (1 / 1 edges)",
            "- xx.One <-> yy.Two (1 / 1 edges)");
        assertThat(lines.get(0)).startsWith("## Circular Dependencies (4)").contains("left->right / right->left");
    }

    @Test
    void packagesCanBeScopedToOnePackageAndItsSubPackages() {
        addCycles(3);

        var result = server.handleCgPackages(Map.of("package", "org.example.platform.subsystem.feature2"));

        assertThat(result)
            .contains("feature2.{alpha <-> beta}")
            .contains("- org.example.platform.subsystem.feature2.alpha (1 nodes)")
            .doesNotContain("feature1").doesNotContain("feature3");
        // A prefix ends at a dot: "feature" is not the package "feature2".
        assertThat(server.handleCgPackages(Map.of("package", "org.example.platform.subsystem.feature")))
            .contains("Nothing under package `org.example.platform.subsystem.feature`");
    }

    @Test
    void packagesReplyPointsAtTheScopedHotspots() {
        addCycles(1);

        assertThat(server.handleCgPackages(Map.of())).contains("`cg_health kind=hotspots package=<name>`");
    }

    @Test
    void hotspotsCanBeScopedToAPackageAndItsSubPackages() {
        node("com.one.Hub");
        node("com.one.sub.Deep");
        node("com.two.Other");
        for (int i = 1; i <= 3; i++) {
            node("com.one.C" + i);
            calls("com.one.C" + i, "com.one.Hub");
        }
        calls("com.one.C1", "com.one.sub.Deep");
        for (int i = 1; i <= 5; i++) {
            node("com.two.D" + i);
            calls("com.two.D" + i, "com.two.Other");
        }

        var scoped = server.handleCgHotspots(Map.of("package", "com.one"));

        assertThat(scoped).startsWith("## Hotspots in com.one (top 10)")
            .contains("Hub (SERVICE) — 3 incoming edges")
            .contains("Deep (SERVICE) — 1 incoming edges")
            .contains("C1 (SERVICE) — 2 outgoing edges")
            .doesNotContain("Other").doesNotContain("D1");
        assertThat(server.handleCgHotspots(Map.of()))
            .startsWith("## Hotspots (top 10)").contains("Other (SERVICE) — 5 incoming edges");
        assertThat(server.handleCgHotspots(Map.of("package", "com.on")))
            .contains("Nothing under package `com.on`");
    }

    private void typeNode(String id, NodeType type, String path, int line) {
        store.saveNode(new Node(id, type, id.substring(id.lastIndexOf('.') + 1), id, path, line, "", 0));
    }

    /** {@code count} calls from {@code from} to distinct util nodes: its fan-out. */
    private void fanOut(String from, int count) {
        for (int i = 1; i <= count; i++) calls(from, "com.shop.util.T" + i);
    }

    /** Under com.shop.orders: three production types, a smaller one, a test type, a method, and one in another package. */
    private void addShop() {
        for (int i = 1; i <= 50; i++) node("com.shop.util.T" + i);
        typeNode("com.shop.orders.Order", NodeType.AGGREGATE, "src/main/java/com/shop/orders/Order.java", 7);
        typeNode("com.shop.orders.Applier", NodeType.EVENT_APPLIER, "src/main/java/com/shop/orders/Applier.java", 3);
        typeNode("com.shop.orders.billing.Invoice", NodeType.SERVICE, "src/main/java/com/shop/orders/billing/Invoice.java", 1);
        typeNode("com.shop.orders.Small", NodeType.SERVICE, "src/main/java/com/shop/orders/Small.java", 1);
        typeNode("com.shop.orders.OrderTest", NodeType.CLASS, "src/test/java/com/shop/orders/OrderTest.java", 1);
        typeNode("com.shop.orders.Order#run()", NodeType.METHOD, "src/main/java/com/shop/orders/Order.java", 9);
        typeNode("com.shop.other.Elsewhere", NodeType.SERVICE, "src/main/java/com/shop/other/Elsewhere.java", 1);
        fanOut("com.shop.orders.Order", 5);
        fanOut("com.shop.orders.Applier", 8);
        fanOut("com.shop.orders.billing.Invoice", 3);
        fanOut("com.shop.orders.Small", 1);
        fanOut("com.shop.orders.OrderTest", 20);
        fanOut("com.shop.orders.Order#run()", 30);
        fanOut("com.shop.other.Elsewhere", 50);
    }

    @Test
    void aScopedPackagesReplyListsThePackagesTopThreeProductionTypesByFanOut() {
        addShop();

        var reply = server.handleCgPackages(Map.of("package", "com.shop.orders"));

        assertThat(reply).contains("## Top types by fan-out");
        var section = reply.substring(reply.indexOf("## Top types by fan-out"));
        assertThat(section.lines().filter(l -> l.startsWith("  ") || l.startsWith("src/")).toList()).containsExactly(
            "src/main/java/com/shop/orders/",
            "  Applier.java:3 Applier EVENT_APPLIER — 8 out",
            "  Order.java:7 Order AGGREGATE — 5 out",
            "src/main/java/com/shop/orders/billing/",
            "  Invoice.java:1 Invoice SERVICE — 3 out");
        // Not a method (30 out), not a test type (20 out), not the fourth type, not another package's (50 out).
        assertThat(section).doesNotContain("METHOD").doesNotContain("OrderTest")
            .doesNotContain("Small").doesNotContain("Elsewhere");
    }

    @Test
    void anUnscopedPackagesReplyAndAScopeWithNoTypeWithOutgoingEdgesHaveNoTopTypesSection() {
        addShop();

        assertThat(server.handleCgPackages(Map.of())).doesNotContain("Top types by fan-out");
        assertThat(server.handleCgPackages(Map.of("package", "com.shop.util")))
            .contains("- com.shop.util (50 nodes)").doesNotContain("Top types by fan-out");
    }
}
