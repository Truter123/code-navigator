package com.codenavigator.search;

import com.codenavigator.graph.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SearchServiceTest {

    @TempDir Path tempDir;
    private GraphStore store;
    private SearchService searchService;

    @BeforeEach
    void setUp() {
        store = new GraphStore(tempDir.resolve("test.db"));
        searchService = new SearchService(store, new GraphTraversal(store));

        store.saveNode(new Node("com.CreatePurchaseOrderCommand", NodeType.COMMAND,
            "CreatePurchaseOrderCommand", "com.CreatePurchaseOrderCommand", "f.java", 1,
            "public record CreatePurchaseOrderCommand implements Command", 0));
        store.saveNode(new Node("com.PurchaseOrder", NodeType.AGGREGATE,
            "PurchaseOrder", "com.PurchaseOrder", "g.java", 1,
            "public class PurchaseOrder extends AggregateRoot", 0));
        store.saveNode(new Node("com.WorkerController", NodeType.CONTROLLER,
            "WorkerController", "com.WorkerController", "h.java", 1,
            "public class WorkerController", 0));
    }

    @AfterEach
    void tearDown() { store.close(); }

    @Test
    void searchByName() {
        var results = searchService.search("PurchaseOrder");
        assertThat(results).hasSize(2);
    }

    @Test
    void extractKeywords() {
        var keywords = searchService.extractKeywords("add notes field to purchase orders");
        assertThat(keywords).contains("notes", "purchase", "orders");
        assertThat(keywords).doesNotContain("add", "to", "field");
    }

    @Test
    void contextSearchExpandsViaChain() {
        // Add edges so chain expansion works
        store.saveEdge(new Edge("e1", EdgeType.DISPATCHES_COMMAND,
            "com.WorkerController", "com.CreatePurchaseOrderCommand"));

        var results = searchService.contextSearch("worker controller");
        assertThat(results).extracting(Node::name)
            .contains("WorkerController", "CreatePurchaseOrderCommand");
    }

    @Test
    void contextSearchReadsTheGraphOnceWhateverTheNumberOfSeeds() {
        // Thirty nodes match every keyword and all hang off one hub. Ranking loads all nodes and
        // edges in bulk: no per-node edge lookups, whatever the number of seeds. The default file
        // budget of ten then holds back the other twenty seeds (was: every node of the component).
        try (var counting = new CountingStore(tempDir.resolve("many-seeds.db"))) {
            counting.saveNode(new Node("com.ledger.Ledger", NodeType.CLASS,
                "Ledger", "com.ledger.Ledger", "ledger.java", 1, "public class Ledger", 0));
            for (int i = 0; i < 30; i++) {
                String id = "com.sales.order.cancellation.Handler" + i;
                counting.saveNode(new Node(id, NodeType.CLASS, "Handler" + i, id,
                    "h" + i + ".java", 1, "public class Handler" + i, 0));
                counting.saveEdge(new Edge("e" + i, EdgeType.CALLS_METHOD, id, "com.ledger.Ledger"));
            }
            var service = new SearchService(counting, new GraphTraversal(counting));

            var results = service.contextSearch("modify sales order cancellation");

            assertThat(results).hasSize(10);
            assertThat(results).extracting(Node::filePath).doesNotHaveDuplicates();
            assertThat(counting.edgeLookups).isZero();
        }
    }

    @Test
    void contextSearchStopsAtTheFileBudgetInsideALargeComponent() {
        // One seed at the head of a 61-node chain over 61 files. The whole component used to come
        // back; now at most the budgeted files, nearest the seed first.
        store.saveNode(new Node("com.gadget.Gadget", NodeType.CLASS, "Gadget", "com.gadget.Gadget",
            "gadget.java", 1, "public class Gadget", 0));
        String previous = "com.gadget.Gadget";
        for (int i = 0; i < 60; i++) {
            String id = "com.chain.Part" + i;
            store.saveNode(new Node(id, NodeType.CLASS, "Part" + i, id, "part" + i + ".java", 1, "class Part" + i, 0));
            store.saveEdge(new Edge("chain" + i, EdgeType.CALLS_METHOD, previous, id));
            previous = id;
        }

        var results = searchService.contextSearch("gadget", 10);

        assertThat(results).extracting(Node::filePath).doesNotHaveDuplicates().hasSizeLessThanOrEqualTo(10);
        assertThat(results).hasSizeLessThan(61);
        assertThat(results.get(0).name()).isEqualTo("Gadget");
        assertThat(results).extracting(Node::name).contains("Part0", "Part1").doesNotContain("Part59");
    }

    @Test
    void contextSearchRanksANodeLinkedToTwoSeedsAboveOnesLinkedToOneOrNone() {
        store.saveNode(new Node("com.GadgetOne", NodeType.CLASS, "GadgetOne", "com.GadgetOne", "g1.java", 1, "class GadgetOne", 0));
        store.saveNode(new Node("com.GadgetTwo", NodeType.CLASS, "GadgetTwo", "com.GadgetTwo", "g2.java", 1, "class GadgetTwo", 0));
        store.saveNode(new Node("com.Linker", NodeType.CLASS, "Linker", "com.Linker", "linker.java", 1, "class Linker", 0));
        store.saveNode(new Node("com.Lone", NodeType.CLASS, "Lone", "com.Lone", "lone.java", 1, "class Lone", 0));
        store.saveNode(new Node("com.Stranger", NodeType.CLASS, "Stranger", "com.Stranger", "stranger.java", 1, "class Stranger", 0));
        store.saveEdge(new Edge("l1", EdgeType.CALLS_METHOD, "com.Linker", "com.GadgetOne"));
        store.saveEdge(new Edge("l2", EdgeType.CALLS_METHOD, "com.Linker", "com.GadgetTwo"));
        store.saveEdge(new Edge("l3", EdgeType.CALLS_METHOD, "com.Lone", "com.GadgetOne"));

        var names = searchService.contextSearch("gadget").stream().map(Node::name).toList();

        assertThat(names.indexOf("Linker")).isGreaterThanOrEqualTo(0).isLessThan(names.indexOf("Lone"));
        // Reached from no seed: score zero, never returned.
        assertThat(names).doesNotContain("Stranger");
    }

    @Test
    void contextSearchNeverRanksANonSeedAboveASeed() {
        store.saveNode(new Node("com.GadgetOne", NodeType.CLASS, "GadgetOne", "com.GadgetOne", "g1.java", 1, "class GadgetOne", 0));
        store.saveNode(new Node("com.GadgetTwo", NodeType.CLASS, "GadgetTwo", "com.GadgetTwo", "g2.java", 1, "class GadgetTwo", 0));
        store.saveNode(new Node("com.Linker", NodeType.CLASS, "Linker", "com.Linker", "linker.java", 1, "class Linker", 0));
        store.saveEdge(new Edge("l1", EdgeType.CALLS_METHOD, "com.Linker", "com.GadgetOne"));
        store.saveEdge(new Edge("l2", EdgeType.CALLS_METHOD, "com.Linker", "com.GadgetTwo"));

        var names = searchService.contextSearch("gadget").stream().map(Node::name).toList();

        assertThat(names.indexOf("Linker")).isEqualTo(names.size() - 1);
    }

    @Test
    void contextSearchLeavesOutTestSourcesAndLibraries() {
        store.saveNode(new Node("com.GadgetOne", NodeType.CLASS, "GadgetOne", "com.GadgetOne", "g1.java", 1, "class GadgetOne", 0));
        store.saveNode(new Node("com.GadgetTest", NodeType.CLASS, "GadgetTest", "com.GadgetTest",
            "src/test/java/com/GadgetTest.java", 1, "class GadgetTest", 0));
        store.saveNode(new Node("com.Helper", NodeType.CLASS, "Helper", "com.Helper",
            "src/test/java/com/Helper.java", 1, "class Helper", 0));
        store.saveNode(new Node("lib:gadget-lib", NodeType.LIBRARY, "gadget-lib", "gadget-lib", "", 0, "", 0));
        store.saveEdge(new Edge("t1", EdgeType.CALLS_METHOD, "com.Helper", "com.GadgetOne"));
        store.saveEdge(new Edge("t2", EdgeType.USES_LIBRARY, "com.GadgetOne", "lib:gadget-lib"));

        var names = searchService.contextSearch("gadget").stream().map(Node::name).toList();

        assertThat(names).containsExactly("GadgetOne");
    }

    @Test
    void contextSearchRanksNodesMatchingMoreKeywordsFirst() {
        // Three nodes match only "sales" and are saved first, so FTS returns them first; the node
        // that matches all three keywords says the most about the task and must lead.
        for (int i = 0; i < 3; i++) {
            String id = "com.sales.Quote" + i;
            store.saveNode(new Node(id, NodeType.CLASS, "Quote" + i, id,
                "q" + i + ".java", 1, "public class Quote" + i, 0));
        }
        store.saveNode(new Node("com.sales.order.cancellation.Cancel", NodeType.CLASS, "Cancel",
            "com.sales.order.cancellation.Cancel", "c.java", 1, "public class Cancel", 0));

        var results = searchService.contextSearch("modify sales order cancellation");

        assertThat(results.get(0).name()).isEqualTo("Cancel");
    }

    @Test
    void stemReducesInflectionsToTheirCommonPrefix() {
        // "cancellation", "cancelled", "cancelling" and "cancels" all ask for what "cancel" names.
        for (String word : new String[] {"cancellation", "cancellations", "cancelled", "cancelling", "cancels", "cancel"}) {
            assertThat(SearchService.stem(word)).as(word).isEqualTo("cancel");
        }
        assertThat(SearchService.stem("orders")).isEqualTo("order");
        assertThat(SearchService.stem("sales")).isEqualTo("sale");
        assertThat(SearchService.stem("controller")).isEqualTo("control");
        assertThat(SearchService.stem("stopped")).isEqualTo("stop");
    }

    @Test
    void stemLeavesAWordAloneWhenTheEndingIsNotASuffix() {
        // A stem under four characters would match half the graph: "order" is not "ord" + "er".
        assertThat(SearchService.stem("order")).isEqualTo("order");
        // A doubled consonant is undoubled only after a suffix came off: "install" keeps both l.
        assertThat(SearchService.stem("install")).isEqualTo("install");
        assertThat(SearchService.stem("installed")).isEqualTo("instal");
        // "ss", "us" and "is" end a word, they are not a plural.
        assertThat(SearchService.stem("class")).isEqualTo("class");
        assertThat(SearchService.stem("classes")).isEqualTo("class");
        assertThat(SearchService.stem("status")).isEqualTo("status");
        assertThat(SearchService.stem("analysis")).isEqualTo("analysis");
    }

    @Test
    void contextSearchMatchesTheStemOfAKeyword() {
        // FTS5 prefix matching only reaches forward from the start of a token, so "cancellation*"
        // never finds "cancelledAt": the one node about cancelling matched two keywords, the same as
        // three decoys saved before it, and stayed behind them.
        for (int i = 0; i < 3; i++) {
            String id = "com.sales.Quote" + i;
            store.saveNode(new Node(id, NodeType.CLASS, "Quote" + i, id,
                "q" + i + ".java", 1, "public record Quote" + i + "(UUID orderId)", 0));
        }
        store.saveNode(new Node("com.sales.SalesOrderCancelledEvent", NodeType.DOMAIN_EVENT,
            "SalesOrderCancelledEvent", "com.sales.SalesOrderCancelledEvent", "e.java", 1,
            "public record SalesOrderCancelledEvent(UUID orderId, Instant cancelledAt)", 0));

        var results = searchService.contextSearch("modify sales order cancellation");

        assertThat(results.get(0).name()).isEqualTo("SalesOrderCancelledEvent");
    }

    @Test
    void contextSearchRanksTheWordAsTypedAboveItsStem() {
        // Saved first, so FTS lists it first, yet it matches only the stem "cancel".
        store.saveNode(new Node("com.CancelledMarker", NodeType.CLASS, "CancelledMarker",
            "com.CancelledMarker", "m.java", 1, "public class CancelledMarker", 0));
        store.saveNode(new Node("com.CancellationPolicy", NodeType.CLASS, "CancellationPolicy",
            "com.CancellationPolicy", "p.java", 1, "public class CancellationPolicy", 0));

        var results = searchService.contextSearch("cancellation");

        assertThat(results).extracting(Node::name).containsExactly("CancellationPolicy", "CancelledMarker");
    }

    @Test
    void extractKeywordsFiltersShortWords() {
        var keywords = searchService.extractKeywords("do it on a go");
        assertThat(keywords).isEmpty();
    }

    @Test
    void searchFindsHyphenatedShellScriptName() {
        store.saveNode(new Node("clean-restart.sh", NodeType.SHELL_SCRIPT, "clean-restart",
            "clean-restart.sh", "clean-restart.sh", 1, "", 0));

        var results = searchService.search("clean-restart");
        assertThat(results).extracting(Node::name).contains("clean-restart");
    }

    @Test
    void searchWithParenthesisDoesNotThrow() {
        assertThatCode(() -> searchService.search("build(x)")).doesNotThrowAnyException();
    }

    @Test
    void searchPrefixMatchStillWorks() {
        var results = searchService.search("Purchase");
        assertThat(results).extracting(Node::name).contains("PurchaseOrder", "CreatePurchaseOrderCommand");
    }

    @Test
    void searchRanksExactNameMatchFirst() {
        store.saveNode(new Node("com.supplier.Supplier", NodeType.CLASS,
            "Supplier", "com.supplier.Supplier", "s.java", 1, "public class Supplier", 0));
        store.saveNode(new Node("com.supplier.SupplierEntity", NodeType.ENTITY,
            "SupplierEntity", "com.supplier.SupplierEntity", "e.java", 1, "public class SupplierEntity", 0));
        store.saveNode(new Node("com.supplier.SupplierRepository", NodeType.REPOSITORY,
            "SupplierRepository", "com.supplier.SupplierRepository", "r.java", 1, "public interface SupplierRepository", 0));

        var results = searchService.search("supplier");
        assertThat(results.get(0).name()).isEqualTo("Supplier");
    }

    /** Counts how often a traversal asks for a node's edges, in either direction. */
    private static class CountingStore extends GraphStore {
        int edgeLookups;

        CountingStore(Path db) { super(db); }

        @Override public List<Edge> findEdgesFrom(String sourceId) {
            edgeLookups++;
            return super.findEdgesFrom(sourceId);
        }

        @Override public List<Edge> findEdgesTo(String targetId) {
            edgeLookups++;
            return super.findEdgesTo(targetId);
        }
    }

    @Test
    void aQuestionTaskSeedsTheSameNodesAsItsSubjectAlone() {
        var plain = searchService.contextSearch("worker").stream().map(Node::id).toList();
        var asked = searchService.contextSearch("where does worker happen").stream().map(Node::id).toList();
        assertThat(plain).isNotEmpty();
        assertThat(asked).isEqualTo(plain);
        assertThat(searchService.extractKeywords("where does worker happen")).containsExactly("worker");
    }

    @Test
    void aTaskOfOnlyStopWordsYieldsNoResult() {
        assertThat(searchService.contextSearch("where does it happen when")).isEmpty();
        assertThat(searchService.hasKeywordSeed("where does it happen")).isFalse();
    }
}
