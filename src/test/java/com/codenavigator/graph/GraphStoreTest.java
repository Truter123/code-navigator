package com.codenavigator.graph;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GraphStoreTest {

    @TempDir
    Path tempDir;

    private GraphStore store;

    @BeforeEach
    void setUp() {
        store = new GraphStore(tempDir.resolve("test.db"));
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void saveAndFindNode() {
        Node node = new Node("n1", NodeType.CONTROLLER, "OrderController",
                "com.example.OrderController", "src/OrderController.java", 10,
                "public class OrderController {}", System.currentTimeMillis());

        store.saveNode(node);

        Optional<Node> found = store.findNodeById("n1");
        assertThat(found).isPresent();
        assertThat(found.get().type()).isEqualTo(NodeType.CONTROLLER);
        assertThat(found.get().name()).isEqualTo("OrderController");
    }

    @Test
    void saveAndFindEdge() {
        Node source = new Node("n1", NodeType.CONTROLLER, "Controller",
                "com.example.Controller", "src/Controller.java", 1, "", 0);
        Node target = new Node("n2", NodeType.SERVICE, "Service",
                "com.example.Service", "src/Service.java", 1, "", 0);
        store.saveNode(source);
        store.saveNode(target);

        Edge edge = new Edge("e1", EdgeType.INJECTS, "n1", "n2");
        store.saveEdge(edge);

        List<Edge> from = store.findEdgesFrom("n1");
        assertThat(from).hasSize(1);
        assertThat(from.get(0).targetId()).isEqualTo("n2");

        List<Edge> to = store.findEdgesTo("n2");
        assertThat(to).hasSize(1);
        assertThat(to.get(0).sourceId()).isEqualTo("n1");
    }

    @Test
    void findNodesByType() {
        store.saveNode(new Node("n1", NodeType.SERVICE, "Svc1", "com.Svc1", "f1", 1, "", 0));
        store.saveNode(new Node("n2", NodeType.SERVICE, "Svc2", "com.Svc2", "f2", 1, "", 0));
        store.saveNode(new Node("n3", NodeType.CONTROLLER, "Ctrl", "com.Ctrl", "f3", 1, "", 0));

        List<Node> services = store.findNodesByType(NodeType.SERVICE);
        assertThat(services).hasSize(2);
        assertThat(services).extracting(Node::name).containsExactlyInAnyOrder("Svc1", "Svc2");
    }

    @Test
    void deleteNodeCascadesEdges() {
        store.saveNode(new Node("n1", NodeType.CONTROLLER, "Ctrl", "com.Ctrl", "f1", 1, "", 0));
        store.saveNode(new Node("n2", NodeType.SERVICE, "Svc", "com.Svc", "f2", 1, "", 0));
        store.saveEdge(new Edge("e1", EdgeType.INJECTS, "n1", "n2"));

        store.deleteNode("n1");

        assertThat(store.findNodeById("n1")).isEmpty();
        assertThat(store.findEdgesFrom("n1")).isEmpty();
        assertThat(store.findEdgesTo("n2")).isEmpty();
    }

    @Test
    void deleteNodesByFilePath() {
        store.saveNode(new Node("n1", NodeType.CLASS, "A", "com.A", "file1.java", 1, "", 0));
        store.saveNode(new Node("n2", NodeType.CLASS, "B", "com.B", "file1.java", 10, "", 0));
        store.saveNode(new Node("n3", NodeType.CLASS, "C", "com.C", "file2.java", 1, "", 0));

        store.deleteNodesByFilePath("file1.java");

        assertThat(store.findNodeById("n1")).isEmpty();
        assertThat(store.findNodeById("n2")).isEmpty();
        assertThat(store.findNodeById("n3")).isPresent();
    }

    @Test
    void ftsSearch() {
        store.saveNode(new Node("n1", NodeType.AGGREGATE, "PurchaseOrder",
                "com.example.PurchaseOrder", "src/PurchaseOrder.java", 1,
                "public class PurchaseOrder extends Aggregate {}", 0));
        store.saveNode(new Node("n2", NodeType.COMMAND, "CreateCommand",
                "com.example.CreateCommand", "src/CreateCommand.java", 1,
                "Handles PurchaseOrder creation", 0));
        store.saveNode(new Node("n3", NodeType.SERVICE, "InventoryService",
                "com.example.InventoryService", "src/InventoryService.java", 1,
                "Manages inventory", 0));

        List<Node> results = store.searchFts("PurchaseOrder");
        assertThat(results).hasSize(2);
        assertThat(results).extracting(Node::name)
                .containsExactlyInAnyOrder("PurchaseOrder", "CreateCommand");
    }

    @Test
    void trackIndexedFiles() {
        assertThat(store.getIndexedFileModifiedTime("src/Foo.java")).isEqualTo(-1);

        store.saveIndexedFile("src/Foo.java", 1000L, "abc123");
        assertThat(store.getIndexedFileModifiedTime("src/Foo.java")).isEqualTo(1000L);

        store.saveIndexedFile("src/Foo.java", 2000L, "def456");
        assertThat(store.getIndexedFileModifiedTime("src/Foo.java")).isEqualTo(2000L);

        store.removeIndexedFile("src/Foo.java");
        assertThat(store.getIndexedFileModifiedTime("src/Foo.java")).isEqualTo(-1);
    }

    @Test
    void projectConfig() {
        assertThat(store.getConfig("missing")).isNull();

        store.setConfig("project.name", "my-app");
        assertThat(store.getConfig("project.name")).isEqualTo("my-app");

        store.setConfig("project.name", "renamed-app");
        assertThat(store.getConfig("project.name")).isEqualTo("renamed-app");
    }

    @Test
    void findEdgesFromByType() {
        store.saveNode(new Node("n1", NodeType.CONTROLLER, "Ctrl", "com.Ctrl", "f1", 1, "", 0));
        store.saveNode(new Node("n2", NodeType.SERVICE, "Svc", "com.Svc", "f2", 1, "", 0));
        store.saveNode(new Node("n3", NodeType.REPOSITORY, "Repo", "com.Repo", "f3", 1, "", 0));

        store.saveEdge(new Edge("e1", EdgeType.INJECTS, "n1", "n2"));
        store.saveEdge(new Edge("e2", EdgeType.CALLS_METHOD, "n1", "n3"));
        store.saveEdge(new Edge("e3", EdgeType.CALLS_METHOD, "n1", "n2"));

        List<Edge> callsMethod = store.findEdgesFromByType("n1", EdgeType.CALLS_METHOD);
        assertThat(callsMethod).hasSize(2);
        assertThat(callsMethod).extracting(Edge::type).containsOnly(EdgeType.CALLS_METHOD);

        List<Edge> injects = store.findEdgesFromByType("n1", EdgeType.INJECTS);
        assertThat(injects).hasSize(1);
        assertThat(injects.get(0).targetId()).isEqualTo("n2");
    }

    @Test
    void findEdgesToByType() {
        store.saveNode(new Node("n1", NodeType.CONTROLLER, "Ctrl", "com.Ctrl", "f1", 1, "", 0));
        store.saveNode(new Node("n2", NodeType.SERVICE, "Svc", "com.Svc", "f2", 1, "", 0));
        store.saveNode(new Node("n3", NodeType.REPOSITORY, "Repo", "com.Repo", "f3", 1, "", 0));

        store.saveEdge(new Edge("e1", EdgeType.INJECTS, "n1", "n2"));
        store.saveEdge(new Edge("e2", EdgeType.CALLS_METHOD, "n3", "n2"));
        store.saveEdge(new Edge("e3", EdgeType.EXTENDS, "n1", "n2"));

        List<Edge> callsMethod = store.findEdgesToByType("n2", EdgeType.CALLS_METHOD);
        assertThat(callsMethod).hasSize(1);
        assertThat(callsMethod.get(0).sourceId()).isEqualTo("n3");

        List<Edge> injects = store.findEdgesToByType("n2", EdgeType.INJECTS);
        assertThat(injects).hasSize(1);
        assertThat(injects.get(0).sourceId()).isEqualTo("n1");

        List<Edge> empty = store.findEdgesToByType("n2", EdgeType.READS_VIEW);
        assertThat(empty).isEmpty();
    }

    @Test
    void getNodeCount() {
        assertThat(store.getNodeCount()).isEqualTo(0);
        store.saveNode(new Node("n1", NodeType.CLASS, "A", "com.A", "f1", 1, "", 0));
        store.saveNode(new Node("n2", NodeType.CLASS, "B", "com.B", "f2", 1, "", 0));
        assertThat(store.getNodeCount()).isEqualTo(2);
    }

    @Test
    void getEdgeCount() {
        assertThat(store.getEdgeCount()).isEqualTo(0);
        store.saveNode(new Node("n1", NodeType.CLASS, "A", "com.A", "f1", 1, "", 0));
        store.saveNode(new Node("n2", NodeType.CLASS, "B", "com.B", "f2", 1, "", 0));
        store.saveEdge(new Edge("e1", EdgeType.EXTENDS, "n1", "n2"));
        assertThat(store.getEdgeCount()).isEqualTo(1);
    }

    @Test
    void getFileCount() {
        assertThat(store.getFileCount()).isEqualTo(0);
        store.saveIndexedFile("src/A.java", 1000L, "abc");
        store.saveIndexedFile("src/B.java", 2000L, "def");
        assertThat(store.getFileCount()).isEqualTo(2);
    }

    @Test
    void getAllIndexedFiles() {
        assertThat(store.getAllIndexedFiles()).isEmpty();
        store.saveIndexedFile("src/B.java", 1000L, "abc");
        store.saveIndexedFile("src/A.java", 2000L, "def");
        List<String> files = store.getAllIndexedFiles();
        assertThat(files).containsExactly("src/A.java", "src/B.java");
    }

    @Test
    void getAllNodesAndEdges() {
        store.saveNode(new Node("n1", NodeType.CLASS, "A", "com.A", "f1", 1, "", 0));
        store.saveNode(new Node("n2", NodeType.CLASS, "B", "com.B", "f2", 1, "", 0));
        store.saveNode(new Node("n3", NodeType.CLASS, "C", "com.C", "f3", 1, "", 0));
        store.saveEdge(new Edge("e1", EdgeType.EXTENDS, "n1", "n2"));
        store.saveEdge(new Edge("e2", EdgeType.IMPLEMENTS, "n2", "n3"));

        assertThat(store.getAllNodes()).hasSize(3);
        assertThat(store.getAllEdges()).hasSize(2);
    }

    @Test
    void findHotspots_fanIn() {
        store.saveNode(new Node("n1", NodeType.SERVICE, "PopularSvc", "com.PopularSvc", "f1", 1, "", 0));
        store.saveNode(new Node("n2", NodeType.CONTROLLER, "Ctrl1", "com.Ctrl1", "f2", 1, "", 0));
        store.saveNode(new Node("n3", NodeType.CONTROLLER, "Ctrl2", "com.Ctrl2", "f3", 1, "", 0));
        store.saveNode(new Node("n4", NodeType.CONTROLLER, "Ctrl3", "com.Ctrl3", "f4", 1, "", 0));
        store.saveNode(new Node("n5", NodeType.SERVICE, "LessSvc", "com.LessSvc", "f5", 1, "", 0));

        store.saveEdge(new Edge("e1", EdgeType.INJECTS, "n2", "n1"));
        store.saveEdge(new Edge("e2", EdgeType.INJECTS, "n3", "n1"));
        store.saveEdge(new Edge("e3", EdgeType.INJECTS, "n4", "n1"));
        store.saveEdge(new Edge("e4", EdgeType.INJECTS, "n2", "n5"));

        var fanIn = store.findTopFanIn(2);
        assertThat(fanIn).hasSize(2);
        assertThat(fanIn.get(0).node().name()).isEqualTo("PopularSvc");
        assertThat(fanIn.get(0).count()).isEqualTo(3);
    }

    @Test
    void findHotspots_fanOut() {
        store.saveNode(new Node("n1", NodeType.CONTROLLER, "BigCtrl", "com.BigCtrl", "f1", 1, "", 0));
        store.saveNode(new Node("n2", NodeType.SERVICE, "Svc1", "com.Svc1", "f2", 1, "", 0));
        store.saveNode(new Node("n3", NodeType.SERVICE, "Svc2", "com.Svc2", "f3", 1, "", 0));
        store.saveNode(new Node("n4", NodeType.REPOSITORY, "Repo", "com.Repo", "f4", 1, "", 0));

        store.saveEdge(new Edge("e1", EdgeType.INJECTS, "n1", "n2"));
        store.saveEdge(new Edge("e2", EdgeType.INJECTS, "n1", "n3"));
        store.saveEdge(new Edge("e3", EdgeType.CALLS_METHOD, "n1", "n4"));

        var fanOut = store.findTopFanOut(2);
        assertThat(fanOut).hasSize(1);
        assertThat(fanOut.get(0).node().name()).isEqualTo("BigCtrl");
        assertThat(fanOut.get(0).count()).isEqualTo(3);
    }

    @Test
    void findDeadNodes_returnsNodesWithNoIncomingEdges() {
        store.saveNode(new Node("n1", NodeType.CONTROLLER, "Ctrl", "com.Ctrl", "f1", 1, "", 0));
        store.saveNode(new Node("n2", NodeType.SERVICE, "Svc", "com.Svc", "f2", 1, "", 0));
        store.saveNode(new Node("n3", NodeType.REPOSITORY, "Repo", "com.Repo", "f3", 1, "", 0));
        store.saveNode(new Node("n4", NodeType.SERVICE, "DeadSvc", "com.DeadSvc", "f4", 1, "", 0));
        store.saveNode(new Node("n5", NodeType.CONFIGURATION, "Config", "com.Config", "f5", 1, "", 0));

        store.saveEdge(new Edge("e1", EdgeType.INJECTS, "n1", "n2"));
        store.saveEdge(new Edge("e2", EdgeType.CALLS_METHOD, "n2", "n3"));

        List<Node> dead = store.findDeadNodes(null);
        assertThat(dead).extracting(Node::name).contains("Ctrl", "DeadSvc");
        assertThat(dead).extracting(Node::name).doesNotContain("Config");
    }

    @Test
    void saveAndFindMethods() {
        store.saveNode(new Node("n1", NodeType.CONTROLLER, "Ctrl", "com.Ctrl", "f1", 1, "", 0));

        store.saveMethod(new GraphStore.MethodRecord("n1", "getAll", "ResponseEntity<List<Item>>", "", "GET /items", "public"));
        store.saveMethod(new GraphStore.MethodRecord("n1", "create", "ResponseEntity<Item>", "CreateRequest req", "POST /items", "public"));

        var methods = store.findMethodsByNodeId("n1");
        assertThat(methods).hasSize(2);
        assertThat(methods).extracting(GraphStore.MethodRecord::name).containsExactlyInAnyOrder("getAll", "create");
        assertThat(methods.stream().filter(m -> m.name().equals("getAll")).findFirst().get().annotations()).isEqualTo("GET /items");
    }

    @Test
    void deleteMethodsByNodeId() {
        store.saveNode(new Node("n1", NodeType.SERVICE, "Svc", "com.Svc", "f1", 1, "", 0));
        store.saveMethod(new GraphStore.MethodRecord("n1", "findAll", "List<Item>", "", null, "public"));
        assertThat(store.findMethodsByNodeId("n1")).hasSize(1);

        store.deleteMethodsByNodeId("n1");
        assertThat(store.findMethodsByNodeId("n1")).isEmpty();
    }

    @Test
    void findMethodsByNodeIds() {
        store.saveNode(new Node("n1", NodeType.SERVICE, "Svc1", "com.Svc1", "f1", 1, "", 0));
        store.saveNode(new Node("n2", NodeType.SERVICE, "Svc2", "com.Svc2", "f2", 1, "", 0));
        store.saveMethod(new GraphStore.MethodRecord("n1", "findAll", "List<A>", "", null, "public"));
        store.saveMethod(new GraphStore.MethodRecord("n2", "findAll", "List<B>", "", null, "public"));

        var methods = store.findMethodsByNodeIds(List.of("n1", "n2"));
        assertThat(methods).hasSize(2);
    }

    @Test
    void deleteNodeCascadesMethodsToo() {
        store.saveNode(new Node("n1", NodeType.SERVICE, "Svc", "com.Svc", "f1", 1, "", 0));
        store.saveMethod(new GraphStore.MethodRecord("n1", "findAll", "List<Item>", "", null, "public"));

        store.deleteNode("n1");
        assertThat(store.findMethodsByNodeId("n1")).isEmpty();
    }

    @Test
    void recordFieldsStoredAsMethods() {
        store.saveNode(new Node("n1", NodeType.RECORD, "Habit", "com.Habit", "f1", 1, "", 0));
        store.saveMethod(new GraphStore.MethodRecord("n1", "id", "UUID", null, null, "field"));
        store.saveMethod(new GraphStore.MethodRecord("n1", "name", "String", null, null, "field"));

        var fields = store.findMethodsByNodeId("n1");
        assertThat(fields).hasSize(2);
        assertThat(fields).allMatch(m -> m.visibility().equals("field"));
    }

    @Test
    void findDeadNodes_filteredByType() {
        store.saveNode(new Node("n1", NodeType.CONTROLLER, "Ctrl", "com.Ctrl", "f1", 1, "", 0));
        store.saveNode(new Node("n2", NodeType.SERVICE, "Svc", "com.Svc", "f2", 1, "", 0));

        List<Node> dead = store.findDeadNodes(NodeType.SERVICE);
        assertThat(dead).extracting(Node::name).containsExactly("Svc");
        assertThat(dead).extracting(Node::name).doesNotContain("Ctrl");
    }

    // ---- findDeadNodeCandidates: supertype-aware, entry-point-aware, test-aware, tiered ----

    @Test
    void findDeadNodeCandidates_excludesImplWhoseInterfaceIsInjected() {
        // The exact shape of the verified false positive: AndonReadsImpl implements AndonReads;
        // callers inject the interface, so the impl itself has zero incoming edges.
        store.saveNode(new Node("impl", NodeType.SERVICE, "AndonReadsImpl", "com.AndonReadsImpl", "f1", 1, "", 0));
        store.saveNode(new Node("iface", NodeType.INTERFACE, "AndonReads", "com.AndonReads", "f2", 1, "", 0));
        store.saveNode(new Node("caller", NodeType.CONTROLLER, "AndonController", "com.AndonController", "f3", 1, "", 0));
        store.saveEdge(new Edge("e1", EdgeType.IMPLEMENTS, "impl", "iface"));
        store.saveEdge(new Edge("e2", EdgeType.INJECTS, "caller", "iface"));

        var candidates = store.findDeadNodeCandidates(NodeType.SERVICE);
        assertThat(candidates).extracting(c -> c.node().name()).doesNotContain("AndonReadsImpl");
    }

    @Test
    void findDeadNodeCandidates_transitiveSupertypeIndirection() {
        // A extends B extends C; only C is actually referenced. A and B must both be excluded.
        store.saveNode(new Node("a", NodeType.CLASS, "A", "com.A", "f1", 1, "", 0));
        store.saveNode(new Node("b", NodeType.CLASS, "B", "com.B", "f2", 1, "", 0));
        store.saveNode(new Node("c", NodeType.CLASS, "C", "com.C", "f3", 1, "", 0));
        store.saveNode(new Node("caller", NodeType.SERVICE, "Caller", "com.Caller", "f4", 1, "", 0));
        store.saveEdge(new Edge("e1", EdgeType.EXTENDS, "a", "b"));
        store.saveEdge(new Edge("e2", EdgeType.EXTENDS, "b", "c"));
        store.saveEdge(new Edge("e3", EdgeType.CALLS_METHOD, "caller", "c"));

        var candidates = store.findDeadNodeCandidates(null);
        assertThat(candidates).extracting(c -> c.node().name()).doesNotContain("A", "B");
    }

    @Test
    void findDeadNodeCandidates_interfaceWithSoleImplementorIsStillDead() {
        // Regression guard: an interface's incoming IMPLEMENTS edge from its own (otherwise
        // unreferenced) implementor must NOT count as "the interface is live" — otherwise every
        // class that implements anything would be excluded regardless of real usage.
        store.saveNode(new Node("impl", NodeType.SERVICE, "OnlyImpl", "com.OnlyImpl", "f1", 1, "", 0));
        store.saveNode(new Node("iface", NodeType.INTERFACE, "OnlyIface", "com.OnlyIface", "f2", 1, "", 0));
        store.saveEdge(new Edge("e1", EdgeType.IMPLEMENTS, "impl", "iface"));

        var candidates = store.findDeadNodeCandidates(NodeType.SERVICE);
        assertThat(candidates).extracting(c -> c.node().name()).contains("OnlyImpl");
        assertThat(candidates.stream().filter(c -> c.node().name().equals("OnlyImpl")).findFirst().get().confidence())
            .isEqualTo(DeadCodeConfidence.HIGH);
    }

    @Test
    void findDeadNodeCandidates_demotesFrameworkEntryPoints() {
        store.saveNode(new Node("n1", NodeType.CONTROLLER, "OrphanController", "com.OrphanController", "f1", 1, "", 0));

        var candidates = store.findDeadNodeCandidates(NodeType.CONTROLLER);
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).confidence()).isEqualTo(DeadCodeConfidence.LOW);
    }

    @Test
    void findDeadNodeCandidates_testSourceIsNeverDead() {
        store.saveNode(new Node("n1", NodeType.SERVICE, "FooTest", "com.FooTest", "src/test/java/com/FooTest.java", 1, "", 0));
        store.saveNode(new Node("n2", NodeType.FE_COMPONENT, "BarSpec", "com.BarSpec", "src/app/bar.spec.ts", 1, "", 0));

        var candidates = store.findDeadNodeCandidates(null);
        var testCandidates = candidates.stream()
            .filter(c -> c.node().name().equals("FooTest") || c.node().name().equals("BarSpec")).toList();
        assertThat(testCandidates).hasSize(2);
        assertThat(testCandidates).allMatch(c -> c.confidence() == DeadCodeConfidence.TEST_SOURCE);
    }

    @Test
    void findDeadNodeCandidates_flagsUsedOnlyByTests() {
        store.saveNode(new Node("prod", NodeType.SERVICE, "HelperUtil", "com.HelperUtil", "src/main/java/com/HelperUtil.java", 1, "", 0));
        store.saveNode(new Node("test", NodeType.SERVICE, "HelperUtilTest", "com.HelperUtilTest", "src/test/java/com/HelperUtilTest.java", 1, "", 0));
        store.saveEdge(new Edge("e1", EdgeType.CALLS_METHOD, "test", "prod"));

        var candidates = store.findDeadNodeCandidates(null);
        var found = candidates.stream().filter(c -> c.node().name().equals("HelperUtil")).findFirst();
        assertThat(found).isPresent();
        assertThat(found.get().confidence()).isEqualTo(DeadCodeConfidence.USED_ONLY_BY_TESTS);
    }

    @Test
    void findDeadNodeCandidates_ranksHighVsMedium() {
        store.saveNode(new Node("n1", NodeType.SERVICE, "OrphanService", "com.OrphanService", "f1", 1, "", 0));
        store.saveNode(new Node("n2", NodeType.ENTITY, "OrphanEntity", "com.OrphanEntity", "f2", 1, "", 0));

        var candidates = store.findDeadNodeCandidates(null);
        var byName = candidates.stream().collect(java.util.stream.Collectors.toMap(c -> c.node().name(), c -> c));
        assertThat(byName.get("OrphanService").confidence()).isEqualTo(DeadCodeConfidence.HIGH);
        assertThat(byName.get("OrphanEntity").confidence()).isEqualTo(DeadCodeConfidence.MEDIUM);
    }

    @Test
    void findDeadNodeCandidates_respectsTypeFilter() {
        store.saveNode(new Node("n1", NodeType.CONTROLLER, "Ctrl", "com.Ctrl", "f1", 1, "", 0));
        store.saveNode(new Node("n2", NodeType.SERVICE, "Svc", "com.Svc", "f2", 1, "", 0));

        var candidates = store.findDeadNodeCandidates(NodeType.SERVICE);
        assertThat(candidates).extracting(c -> c.node().name()).containsExactly("Svc");
    }

    // ---- co_change tests ----

    @Test
    void coChangeUpsert_insertsNewPair() {
        store.upsertCoChange("src/A.java", "src/B.java");
        store.upsertCoChange("src/A.java", "src/B.java");
        store.upsertCoChange("src/A.java", "src/B.java");

        var coupled = store.topCoupled("src/A.java", 5);
        assertThat(coupled).hasSize(1);
        assertThat(coupled.get(0).otherFile()).isEqualTo("src/B.java");
        assertThat(coupled.get(0).count()).isEqualTo(3);
    }

    @Test
    void coChangeUpsert_canonicalOrdering() {
        // Insert in both orderings — must accumulate on the same row
        store.upsertCoChange("src/Z.java", "src/A.java");
        store.upsertCoChange("src/A.java", "src/Z.java");

        var fromA = store.topCoupled("src/A.java", 5);
        var fromZ = store.topCoupled("src/Z.java", 5);
        assertThat(fromA).hasSize(1);
        assertThat(fromA.get(0).count()).isEqualTo(2);
        assertThat(fromZ).hasSize(1);
        assertThat(fromZ.get(0).count()).isEqualTo(2);
    }

    @Test
    void coChangeUpsert_topCoupled_limitAndOrdering() {
        // A-B: 3 commits, A-C: 5 commits, A-D: 1 commit
        for (int i = 0; i < 3; i++) store.upsertCoChange("src/A.java", "src/B.java");
        for (int i = 0; i < 5; i++) store.upsertCoChange("src/A.java", "src/C.java");
        store.upsertCoChange("src/A.java", "src/D.java");

        var top2 = store.topCoupled("src/A.java", 2);
        assertThat(top2).hasSize(2);
        assertThat(top2.get(0).otherFile()).isEqualTo("src/C.java");
        assertThat(top2.get(0).count()).isEqualTo(5);
        assertThat(top2.get(1).otherFile()).isEqualTo("src/B.java");
        assertThat(top2.get(1).count()).isEqualTo(3);
    }

    @Test
    void topCoupled_returnsEmptyWhenNoData() {
        var result = store.topCoupled("nonexistent/File.java", 10);
        assertThat(result).isEmpty();
    }

}
