package com.codenavigator.domain;

import com.codenavigator.domain.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DomainSqliteStoreGuardQueryTest {

    @TempDir Path tempDir;
    private DomainSqliteStore store;

    @BeforeEach
    void setUp() {
        store = new DomainSqliteStore(tempDir.resolve("guard-query.db"));

        store.saveContext(new BoundedContext(
            "Order Management", "Handles order lifecycle", "order-team",
            List.of("Order", "OrderCreatedEvent", "CreateOrderCommand"),
            List.of()
        ));
        store.saveContext(new BoundedContext(
            "Payment", "Processes payments", "payment-team",
            List.of("Payment", "Refund"),
            List.of()
        ));

        store.saveRule(new BusinessRule(
            "order-minimum", "Order total must exceed $10",
            "Order Management", "Order", Severity.ERROR,
            "order.total >= 10.00"
        ));
        store.saveRule(new BusinessRule(
            "shipment-requires-payment",
            "An order cannot be shipped if payment is pending",
            "Order Management", "Order", Severity.ERROR,
            "order.paymentStatus == CAPTURED before order.status -> SHIPPED"
        ));
        store.saveRule(new BusinessRule(
            "refund-window", "Refunds only within 30 days",
            "Payment", "Refund", Severity.WARNING,
            "refund.requestDate <= order.deliveryDate + 30 days"
        ));
    }

    @AfterEach
    void tearDown() { store.close(); }

    // --- findRulesMentioning ---

    @Test
    void findRulesMentioning_matchesRulesByEntityField() {
        var rules = store.findRulesMentioning(List.of("Order"));
        assertThat(rules).extracting(BusinessRule::name)
            .containsExactlyInAnyOrder("order-minimum", "shipment-requires-payment");
    }

    @Test
    void findRulesMentioning_matchesMultipleTerms() {
        var rules = store.findRulesMentioning(List.of("Order", "Refund"));
        assertThat(rules).extracting(BusinessRule::name)
            .containsExactlyInAnyOrder("order-minimum", "shipment-requires-payment", "refund-window");
    }

    @Test
    void findRulesMentioning_returnsEmptyForNoMatch() {
        var rules = store.findRulesMentioning(List.of("Widget", "Gadget"));
        assertThat(rules).isEmpty();
    }

    @Test
    void findRulesMentioning_emptyTermListReturnsEmpty() {
        var rules = store.findRulesMentioning(List.of());
        assertThat(rules).isEmpty();
    }

    // --- findContextsContaining ---

    @Test
    void findContextsContaining_matchesContextByEntity() {
        var contexts = store.findContextsContaining(List.of("Order"));
        assertThat(contexts).extracting(BoundedContext::name)
            .containsExactly("Order Management");
    }

    @Test
    void findContextsContaining_matchesMultipleContexts() {
        var contexts = store.findContextsContaining(List.of("Order", "Refund"));
        assertThat(contexts).extracting(BoundedContext::name)
            .containsExactlyInAnyOrder("Order Management", "Payment");
    }

    @Test
    void findContextsContaining_returnsEmptyForNoMatch() {
        var contexts = store.findContextsContaining(List.of("Widget"));
        assertThat(contexts).isEmpty();
    }

    @Test
    void findContextsContaining_emptyListReturnsEmpty() {
        var contexts = store.findContextsContaining(List.of());
        assertThat(contexts).isEmpty();
    }
}
