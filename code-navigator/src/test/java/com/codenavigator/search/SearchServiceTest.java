package com.codenavigator.search;

import com.codenavigator.embedding.EmbeddingProvider;
import com.codenavigator.embedding.NoopEmbeddingProvider;
import com.codenavigator.graph.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

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
    void searchWithNoop_behaviorIdenticalToFtsOnly() {
        var noopService = new SearchService(store, new GraphTraversal(store), new NoopEmbeddingProvider());
        var defaultService = new SearchService(store, new GraphTraversal(store));

        var withNoop = noopService.search("PurchaseOrder");
        var withDefault = defaultService.search("PurchaseOrder");

        assertThat(withNoop).extracting(Node::id)
            .containsExactlyInAnyOrderElementsOf(withDefault.stream().map(Node::id).toList());
    }

    @Test
    void searchWithFakeProvider_promotesSemanticMatchFtsMisses() {
        // A node whose text contains no "PurchaseOrder" token (FTS won't surface it),
        // but whose embedding is aligned with the (faked) query vector.
        store.saveNode(new Node("com.WorkerController", NodeType.CONTROLLER,
            "WorkerController", "com.WorkerController", "Worker.java", 1, "handles work", 0));
        store.upsertEmbedding("com.WorkerController", new float[]{1.0f, 0.0f});

        EmbeddingProvider fakeProvider = text -> new float[]{1.0f, 0.0f};
        var hybrid = new SearchService(store, new GraphTraversal(store), fakeProvider);

        var results = hybrid.search("PurchaseOrder");
        assertThat(results).extracting(Node::name).contains("WorkerController");
    }
}
