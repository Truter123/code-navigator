package com.codenavigator.domain;

import com.codenavigator.domain.*;
import com.codenavigator.domain.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.*;

class CodeNavigatorExtractorTest {

    @TempDir
    Path tempDir;

    private Path createTestGraphDb() throws Exception {
        var dbPath = tempDir.resolve("code-navigator.db");
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath())) {
            try (var stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE nodes (
                        id TEXT PRIMARY KEY,
                        type TEXT NOT NULL,
                        name TEXT NOT NULL,
                        qualified_name TEXT,
                        file_path TEXT,
                        line_number INTEGER,
                        code_snippet TEXT,
                        last_modified INTEGER
                    )""");

                stmt.execute("""
                    CREATE TABLE edges (
                        id TEXT PRIMARY KEY,
                        type TEXT,
                        source_id TEXT,
                        target_id TEXT
                    )""");

                stmt.execute("""
                    INSERT INTO nodes VALUES ('1', 'AGGREGATE', 'Order', 'com.example.sales.Order', 'Order.java', 1,
                    '@Getter
                    public class Order extends AggregateRoot<OrderId> {
                        String customerName;
                        OrderStatus status;
                        BigDecimal totalAmount;
                    }', NULL)""");
                stmt.execute("""
                    INSERT INTO nodes VALUES ('2', 'DOMAIN_EVENT', 'OrderCreatedEvent', 'com.example.sales.OrderCreatedEvent', 'OrderCreatedEvent.java', 1,
                    '@Getter
                    public class OrderCreatedEvent extends OrderEvent {
                        private final String customerName;
                        private final BigDecimal amount;
                    }', NULL)""");
                stmt.execute("""
                    INSERT INTO nodes VALUES ('3', 'COMMAND', 'CreateOrderCommand', 'com.example.sales.CreateOrderCommand', 'CreateOrderCommand.java', 1,
                    'public record CreateOrderCommand(String customerName, BigDecimal amount, String currency) implements Command<OrderId> {
                    }', NULL)""");
                stmt.execute("INSERT INTO nodes VALUES ('4', 'REPOSITORY', 'OrderRepository', 'com.example.sales.OrderRepository', 'OrderRepository.java', 1, NULL, NULL)");
                stmt.execute("""
                    INSERT INTO nodes VALUES ('5', 'AGGREGATE', 'Product', 'com.example.catalog.Product', 'Product.java', 1,
                    '/**
                     * Represents a product in the catalog.
                     */
                    @Getter
                    public class Product extends AggregateRoot<ProductId> {
                        String name;
                        BigDecimal price;
                    }', NULL)""");
                // Unrecognized type should be skipped
                stmt.execute("INSERT INTO nodes VALUES ('6', 'UNKNOWN_TYPE', 'SomeThing', 'com.example.SomeThing', 'SomeThing.java', 1, NULL, NULL)");

                // Command handler
                stmt.execute("INSERT INTO nodes VALUES ('7', 'COMMAND_HANDLER', 'CreateOrderCommandHandler', 'com.example.sales.CreateOrderCommandHandler', 'CreateOrderCommandHandler.java', 1, NULL, NULL)");

                // Edges
                stmt.execute("INSERT INTO edges VALUES ('e1', 'EMITS_EVENT', '1', '2')"); // Order emits OrderCreatedEvent
                stmt.execute("INSERT INTO edges VALUES ('e2', 'HANDLES', '7', '3')"); // Handler handles CreateOrderCommand
                stmt.execute("INSERT INTO edges VALUES ('e3', 'LOADS_AGGREGATE', '7', '1')"); // Handler loads Order
            }
        }
        return dbPath;
    }

    private DomainSqliteStore createDomainStore() {
        return new DomainSqliteStore(tempDir.resolve("domain-navigator.db"));
    }

    @Test
    void extractsSavesEntitiesToStore() throws Exception {
        var graphDb = createTestGraphDb();
        try (var store = createDomainStore()) {
            var extractor = new CodeNavigatorExtractor();
            var result = extractor.extract(store, graphDb);

            var entities = store.getAllEntities();
            assertThat(entities).hasSize(6); // 5 recognized + handler

            var order = entities.stream().filter(e -> e.name().equals("Order")).findFirst().orElseThrow();
            assertThat(order.type()).isEqualTo("aggregate");
            assertThat(order.codeMapping()).isEqualTo("com.example.sales.Order");
            assertThat(order.description()).contains("Order");

            var event = entities.stream().filter(e -> e.name().equals("OrderCreatedEvent")).findFirst().orElseThrow();
            assertThat(event.type()).isEqualTo("event");

            var command = entities.stream().filter(e -> e.name().equals("CreateOrderCommand")).findFirst().orElseThrow();
            assertThat(command.type()).isEqualTo("command");

            assertThat(result).contains("6 entities extracted");
        }
    }

    @Test
    void extractsSavesEntityFields() throws Exception {
        var graphDb = createTestGraphDb();
        try (var store = createDomainStore()) {
            var extractor = new CodeNavigatorExtractor();
            extractor.extract(store, graphDb);

            var entities = store.getAllEntities();

            // Aggregate fields parsed from class body
            var order = entities.stream().filter(e -> e.name().equals("Order")).findFirst().orElseThrow();
            assertThat(order.fields()).isNotEmpty();
            var fieldNames = order.fields().stream().map(f -> f.name()).toList();
            assertThat(fieldNames).contains("customerName", "status", "totalAmount");

            // Status field should have transitions marker
            var statusField = order.fields().stream().filter(f -> f.name().equals("status")).findFirst().orElseThrow();
            assertThat(statusField.transitions()).isEqualTo("state field");

            // Command fields parsed from record params
            var command = entities.stream().filter(e -> e.name().equals("CreateOrderCommand")).findFirst().orElseThrow();
            assertThat(command.fields()).isNotEmpty();
            var cmdFieldNames = command.fields().stream().map(f -> f.name()).toList();
            assertThat(cmdFieldNames).contains("customerName", "amount", "currency");
        }
    }

    @Test
    void extractsGroupsIntoContextsByPackage() throws Exception {
        var graphDb = createTestGraphDb();
        try (var store = createDomainStore()) {
            var extractor = new CodeNavigatorExtractor();
            extractor.extract(store, graphDb);

            var contexts = store.getAllContexts();
            assertThat(contexts).hasSize(2); // sales + catalog

            var sales = contexts.stream().filter(c -> c.name().equals("Sales")).findFirst().orElseThrow();
            assertThat(sales.entities()).contains("Order", "OrderCreatedEvent", "CreateOrderCommand", "OrderRepository");
            assertThat(sales.description()).contains("com.example.sales");

            var catalog = contexts.stream().filter(c -> c.name().equals("Catalog")).findFirst().orElseThrow();
            assertThat(catalog.entities()).containsExactly("Product");
        }
    }

    @Test
    void extractsCreatesGlossaryTermsForAggregatesEventsCommands() throws Exception {
        var graphDb = createTestGraphDb();
        try (var store = createDomainStore()) {
            var extractor = new CodeNavigatorExtractor();
            extractor.extract(store, graphDb);

            var terms = store.getAllTerms();
            // Order (aggregate) + OrderCreatedEvent (event) + CreateOrderCommand (command) + Product (aggregate)
            assertThat(terms).hasSize(4);

            var termNames = terms.stream().map(GlossaryTerm::name).toList();
            assertThat(termNames).contains("Order", "Order Created Event", "Create Order Command", "Product");

            // Repository should NOT generate a glossary term
            assertThat(termNames).doesNotContain("Order Repository");

            // Terms should have aliases
            var orderTerm = terms.stream().filter(t -> t.name().equals("Order")).findFirst().orElseThrow();
            assertThat(orderTerm.relatedEntities()).containsExactly("Order");
            assertThat(orderTerm.aliases()).contains("Order");

            // Event term should have alias without "Event" suffix
            var eventTerm = terms.stream().filter(t -> t.name().equals("Order Created Event")).findFirst().orElseThrow();
            assertThat(eventTerm.aliases()).contains("Order Created");

            // Product should have javadoc-based definition
            var productTerm = terms.stream().filter(t -> t.name().equals("Product")).findFirst().orElseThrow();
            assertThat(productTerm.definition()).contains("product in the catalog");
        }
    }

    @Test
    void extractsBusinessFlows() throws Exception {
        var graphDb = createTestGraphDb();
        try (var store = createDomainStore()) {
            var extractor = new CodeNavigatorExtractor();
            extractor.extract(store, graphDb);

            var flows = store.getAllFlows();
            assertThat(flows).isNotEmpty();

            var createOrderFlow = flows.stream()
                .filter(f -> f.name().contains("Create Order"))
                .findFirst().orElseThrow();
            assertThat(createOrderFlow.steps()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(createOrderFlow.trigger()).contains("Create Order Command");
        }
    }

    @Test
    void extractsBusinessRules() throws Exception {
        var graphDb = createTestGraphDb();
        try (var store = createDomainStore()) {
            var extractor = new CodeNavigatorExtractor();
            extractor.extract(store, graphDb);

            var rules = store.getAllRules();
            assertThat(rules).isNotEmpty();

            // Should have lifecycle rule for Order (has events)
            var lifecycleRule = rules.stream()
                .filter(r -> r.name().contains("Lifecycle"))
                .findFirst().orElseThrow();
            assertThat(lifecycleRule.entity()).isEqualTo("Order");
            assertThat(lifecycleRule.invariant()).contains("Order Created Event");

            // Should have modification rule (has command handlers)
            var modRule = rules.stream()
                .filter(r -> r.name().contains("Modification"))
                .findFirst().orElseThrow();
            assertThat(modRule.entity()).isEqualTo("Order");
            assertThat(modRule.invariant()).contains("CreateOrderCommandHandler");
        }
    }

    @Test
    void extractsContextCommunications() throws Exception {
        // Create a graph with cross-context edges
        var dbPath = tempDir.resolve("graph2.db");
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath())) {
            try (var stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE nodes (
                        id TEXT PRIMARY KEY, type TEXT, name TEXT, qualified_name TEXT,
                        file_path TEXT, line_number INTEGER, code_snippet TEXT, last_modified INTEGER
                    )""");
                stmt.execute("""
                    CREATE TABLE edges (
                        id TEXT PRIMARY KEY, type TEXT, source_id TEXT, target_id TEXT
                    )""");

                stmt.execute("INSERT INTO nodes VALUES ('1', 'SERVICE', 'OrderService', 'com.example.sales.OrderService', 'f', 1, NULL, NULL)");
                stmt.execute("INSERT INTO nodes VALUES ('2', 'AGGREGATE', 'Product', 'com.example.catalog.Product', 'f', 1, NULL, NULL)");
                stmt.execute("INSERT INTO edges VALUES ('e1', 'USES_SERVICE', '1', '2')");
            }
        }

        try (var store = new DomainSqliteStore(tempDir.resolve("domain2.db"))) {
            new CodeNavigatorExtractor().extract(store, dbPath);

            var contexts = store.getAllContexts();
            var sales = contexts.stream().filter(c -> c.name().equals("Sales")).findFirst().orElseThrow();
            assertThat(sales.communicatesWith()).isNotEmpty();
            assertThat(sales.communicatesWith().get(0).context()).isEqualTo("Catalog");
        }
    }

    @Test
    void extractsSetsCodeGraphModifiedConfig() throws Exception {
        var graphDb = createTestGraphDb();
        try (var store = createDomainStore()) {
            var extractor = new CodeNavigatorExtractor();
            extractor.extract(store, graphDb);

            var modified = store.getConfigLong("code_graph_modified");
            assertThat(modified).isGreaterThan(0);
        }
    }

    @Test
    void extractsClearsExistingDataFirst() throws Exception {
        var graphDb = createTestGraphDb();
        try (var store = createDomainStore()) {
            // Pre-populate with data
            store.saveEntity(new DomainEntity("OldEntity", null, "entity", "should be cleared", null, null));

            var extractor = new CodeNavigatorExtractor();
            extractor.extract(store, graphDb);

            var entities = store.getAllEntities();
            assertThat(entities.stream().map(DomainEntity::name).toList()).doesNotContain("OldEntity");
            assertThat(entities).hasSize(6);
        }
    }

    @Test
    void extractsReturnsErrorForMissingDb() {
        try (var store = createDomainStore()) {
            var extractor = new CodeNavigatorExtractor();
            var result = extractor.extract(store, tempDir.resolve("nonexistent.db"));
            assertThat(result).contains("Error");
        }
    }

    @Test
    void extractsReturnsSummaryWithTypeCounts() throws Exception {
        var graphDb = createTestGraphDb();
        try (var store = createDomainStore()) {
            var extractor = new CodeNavigatorExtractor();
            var result = extractor.extract(store, graphDb);

            assertThat(result).contains("SQLite domain store");
            assertThat(result).contains("aggregate");
            assertThat(result).contains("event");
            assertThat(result).contains("command");
            assertThat(result).contains("2 bounded contexts");
            assertThat(result).contains("4 terms");
        }
    }

    @Test
    void extractsJavadocDescriptions() throws Exception {
        var graphDb = createTestGraphDb();
        try (var store = createDomainStore()) {
            new CodeNavigatorExtractor().extract(store, graphDb);

            var entities = store.getAllEntities();
            var product = entities.stream().filter(e -> e.name().equals("Product")).findFirst().orElseThrow();
            assertThat(product.description()).contains("product in the catalog");
        }
    }

    @Test
    void extractsRelationshipDescriptions() throws Exception {
        var graphDb = createTestGraphDb();
        try (var store = createDomainStore()) {
            new CodeNavigatorExtractor().extract(store, graphDb);

            var entities = store.getAllEntities();
            var order = entities.stream().filter(e -> e.name().equals("Order")).findFirst().orElseThrow();
            assertThat(order.description()).contains("emits Order Created Event");
        }
    }
}
