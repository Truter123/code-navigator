package com.codenavigator.domain;

import com.codenavigator.domain.model.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class DomainToolHandlersTest {

    @TempDir Path tempDir;
    private DomainToolHandlers handlers;
    private DomainSqliteStore store;

    @BeforeEach
    void setUp() {
        store = new DomainSqliteStore(tempDir.resolve("domain-navigator.db"));

        // Contexts
        store.saveContext(new BoundedContext(
            "Order Management",
            "Handles order lifecycle from creation to fulfillment",
            "order-team",
            List.of("Order", "OrderLine", "OrderStatus"),
            List.of(
                new ContextCommunication("Payment", "async", "OrderCreatedEvent -> PaymentService"),
                new ContextCommunication("Inventory", "sync", "REST API /inventory/reserve")
            )
        ));
        store.saveContext(new BoundedContext(
            "Payment",
            "Processes payments and refunds",
            "payment-team",
            List.of("Payment", "Refund"),
            List.of(
                new ContextCommunication("Order Management", "async", "PaymentConfirmedEvent -> OrderService")
            )
        ));

        // Glossary terms
        store.saveTerm(new GlossaryTerm(
            "Fulfillment",
            "The process of picking, packing, and shipping an order",
            List.of("shipping", "dispatch"),
            "Order Management",
            List.of("Order", "Shipment", "Warehouse"),
            null
        ));
        store.saveTerm(new GlossaryTerm(
            "Back-order",
            "Order accepted but cannot be fulfilled due to insufficient inventory",
            null,
            "Order Management",
            null,
            "Auto-created when stock < ordered quantity"
        ));
        store.saveTerm(new GlossaryTerm(
            "Chargeback",
            "Reversal of a payment initiated by the customer's bank",
            null,
            "Payment",
            List.of("Payment", "Refund"),
            null
        ));

        // Flows
        store.saveFlow(new BusinessFlow(
            "Place Order",
            "Customer submits an order through the storefront",
            "Order Management",
            "Customer clicks Place Order",
            List.of(
                new FlowStep("Validate order items and quantities", "OrderService", "Return validation errors to customer"),
                new FlowStep("Reserve inventory for each line item", "InventoryService", "Create back-order, notify customer"),
                new FlowStep("Process payment", "PaymentService", "Release inventory, cancel order"),
                new FlowStep("Confirm order and emit OrderCreatedEvent", "OrderAggregate", null)
            ),
            "Order is confirmed, inventory reserved, payment captured"
        ));
        store.saveFlow(new BusinessFlow(
            "Process Refund",
            "Customer requests a refund for a delivered order",
            "Payment",
            "Customer submits refund request",
            List.of(
                new FlowStep("Validate refund eligibility (within 30 days)", "RefundService", "Reject refund request"),
                new FlowStep("Reverse payment via payment gateway", "PaymentGateway", "Queue for manual review"),
                new FlowStep("Update order status to REFUNDED", "OrderService", null)
            ),
            "Payment reversed, order marked as refunded"
        ));

        // Rules
        store.saveRule(new BusinessRule(
            "order-minimum",
            "Order total must exceed $10",
            "Order Management",
            "Order",
            Severity.ERROR,
            "order.total >= 10.00"
        ));
        store.saveRule(new BusinessRule(
            "shipment-requires-payment",
            "An order cannot be shipped if payment is still pending",
            "Order Management",
            "Order",
            Severity.ERROR,
            "order.paymentStatus == CAPTURED before order.status -> SHIPPED"
        ));
        store.saveRule(new BusinessRule(
            "refund-window",
            "Refunds can only be processed within 30 days of delivery",
            "Payment",
            "Refund",
            Severity.WARNING,
            "refund.requestDate <= order.deliveryDate + 30 days"
        ));

        // Entities
        store.saveEntity(new DomainEntity(
            "Order",
            "Order Management",
            "aggregate",
            "Represents a customer's purchase request",
            List.of(
                new EntityField("status", "OrderStatus", "Current lifecycle state", "CREATED -> PAID -> SHIPPED -> DELIVERED | CANCELLED"),
                new EntityField("lines", "List<OrderLine>", "Items in the order", null),
                new EntityField("total", "BigDecimal", "Computed total of all line items", null)
            ),
            "com.example.order.Order"
        ));
        store.saveEntity(new DomainEntity(
            "OrderLine",
            "Order Management",
            "entity",
            "A single item in an order with quantity and price",
            List.of(
                new EntityField("product", "String", "Product identifier", null),
                new EntityField("quantity", "int", "Number of units", null),
                new EntityField("price", "BigDecimal", "Unit price", null)
            ),
            null
        ));
        store.saveEntity(new DomainEntity(
            "Payment",
            "Payment",
            "aggregate",
            "Tracks a payment transaction against an order",
            List.of(
                new EntityField("status", "PaymentStatus", "Current payment state", "PENDING -> CAPTURED -> REFUNDED | FAILED")
            ),
            "com.example.payment.Payment"
        ));
        store.saveEntity(new DomainEntity(
            "OrderCreatedEvent",
            "Order Management",
            "event",
            "Emitted when a new order is confirmed",
            null,
            null
        ));
        store.saveEntity(new DomainEntity(
            "CreateOrderCommand",
            "Order Management",
            "command",
            "Initiates order creation with line items",
            null,
            null
        ));

        // No code graph db for most tests (staleness check skipped)
        handlers = new DomainToolHandlers(store, null);
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    @Test
    void handleDmContext_returnsAllContexts() {
        var result = handlers.handleDmContext(Map.of());
        assertThat(result).contains("## Bounded Contexts");
        assertThat(result).contains("Order Management");
        assertThat(result).contains("Payment");
        assertThat(result).contains("async");
        assertThat(result).contains("**Owner:** order-team");
        assertThat(result).contains("**Entities:** Order, OrderLine, OrderStatus");
        assertThat(result).contains("**Communicates with:**");
        assertThat(result).contains("Payment (async) via OrderCreatedEvent -> PaymentService");
    }

    @Test
    void handleDmGlossary_returnsAllTerms() {
        var result = handlers.handleDmGlossary(Map.of());
        assertThat(result).contains("Fulfillment");
        assertThat(result).contains("Back-order");
        assertThat(result).contains("Chargeback");
    }

    @Test
    void handleDmGlossary_filtersByTermName() {
        var result = handlers.handleDmGlossary(Map.of("term", "Fulfillment"));
        assertThat(result).contains("Fulfillment");
        assertThat(result).contains("picking, packing, and shipping");
        assertThat(result).contains("**Aliases:** shipping, dispatch");
        assertThat(result).doesNotContain("Back-order");
        assertThat(result).doesNotContain("Chargeback");
    }

    @Test
    void handleDmGlossary_filtersByContext() {
        var result = handlers.handleDmGlossary(Map.of("context", "Payment"));
        assertThat(result).contains("Chargeback");
        assertThat(result).doesNotContain("Fulfillment");
        assertThat(result).doesNotContain("Back-order");
    }

    @Test
    void handleDmFlow_returnsFlowsWithSteps() {
        var result = handlers.handleDmFlow(Map.of("name", "Place Order"));
        assertThat(result).contains("Place Order");
        assertThat(result).contains("Validate order items and quantities");
        assertThat(result).contains("Process payment");
        assertThat(result).contains("**Trigger:** Customer clicks Place Order");
        assertThat(result).contains("1. **OrderService**: Validate order items and quantities");
        assertThat(result).contains("   - On failure: Return validation errors to customer");
        assertThat(result).contains("**Outcome:** Order is confirmed, inventory reserved, payment captured");
    }

    @Test
    void handleDmFlow_filtersByContext() {
        var result = handlers.handleDmFlow(Map.of("context", "Payment"));
        assertThat(result).contains("Process Refund");
        assertThat(result).doesNotContain("Place Order");
    }

    @Test
    void handleDmRules_filtersByEntity() {
        var result = handlers.handleDmRules(Map.of("entity", "Order"));
        assertThat(result).contains("order-minimum");
        assertThat(result).contains("shipment-requires-payment");
        assertThat(result).doesNotContain("refund-window");
    }

    @Test
    void handleDmRules_filtersBySeverity() {
        var result = handlers.handleDmRules(Map.of("severity", "ERROR"));
        assertThat(result).contains("order-minimum");
        assertThat(result).contains("shipment-requires-payment");
        assertThat(result).doesNotContain("refund-window");
    }

    @Test
    void handleDmRules_filtersBySeverityWarning() {
        var result = handlers.handleDmRules(Map.of("severity", "WARNING"));
        assertThat(result).contains("refund-window");
        assertThat(result).doesNotContain("order-minimum");
    }

    @Test
    void handleDmEntity_filtersByName() {
        var result = handlers.handleDmEntity(Map.of("name", "Order"));
        assertThat(result).contains("Order");
        assertThat(result).contains("aggregate");
        assertThat(result).contains("CREATED -> PAID -> SHIPPED");
        assertThat(result).contains("**Code:** `com.example.order.Order`");
    }

    @Test
    void handleDmEntity_filtersByType() {
        var result = handlers.handleDmEntity(Map.of("type", "event"));
        assertThat(result).contains("OrderCreatedEvent");
        assertThat(result).doesNotContain("CreateOrderCommand");
    }

    @Test
    void handleDmEntity_filtersByContext() {
        var result = handlers.handleDmEntity(Map.of("context", "Payment"));
        assertThat(result).contains("Payment");
        assertThat(result).doesNotContain("OrderLine");
        assertThat(result).doesNotContain("OrderCreatedEvent");
    }

    @Test
    void handleDmExplain_searchesAcrossDomainKnowledge() {
        var result = handlers.handleDmExplain(Map.of("question", "Why can't I ship an unpaid order?"));
        assertThat(result).contains("shipment-requires-payment");
    }

    @Test
    void handleDmExplain_returnsNotFoundForUnknownQuestion() {
        var result = handlers.handleDmExplain(Map.of("question", "xyznonexistent"));
        assertThat(result).contains("No relevant domain knowledge found");
    }
}
