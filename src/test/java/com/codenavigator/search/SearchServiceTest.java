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
    void contextSearchTracesManySeedsInOneWalk() {
        // Thirty nodes match every keyword and all hang off one hub, so each seed's own chain is
        // the whole 31-node component. Tracing per seed cost 30 x 31 expansions; on a real project
        // "modify sales order cancellation" (hundreds of hits, a 10,615-node component) never
        // returned. One shared walk expands each node once, whatever the number of seeds.
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

            // Nothing lost: every seed and the hub they reach, once each.
            assertThat(results).hasSize(31);
            // Bounded by the graph, not by seeds x graph: one lookup per direction per node.
            assertThat(counting.edgeLookups).isLessThanOrEqualTo(2 * 31);
        }
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
}
