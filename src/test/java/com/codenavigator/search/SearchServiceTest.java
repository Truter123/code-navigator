package com.codenavigator.search;

import com.codenavigator.graph.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
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
}
