package com.codenavigator.domain;

import com.codenavigator.domain.*;
import com.codenavigator.domain.model.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class DomainSearchServiceTest {

    @TempDir Path tempDir;
    private DomainSqliteStore store;
    private DomainSearchService search;

    @BeforeEach
    void setUp() {
        store = new DomainSqliteStore(tempDir.resolve("domain-navigator.db"));

        store.saveEntity(new DomainEntity("SalesOrder", "Sales", "aggregate",
            "Customer purchase order", List.of(), null));
        store.saveEntity(new DomainEntity("Order", "OrderManagement", "aggregate",
            "Represents a customer order in the system", List.of(), null));

        store.saveTerm(new GlossaryTerm("Fulfillment", "Process of completing an order",
            List.of("Order Fulfillment", "shipping"), "Sales", List.of("SalesOrder"), null));

        store.saveFlow(new BusinessFlow("Place Order", "Customer places an order",
            "OrderManagement", "Customer submits cart", List.of(), "Order created"));

        store.saveRule(new BusinessRule("order-minimum", "Order must have at least one item",
            "OrderManagement", "Order", Severity.ERROR, "items.size() > 0"));

        store.saveContext(new BoundedContext("Payment", "Handles payment processing",
            "finance-team", List.of("Invoice", "Transaction"), List.of()));

        search = new DomainSearchService(store);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void searchFindsMatchingResults() {
        var results = search.search("order");
        assertThat(results).isNotEmpty();
        assertThat(results).anyMatch(r -> r.name().contains("Order"));
    }

    @Test
    void explainReturnsFormattedMarkdown() {
        var answer = search.explain("Tell me about orders");
        assertThat(answer).contains("## Domain context for:");
        assertThat(answer).contains("###");
        assertThat(answer).containsIgnoringCase("order");
    }

    @Test
    void extractKeywordsRemovesStopWords() {
        var keywords = search.extractKeywords("Why can't I ship an unpaid order?");
        assertThat(keywords).contains("ship", "unpaid", "order");
        assertThat(keywords).doesNotContain("why", "can't", "i", "an");
    }

    @Test
    void searchReturnsEmptyForNoMatch() {
        var results = search.search("xyznonexistent");
        assertThat(results).isEmpty();
    }

    @Test
    void explainWithNoKeywordsReturnsDefault() {
        var result = search.explain("is it a the");
        assertThat(result).isEqualTo("No relevant domain knowledge found.");
    }
}
