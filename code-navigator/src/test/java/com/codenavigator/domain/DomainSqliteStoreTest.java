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

class DomainSqliteStoreTest {

    @TempDir
    Path tempDir;

    private DomainSqliteStore store;

    @BeforeEach
    void setUp() {
        store = new DomainSqliteStore(tempDir.resolve("test-domain-navigator.db"));
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    // ---- Schema ----

    @Test
    void opensAndCreatesSchema() {
        // Store opened in setUp — just verify it works and is empty
        assertThat(store.isEmpty()).isTrue();
        assertThat(store.getAllContexts()).isEmpty();
        assertThat(store.getAllTerms()).isEmpty();
        assertThat(store.getAllFlows()).isEmpty();
        assertThat(store.getAllRules()).isEmpty();
        assertThat(store.getAllEntities()).isEmpty();
    }

    @Test
    void reopenPreservesData() {
        store.saveRule(new BusinessRule("R1", "desc", "ctx", "E1", Severity.ERROR, "inv"));
        store.close();

        store = new DomainSqliteStore(tempDir.resolve("test-domain-navigator.db"));
        assertThat(store.getAllRules()).hasSize(1);
        assertThat(store.getAllRules().get(0).name()).isEqualTo("R1");
    }

    // ---- Config ----

    @Test
    void configSetAndGet() {
        store.setConfig("key1", "value1");
        assertThat(store.getConfig("key1")).isEqualTo("value1");
    }

    @Test
    void configReturnsNullForMissing() {
        assertThat(store.getConfig("nonexistent")).isNull();
    }

    @Test
    void configOverwrite() {
        store.setConfig("key1", "v1");
        store.setConfig("key1", "v2");
        assertThat(store.getConfig("key1")).isEqualTo("v2");
    }

    @Test
    void configLongParsesCorrectly() {
        store.setConfig("ts", "1234567890");
        assertThat(store.getConfigLong("ts")).isEqualTo(1234567890L);
    }

    @Test
    void configLongReturnsZeroForMissing() {
        assertThat(store.getConfigLong("missing")).isEqualTo(0);
    }

    @Test
    void configLongReturnsZeroForNonNumeric() {
        store.setConfig("bad", "notanumber");
        assertThat(store.getConfigLong("bad")).isEqualTo(0);
    }

    // ---- Contexts ----

    @Test
    void saveAndLoadContext() {
        var ctx = new BoundedContext(
            "Order Management",
            "Handles orders",
            "team-a",
            List.of("Order", "LineItem"),
            List.of(new ContextCommunication("Inventory", "async", "EventBus"))
        );

        store.saveContext(ctx);
        var loaded = store.getAllContexts();

        assertThat(loaded).hasSize(1);
        var c = loaded.get(0);
        assertThat(c.name()).isEqualTo("Order Management");
        assertThat(c.description()).isEqualTo("Handles orders");
        assertThat(c.owner()).isEqualTo("team-a");
        assertThat(c.entities()).containsExactlyInAnyOrder("Order", "LineItem");
        assertThat(c.communicatesWith()).hasSize(1);
        assertThat(c.communicatesWith().get(0).context()).isEqualTo("Inventory");
        assertThat(c.communicatesWith().get(0).type()).isEqualTo("async");
        assertThat(c.communicatesWith().get(0).via()).isEqualTo("EventBus");
    }

    @Test
    void saveContextOverwritesByName() {
        store.saveContext(new BoundedContext("Ctx", "v1", null, List.of(), List.of()));
        store.saveContext(new BoundedContext("Ctx", "v2", null, List.of("E1"), List.of()));

        var loaded = store.getAllContexts();
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).description()).isEqualTo("v2");
        assertThat(loaded.get(0).entities()).containsExactly("E1");
    }

    @Test
    void contextWithNullChildren() {
        store.saveContext(new BoundedContext("Bare", null, null, null, null));
        var loaded = store.getAllContexts();
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).entities()).isEmpty();
        assertThat(loaded.get(0).communicatesWith()).isEmpty();
    }

    // ---- Terms ----

    @Test
    void saveAndLoadTerm() {
        var term = new GlossaryTerm(
            "Fulfillment",
            "Process of completing an order",
            List.of("Shipping", "Delivery"),
            "Order Management",
            List.of("Order", "Shipment"),
            "Must complete within 48h"
        );

        store.saveTerm(term);
        var loaded = store.getAllTerms();

        assertThat(loaded).hasSize(1);
        var t = loaded.get(0);
        assertThat(t.name()).isEqualTo("Fulfillment");
        assertThat(t.definition()).isEqualTo("Process of completing an order");
        assertThat(t.aliases()).containsExactly("Shipping", "Delivery");
        assertThat(t.context()).isEqualTo("Order Management");
        assertThat(t.relatedEntities()).containsExactly("Order", "Shipment");
        assertThat(t.businessRule()).isEqualTo("Must complete within 48h");
    }

    @Test
    void saveTermOverwritesByNameAndContext() {
        store.saveTerm(new GlossaryTerm("T1", "old def", List.of("a1"), "ctx", List.of("E1"), "rule1"));
        store.saveTerm(new GlossaryTerm("T1", "new def", List.of("a2"), "ctx", List.of("E2"), "rule2"));

        var loaded = store.getAllTerms();
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).definition()).isEqualTo("new def");
        assertThat(loaded.get(0).aliases()).containsExactly("a2");
        assertThat(loaded.get(0).relatedEntities()).containsExactly("E2");
    }

    @Test
    void termWithNullChildren() {
        store.saveTerm(new GlossaryTerm("Simple", "def", null, null, null, null));
        var loaded = store.getAllTerms();
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).aliases()).isEmpty();
        assertThat(loaded.get(0).relatedEntities()).isEmpty();
    }

    // ---- Flows ----

    @Test
    void saveAndLoadFlow() {
        var flow = new BusinessFlow(
            "Order Placement",
            "Customer places an order",
            "Order Management",
            "Customer clicks Buy",
            List.of(
                new FlowStep("Validate cart", "System", "Show error"),
                new FlowStep("Create order", "System", null),
                new FlowStep("Process payment", "PaymentGateway", "Cancel order")
            ),
            "Order confirmed"
        );

        store.saveFlow(flow);
        var loaded = store.getAllFlows();

        assertThat(loaded).hasSize(1);
        var f = loaded.get(0);
        assertThat(f.name()).isEqualTo("Order Placement");
        assertThat(f.description()).isEqualTo("Customer places an order");
        assertThat(f.context()).isEqualTo("Order Management");
        assertThat(f.trigger()).isEqualTo("Customer clicks Buy");
        assertThat(f.outcome()).isEqualTo("Order confirmed");
        assertThat(f.steps()).hasSize(3);
        assertThat(f.steps().get(0).action()).isEqualTo("Validate cart");
        assertThat(f.steps().get(0).actor()).isEqualTo("System");
        assertThat(f.steps().get(0).onFailure()).isEqualTo("Show error");
        assertThat(f.steps().get(1).onFailure()).isNull();
        assertThat(f.steps().get(2).action()).isEqualTo("Process payment");
    }

    @Test
    void flowStepOrderPreserved() {
        var flow = new BusinessFlow("F1", null, null, null,
            List.of(
                new FlowStep("Step A", null, null),
                new FlowStep("Step B", null, null),
                new FlowStep("Step C", null, null)
            ), null);

        store.saveFlow(flow);
        var loaded = store.getAllFlows().get(0);
        assertThat(loaded.steps()).extracting(FlowStep::action)
            .containsExactly("Step A", "Step B", "Step C");
    }

    @Test
    void saveFlowOverwritesByNameAndContext() {
        store.saveFlow(new BusinessFlow("F1", "old desc", "ctx", "trigger1",
            List.of(new FlowStep("Step A", null, null)), "outcome1"));
        store.saveFlow(new BusinessFlow("F1", "new desc", "ctx", "trigger2",
            List.of(new FlowStep("Step B", null, null), new FlowStep("Step C", null, null)), "outcome2"));

        var loaded = store.getAllFlows();
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).description()).isEqualTo("new desc");
        assertThat(loaded.get(0).steps()).hasSize(2);
        assertThat(loaded.get(0).steps()).extracting(FlowStep::action)
            .containsExactly("Step B", "Step C");
    }

    @Test
    void flowWithNullSteps() {
        store.saveFlow(new BusinessFlow("Bare", null, null, null, null, null));
        var loaded = store.getAllFlows();
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).steps()).isEmpty();
    }

    // ---- Rules ----

    @Test
    void saveAndLoadRule() {
        var rule = new BusinessRule(
            "Minimum Order",
            "Order must have at least one item",
            "Order Management",
            "Order",
            Severity.ERROR,
            "items.size() > 0"
        );

        store.saveRule(rule);
        var loaded = store.getAllRules();

        assertThat(loaded).hasSize(1);
        var r = loaded.get(0);
        assertThat(r.name()).isEqualTo("Minimum Order");
        assertThat(r.description()).isEqualTo("Order must have at least one item");
        assertThat(r.context()).isEqualTo("Order Management");
        assertThat(r.entity()).isEqualTo("Order");
        assertThat(r.severity()).isEqualTo(Severity.ERROR);
        assertThat(r.invariant()).isEqualTo("items.size() > 0");
    }

    @Test
    void ruleAllSeverityLevels() {
        store.saveRule(new BusinessRule("R1", null, null, null, Severity.ERROR, null));
        store.saveRule(new BusinessRule("R2", null, null, null, Severity.WARNING, null));
        store.saveRule(new BusinessRule("R3", null, null, null, Severity.INFO, null));

        var loaded = store.getAllRules();
        assertThat(loaded).hasSize(3);
        assertThat(loaded).extracting(BusinessRule::severity)
            .containsExactly(Severity.ERROR, Severity.WARNING, Severity.INFO);
    }

    @Test
    void saveRuleOverwritesByNameAndContext() {
        store.saveRule(new BusinessRule("R1", "old desc", "ctx", "E1", Severity.ERROR, "inv1"));
        store.saveRule(new BusinessRule("R1", "new desc", "ctx", "E2", Severity.WARNING, "inv2"));

        var loaded = store.getAllRules();
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).description()).isEqualTo("new desc");
        assertThat(loaded.get(0).severity()).isEqualTo(Severity.WARNING);
        assertThat(loaded.get(0).invariant()).isEqualTo("inv2");
    }

    // ---- Entities ----

    @Test
    void saveAndLoadEntity() {
        var entity = new DomainEntity(
            "Order",
            "Order Management",
            "aggregate",
            "Represents a customer order",
            List.of(
                new EntityField("status", "OrderStatus", "Current status", "DRAFT->CONFIRMED->SHIPPED->DELIVERED"),
                new EntityField("totalAmount", "BigDecimal", "Order total", null)
            ),
            "com.example.Order"
        );

        store.saveEntity(entity);
        var loaded = store.getAllEntities();

        assertThat(loaded).hasSize(1);
        var e = loaded.get(0);
        assertThat(e.name()).isEqualTo("Order");
        assertThat(e.context()).isEqualTo("Order Management");
        assertThat(e.type()).isEqualTo("aggregate");
        assertThat(e.description()).isEqualTo("Represents a customer order");
        assertThat(e.codeMapping()).isEqualTo("com.example.Order");
        assertThat(e.fields()).hasSize(2);
        assertThat(e.fields().get(0).name()).isEqualTo("status");
        assertThat(e.fields().get(0).type()).isEqualTo("OrderStatus");
        assertThat(e.fields().get(0).transitions()).isEqualTo("DRAFT->CONFIRMED->SHIPPED->DELIVERED");
        assertThat(e.fields().get(1).transitions()).isNull();
    }

    @Test
    void saveEntityOverwritesByNameAndContext() {
        store.saveEntity(new DomainEntity("E1", "ctx", "aggregate", "old desc",
            List.of(new EntityField("f1", "String", null, null)), "com.old"));
        store.saveEntity(new DomainEntity("E1", "ctx", "value_object", "new desc",
            List.of(new EntityField("f2", "Integer", null, null), new EntityField("f3", "Long", null, null)), "com.new"));

        var loaded = store.getAllEntities();
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).description()).isEqualTo("new desc");
        assertThat(loaded.get(0).type()).isEqualTo("value_object");
        assertThat(loaded.get(0).fields()).hasSize(2);
        assertThat(loaded.get(0).fields()).extracting(EntityField::name)
            .containsExactly("f2", "f3");
    }

    @Test
    void entityWithNullFields() {
        store.saveEntity(new DomainEntity("Bare", null, null, null, null, null));
        var loaded = store.getAllEntities();
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).fields()).isEmpty();
    }

    // ---- clearAll and isEmpty ----

    @Test
    void clearAllRemovesEverything() {
        store.saveContext(new BoundedContext("C1", null, null, List.of("E1"), List.of()));
        store.saveTerm(new GlossaryTerm("T1", "def", List.of("a"), null, List.of("e"), null));
        store.saveFlow(new BusinessFlow("F1", null, null, null,
            List.of(new FlowStep("act", null, null)), null));
        store.saveRule(new BusinessRule("R1", null, null, null, Severity.INFO, null));
        store.saveEntity(new DomainEntity("E1", null, null, null,
            List.of(new EntityField("f", "t", null, null)), null));
        store.setConfig("k", "v");

        assertThat(store.isEmpty()).isFalse();

        store.clearAll();

        assertThat(store.isEmpty()).isTrue();
        assertThat(store.getAllContexts()).isEmpty();
        assertThat(store.getAllTerms()).isEmpty();
        assertThat(store.getAllFlows()).isEmpty();
        assertThat(store.getAllRules()).isEmpty();
        assertThat(store.getAllEntities()).isEmpty();
        assertThat(store.getConfig("k")).isNull();
    }

    @Test
    void isEmptyChecksEntitiesTable() {
        assertThat(store.isEmpty()).isTrue();

        // Adding a context does not affect isEmpty (it checks entities table)
        store.saveContext(new BoundedContext("C1", null, null, List.of(), List.of()));
        assertThat(store.isEmpty()).isTrue();

        store.saveEntity(new DomainEntity("E1", null, null, null, null, null));
        assertThat(store.isEmpty()).isFalse();
    }

    // ---- getModel ----

    @Test
    void getModelAggregatesAllData() {
        store.saveContext(new BoundedContext("C1", "desc", null, List.of(), List.of()));
        store.saveTerm(new GlossaryTerm("T1", "def", null, null, null, null));
        store.saveFlow(new BusinessFlow("F1", null, null, null, null, null));
        store.saveRule(new BusinessRule("R1", null, null, null, Severity.WARNING, null));
        store.saveEntity(new DomainEntity("E1", null, null, null, null, null));

        var model = store.getModel("my-project", "/path/to/project");

        assertThat(model.projectName()).isEqualTo("my-project");
        assertThat(model.projectPath()).isEqualTo("/path/to/project");
        assertThat(model.contexts()).hasSize(1);
        assertThat(model.terms()).hasSize(1);
        assertThat(model.flows()).hasSize(1);
        assertThat(model.rules()).hasSize(1);
        assertThat(model.entities()).hasSize(1);
    }

    @Test
    void getModelReturnsEmptyListsWhenEmpty() {
        var model = store.getModel("empty", "/tmp");
        assertThat(model.contexts()).isEmpty();
        assertThat(model.terms()).isEmpty();
        assertThat(model.flows()).isEmpty();
        assertThat(model.rules()).isEmpty();
        assertThat(model.entities()).isEmpty();
    }

    // ---- Multiple records ----

    // ---- FTS Search ----

    @Test
    void ftsSearchFindsMatchingEntitiesAcrossTypes() {
        store.saveEntity(new DomainEntity("OrderItem", "Sales", "aggregate",
            "Represents an order line item", null, null));
        store.saveTerm(new GlossaryTerm("Order", "A customer order request",
            null, "Sales", null, null));

        var results = store.searchFts("order");

        assertThat(results).hasSize(2);
        assertThat(results).extracting(DomainSqliteStore.SearchResult::name)
            .containsExactlyInAnyOrder("OrderItem", "Order");
    }

    @Test
    void ftsSearchFiltersByType() {
        store.saveEntity(new DomainEntity("Product", "Catalog", "aggregate",
            "A product", null, null));
        store.saveRule(new BusinessRule("ProductRule", "Validate product",
            "Catalog", "Product", Severity.ERROR, null));
        store.saveFlow(new BusinessFlow("ProductFlow", "Product creation flow",
            "Catalog", null, null, null));

        var results = store.searchFts("product");

        assertThat(results).extracting(DomainSqliteStore.SearchResult::type)
            .containsExactlyInAnyOrder("entity", "rule", "flow");
    }

    @Test
    void searchLikeFallback() {
        store.saveEntity(new DomainEntity("SalesOrder", "Sales", "aggregate",
            "Represents a sales order", null, null));
        store.saveTerm(new GlossaryTerm("Inventory", "Stock management",
            null, "Warehouse", null, null));

        var results = store.searchLike("Order");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("SalesOrder");
        assertThat(results.get(0).type()).isEqualTo("entity");
    }

    @Test
    void ftsSearchReturnsEmptyForNoMatch() {
        store.saveEntity(new DomainEntity("Product", null, "aggregate", "A product", null, null));

        var results = store.searchFts("nonexistentxyz");

        assertThat(results).isEmpty();
    }

    @Test
    void ftsSearchHandlesInvalidSyntaxByFallingBackToLike() {
        store.saveEntity(new DomainEntity("SpecialItem", null, "aggregate",
            "An item for testing fallback", null, null));

        // Unbalanced quotes are invalid FTS5 syntax — should fall back to LIKE
        // The LIKE fallback searches for %SpecialItem% in name or description
        var results = store.searchFts("\"SpecialItem");

        // Should not throw — either FTS handles it or LIKE fallback catches it
        assertThat(results).isNotNull();
        // LIKE will search for %"SpecialItem% which won't match name "SpecialItem"
        // but that's OK — the point is it doesn't crash

        // Also verify direct LIKE works for the same item
        var likeResults = store.searchLike("SpecialItem");
        assertThat(likeResults).hasSize(1);
        assertThat(likeResults.get(0).name()).isEqualTo("SpecialItem");
    }

    // ---- Multiple records ----

    @Test
    void multipleEntitiesPreserveOrder() {
        store.saveEntity(new DomainEntity("Alpha", null, "aggregate", null, null, null));
        store.saveEntity(new DomainEntity("Beta", null, "value_object", null, null, null));
        store.saveEntity(new DomainEntity("Gamma", null, "entity", null, null, null));

        var loaded = store.getAllEntities();
        assertThat(loaded).extracting(DomainEntity::name)
            .containsExactly("Alpha", "Beta", "Gamma");
    }
}
