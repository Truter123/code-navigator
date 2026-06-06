# Domain-Aware Modification Guard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `cg_guard <symbol>` MCP tool that, before any edit, prints a single markdown guard report covering blast radius, DDD node-type grouping (with callouts for AGGREGATE / DOMAIN_EVENT / COMMAND / COMMAND_HANDLER / PROJECTION_HANDLER), touched bounded contexts, and matching domain business rules.

**Architecture:** `handleCgGuard` in `CodeNavigatorMcpServer` delegates to a new `GuardReportBuilder` class (pure logic, no I/O) that combines `GraphTraversal.impact()` + `DomainSqliteStore` queries. Two new query helpers are added to `DomainSqliteStore` — `findRulesMentioning(Collection<String> terms)` and `findContextsContaining(Collection<String> entityNames)` — keeping the store self-contained; `GuardReportBuilder` calls them via the existing `DomainToolHandlers.store()` accessor. When the graph tier is not `DDD`, the report degrades gracefully to blast-radius-only output.

**Tech Stack:** Java 21, Gradle shadowJar, SQLite JDBC, JUnit 5 + AssertJ (`assertThat`), `@TempDir`; no new dependencies.

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| **Modify** | `src/main/java/com/codenavigator/domain/DomainSqliteStore.java` | Add `findRulesMentioning(Collection<String>)` and `findContextsContaining(Collection<String>)` query helpers |
| **Create** | `src/main/java/com/codenavigator/mcp/GuardReportBuilder.java` | Pure logic: combine impact list + DDD-type grouping + context lookup + rules lookup into a markdown string |
| **Modify** | `src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java` | Register `cg_guard` tool in `start()` and add `handleCgGuard(Map<String,Object>)` handler method |
| **Create** | `src/test/java/com/codenavigator/domain/DomainSqliteStoreGuardQueryTest.java` | Unit tests for the two new `DomainSqliteStore` query helpers |
| **Create** | `src/test/java/com/codenavigator/mcp/GuardReportBuilderTest.java` | Unit tests for `GuardReportBuilder` with in-memory graph + domain fixtures |
| **Modify** | `src/test/java/com/codenavigator/mcp/CodeNavigatorMcpServerTest.java` | Add `cgGuard_*` handler tests using the existing `buildTestGraph()` fixture |

---

### Task 1: DomainSqliteStore query helpers + unit tests

**Files:**
- `src/main/java/com/codenavigator/domain/DomainSqliteStore.java` — add two public methods after the `getAllRules()` block (around line 544)
- `src/test/java/com/codenavigator/domain/DomainSqliteStoreGuardQueryTest.java` — new test class

#### Steps

- [ ] **Write failing test** — create `DomainSqliteStoreGuardQueryTest.java`:

```java
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
```

- [ ] **Run — expect COMPILE FAIL** (methods don't exist yet):

```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && \
./gradlew test --tests "com.codenavigator.domain.DomainSqliteStoreGuardQueryTest" 2>&1 | tail -20
```

Expected: compilation error — `cannot find symbol: findRulesMentioning` / `findContextsContaining`.

- [ ] **Implement** — add both methods to `DomainSqliteStore.java` after the `getAllRules()` block (after line 544):

```java
/**
 * Return all rules whose entity field case-insensitively matches any of the given terms.
 * Returns empty list if terms is empty.
 */
public List<BusinessRule> findRulesMentioning(java.util.Collection<String> terms) {
    if (terms == null || terms.isEmpty()) return List.of();
    try {
        List<BusinessRule> result = new ArrayList<>();
        String placeholders = terms.stream().map(t -> "?").collect(java.util.stream.Collectors.joining(", "));
        String sql = "SELECT name, description, context, entity, severity, invariant FROM rules " +
                     "WHERE LOWER(entity) IN (" + placeholders + ")";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            for (String term : terms) {
                ps.setString(i++, term.toLowerCase());
            }
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(new BusinessRule(
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getString("context"),
                    rs.getString("entity"),
                    Severity.valueOf(rs.getString("severity")),
                    rs.getString("invariant")
                ));
            }
        }
        return result;
    } catch (SQLException e) {
        throw new RuntimeException("Failed to query rules by terms: " + terms, e);
    }
}

/**
 * Return all bounded contexts that list any of the given entity names in their context_entities.
 * Returns empty list if entityNames is empty.
 */
public List<BoundedContext> findContextsContaining(java.util.Collection<String> entityNames) {
    if (entityNames == null || entityNames.isEmpty()) return List.of();
    try {
        String placeholders = entityNames.stream().map(e -> "?").collect(java.util.stream.Collectors.joining(", "));
        String sql = "SELECT DISTINCT c.id FROM contexts c " +
                     "JOIN context_entities ce ON ce.context_id = c.id " +
                     "WHERE LOWER(ce.entity_name) IN (" + placeholders + ")";
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            for (String name : entityNames) {
                ps.setString(i++, name.toLowerCase());
            }
            ResultSet rs = ps.executeQuery();
            while (rs.next()) ids.add(rs.getLong(1));
        }
        if (ids.isEmpty()) return List.of();

        // Re-use getAllContexts logic but filter to matched ids
        List<BoundedContext> result = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT id, name, description, owner FROM contexts ORDER BY id")) {
            while (rs.next()) {
                long id = rs.getLong("id");
                if (!ids.contains(id)) continue;
                result.add(new BoundedContext(
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getString("owner"),
                    loadContextEntities(id),
                    loadContextCommunications(id)
                ));
            }
        }
        return result;
    } catch (SQLException e) {
        throw new RuntimeException("Failed to query contexts by entity names: " + entityNames, e);
    }
}
```

- [ ] **Run — expect GREEN**:

```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && \
./gradlew test --tests "com.codenavigator.domain.DomainSqliteStoreGuardQueryTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` — 8 tests passing.

- [ ] **Commit**:

```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && \
git add src/main/java/com/codenavigator/domain/DomainSqliteStore.java \
        src/test/java/com/codenavigator/domain/DomainSqliteStoreGuardQueryTest.java && \
git commit -m "feat(domain): add findRulesMentioning and findContextsContaining query helpers"
```

---

### Task 2: GuardReportBuilder — pure logic + unit tests

**Files:**
- `src/main/java/com/codenavigator/mcp/GuardReportBuilder.java` — new class
- `src/test/java/com/codenavigator/mcp/GuardReportBuilderTest.java` — new test class

#### Steps

- [ ] **Write failing test** — create `GuardReportBuilderTest.java`:

```java
package com.codenavigator.mcp;

import com.codenavigator.domain.DomainSqliteStore;
import com.codenavigator.domain.model.*;
import com.codenavigator.graph.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GuardReportBuilderTest {

    @TempDir Path tempDir;
    private GraphStore graphStore;
    private DomainSqliteStore domainStore;
    private GraphTraversal traversal;

    @BeforeEach
    void setUp() {
        graphStore = new GraphStore(tempDir.resolve("graph.db"));
        domainStore = new DomainSqliteStore(tempDir.resolve("domain.db"));
        traversal = new GraphTraversal(graphStore);
        buildCqrsGraph();
        buildDomainFixtures();
    }

    @AfterEach
    void tearDown() {
        graphStore.close();
        domainStore.close();
    }

    private void buildCqrsGraph() {
        graphStore.saveNode(new Node("com.OrderController", NodeType.CONTROLLER, "OrderController",
            "com.OrderController", "Ctrl.java", 1, "@RestController class OrderController", 0));
        graphStore.saveNode(new Node("com.CreateOrderCommand", NodeType.COMMAND, "CreateOrderCommand",
            "com.CreateOrderCommand", "Cmd.java", 1, "record CreateOrderCommand", 0));
        graphStore.saveNode(new Node("com.CreateOrderCommandHandler", NodeType.COMMAND_HANDLER,
            "CreateOrderCommandHandler", "com.CreateOrderCommandHandler", "H.java", 1, "class Handler", 0));
        graphStore.saveNode(new Node("com.Order", NodeType.AGGREGATE, "Order",
            "com.Order", "Order.java", 1, "class Order extends AggregateRoot", 0));
        graphStore.saveNode(new Node("com.OrderCreatedEvent", NodeType.DOMAIN_EVENT, "OrderCreatedEvent",
            "com.OrderCreatedEvent", "Event.java", 1, "class OrderCreatedEvent", 0));
        graphStore.saveNode(new Node("com.OrderViewProjectionHandler", NodeType.PROJECTION_HANDLER,
            "OrderViewProjectionHandler", "com.OrderViewProjectionHandler", "Proj.java", 1, "class Proj", 0));
        graphStore.saveNode(new Node("com.OrderView", NodeType.VIEW, "OrderView",
            "com.OrderView", "View.java", 1, "@Entity class OrderView", 0));

        graphStore.saveEdge(new Edge("e1", EdgeType.DISPATCHES_COMMAND, "com.OrderController", "com.CreateOrderCommand"));
        graphStore.saveEdge(new Edge("e2", EdgeType.HANDLES, "com.CreateOrderCommandHandler", "com.CreateOrderCommand"));
        graphStore.saveEdge(new Edge("e3", EdgeType.LOADS_AGGREGATE, "com.CreateOrderCommandHandler", "com.Order"));
        graphStore.saveEdge(new Edge("e4", EdgeType.EMITS_EVENT, "com.Order", "com.OrderCreatedEvent"));
        graphStore.saveEdge(new Edge("e5", EdgeType.PROJECTS_EVENT, "com.OrderViewProjectionHandler", "com.OrderCreatedEvent"));
        graphStore.saveEdge(new Edge("e6", EdgeType.UPDATES_VIEW, "com.OrderViewProjectionHandler", "com.OrderView"));

        graphStore.setConfig("tier", "DDD");
    }

    private void buildDomainFixtures() {
        domainStore.saveContext(new BoundedContext(
            "Order Management", "Handles order lifecycle", "order-team",
            List.of("Order", "OrderCreatedEvent", "CreateOrderCommand"),
            List.of()
        ));
        domainStore.saveRule(new BusinessRule(
            "order-minimum", "Order total must exceed $10",
            "Order Management", "Order", Severity.ERROR,
            "order.total >= 10.00"
        ));
        domainStore.saveRule(new BusinessRule(
            "shipment-requires-payment",
            "Order cannot be shipped if payment is pending",
            "Order Management", "Order", Severity.ERROR,
            "order.paymentStatus == CAPTURED"
        ));
    }

    @Test
    void build_containsBlastRadiusSection() {
        var builder = new GuardReportBuilder(graphStore, traversal, domainStore);
        var report = builder.build("com.Order", 2);
        assertThat(report).contains("## Guard Report");
        assertThat(report).contains("Blast Radius");
        assertThat(report).contains("OrderCreatedEvent");
        assertThat(report).contains("CreateOrderCommandHandler");
    }

    @Test
    void build_groupsDddNodeTypes() {
        var builder = new GuardReportBuilder(graphStore, traversal, domainStore);
        var report = builder.build("com.Order", 2);
        assertThat(report).contains("### AGGREGATE");
        assertThat(report).contains("### DOMAIN_EVENT");
        assertThat(report).contains("### PROJECTION_HANDLER");
        assertThat(report).contains("### COMMAND_HANDLER");
    }

    @Test
    void build_callsOutDddHighlightTypes() {
        var builder = new GuardReportBuilder(graphStore, traversal, domainStore);
        var report = builder.build("com.Order", 2);
        // DDD-significant types must be called out with a warning marker
        assertThat(report).contains("AGGREGATE");
        assertThat(report).contains("DOMAIN_EVENT");
        assertThat(report).contains("COMMAND");
        assertThat(report).contains("PROJECTION_HANDLER");
    }

    @Test
    void build_includesTouchedBoundedContexts() {
        var builder = new GuardReportBuilder(graphStore, traversal, domainStore);
        var report = builder.build("com.Order", 2);
        assertThat(report).contains("Bounded Contexts Touched");
        assertThat(report).contains("Order Management");
    }

    @Test
    void build_includesMatchingDomainRules() {
        var builder = new GuardReportBuilder(graphStore, traversal, domainStore);
        var report = builder.build("com.Order", 2);
        assertThat(report).contains("Matching Domain Rules");
        assertThat(report).contains("order-minimum");
        assertThat(report).contains("shipment-requires-payment");
    }

    @Test
    void build_nonDddTierShowsBlastRadiusOnly() {
        graphStore.setConfig("tier", "GENERIC");
        var builder = new GuardReportBuilder(graphStore, traversal, domainStore);
        var report = builder.build("com.Order", 2);
        assertThat(report).contains("Blast Radius");
        assertThat(report).doesNotContain("Bounded Contexts Touched");
        assertThat(report).doesNotContain("Matching Domain Rules");
    }
}
```

- [ ] **Run — expect COMPILE FAIL** (`GuardReportBuilder` does not exist yet):

```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && \
./gradlew test --tests "com.codenavigator.mcp.GuardReportBuilderTest" 2>&1 | tail -20
```

Expected: compilation error — `cannot find symbol: GuardReportBuilder`.

- [ ] **Implement** — create `GuardReportBuilder.java`:

```java
package com.codenavigator.mcp;

import com.codenavigator.domain.DomainSqliteStore;
import com.codenavigator.domain.model.BoundedContext;
import com.codenavigator.domain.model.BusinessRule;
import com.codenavigator.graph.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds a markdown guard report for a symbol before an edit.
 * Combines blast-radius graph traversal with DDD node-type grouping,
 * bounded-context lookup, and matching business rules.
 */
public class GuardReportBuilder {

    // Node types that carry DDD significance — called out with a warning marker.
    private static final Set<NodeType> DDD_HIGHLIGHT_TYPES = Set.of(
        NodeType.AGGREGATE,
        NodeType.DOMAIN_EVENT,
        NodeType.COMMAND,
        NodeType.COMMAND_HANDLER,
        NodeType.PROJECTION_HANDLER
    );

    private final GraphStore graphStore;
    private final GraphTraversal traversal;
    private final DomainSqliteStore domainStore;

    public GuardReportBuilder(GraphStore graphStore, GraphTraversal traversal, DomainSqliteStore domainStore) {
        this.graphStore = graphStore;
        this.traversal = traversal;
        this.domainStore = domainStore;
    }

    /**
     * Build the guard report for the given node id and traversal depth.
     *
     * @param nodeId resolved node id (not a display name)
     * @param depth  blast-radius traversal depth
     * @return markdown string
     */
    public String build(String nodeId, int depth) {
        var startNode = graphStore.findNodeById(nodeId);
        String symbolName = startNode.map(Node::name).orElse(nodeId);

        List<Node> impacted = traversal.impact(nodeId, depth);

        var sb = new StringBuilder();
        sb.append("## Guard Report: `").append(symbolName).append("`\n\n");

        // (a) Blast radius
        sb.append("### Blast Radius (depth ").append(depth).append(")\n\n");
        sb.append(impacted.size()).append(" node(s) affected:\n\n");
        for (Node n : impacted) {
            String marker = DDD_HIGHLIGHT_TYPES.contains(n.type()) ? " ⚠" : "";
            sb.append("- **").append(n.type()).append("**").append(marker)
              .append(" `").append(n.name()).append("`")
              .append(" (").append(n.filePath()).append(":").append(n.lineNumber()).append(")\n");
        }
        sb.append("\n");

        // Check whether this is a DDD-tier project
        String tier = graphStore.getConfig("tier");
        boolean isDdd = "DDD".equalsIgnoreCase(tier);

        if (!isDdd) {
            sb.append("> _Non-DDD tier (`").append(tier != null ? tier : "unknown")
              .append("`) — domain context and rules sections skipped._\n");
            return sb.toString();
        }

        // (b) DDD node-type grouping
        sb.append("### DDD Node Types Touched\n\n");
        Map<NodeType, List<Node>> byType = impacted.stream()
            .collect(Collectors.groupingBy(Node::type, LinkedHashMap::new, Collectors.toList()));

        // DDD highlight types first, then the rest
        List<NodeType> orderedTypes = new ArrayList<>(byType.keySet());
        orderedTypes.sort(Comparator.comparingInt(
            t -> DDD_HIGHLIGHT_TYPES.contains(t) ? 0 : 1));

        for (NodeType type : orderedTypes) {
            String callout = DDD_HIGHLIGHT_TYPES.contains(type) ? " ⚠ DDD-significant" : "";
            sb.append("#### ").append(type).append(callout).append("\n");
            for (Node n : byType.get(type)) {
                sb.append("- `").append(n.name()).append("` (")
                  .append(n.filePath()).append(":").append(n.lineNumber()).append(")\n");
            }
        }
        sb.append("\n");

        // (c) Bounded contexts touched
        Set<String> nodeNames = impacted.stream().map(Node::name).collect(Collectors.toSet());
        startNode.ifPresent(n -> nodeNames.add(n.name()));

        List<BoundedContext> contexts = domainStore.findContextsContaining(nodeNames);
        sb.append("### Bounded Contexts Touched\n\n");
        if (contexts.isEmpty()) {
            sb.append("_No bounded context registered for these symbols._\n\n");
        } else {
            for (BoundedContext ctx : contexts) {
                sb.append("- **").append(ctx.name()).append("**");
                if (ctx.owner() != null) sb.append(" (owner: ").append(ctx.owner()).append(")");
                sb.append("\n");
            }
            sb.append("\n");
        }

        // (d) Matching domain rules
        List<BusinessRule> rules = domainStore.findRulesMentioning(nodeNames);
        sb.append("### Matching Domain Rules\n\n");
        if (rules.isEmpty()) {
            sb.append("_No domain rules registered for these symbols._\n\n");
        } else {
            for (BusinessRule rule : rules) {
                sb.append("- **[").append(rule.severity()).append("]** `").append(rule.name()).append("`");
                if (rule.entity() != null) sb.append(" — entity: `").append(rule.entity()).append("`");
                sb.append("\n");
                sb.append("  > ").append(rule.description()).append("\n");
                sb.append("  > Invariant: `").append(rule.invariant()).append("`\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
```

- [ ] **Run — expect GREEN**:

```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && \
./gradlew test --tests "com.codenavigator.mcp.GuardReportBuilderTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` — 7 tests passing.

- [ ] **Commit**:

```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && \
git add src/main/java/com/codenavigator/mcp/GuardReportBuilder.java \
        src/test/java/com/codenavigator/mcp/GuardReportBuilderTest.java && \
git commit -m "feat(mcp): add GuardReportBuilder combining blast-radius, DDD types, contexts and rules"
```

---

### Task 3: cg_guard MCP tool registration + handler test

**Files:**
- `src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java` — register tool in `start()`, add `handleCgGuard` handler method
- `src/test/java/com/codenavigator/mcp/CodeNavigatorMcpServerTest.java` — add `cgGuard_*` tests

#### Steps

- [ ] **Write failing tests** — add the following test methods to `CodeNavigatorMcpServerTest` (inside the class, after the existing tests):

```java
    @Test
    void cgGuard_containsBlastRadiusAndAggregate() {
        // Order is an AGGREGATE; depth 2 from it reaches Command, Handler, Event, Projection, View
        var result = mcpServer.handleCgGuard(Map.of("symbol", "Order", "depth", 2));
        assertThat(result).contains("## Guard Report");
        assertThat(result).contains("Blast Radius");
        assertThat(result).contains("OrderCreatedEvent");
        assertThat(result).contains("AGGREGATE");
        assertThat(result).contains("DOMAIN_EVENT");
    }

    @Test
    void cgGuard_containsBoundedContextSection() {
        // Domain store in the test setUp has no data, so sections appear but say "No ... registered"
        var result = mcpServer.handleCgGuard(Map.of("symbol", "Order"));
        assertThat(result).contains("Bounded Contexts Touched");
        assertThat(result).contains("Matching Domain Rules");
    }

    @Test
    void cgGuard_symbolNotFound() {
        var result = mcpServer.handleCgGuard(Map.of("symbol", "NonexistentClass"));
        assertThat(result).contains("not found");
    }

    @Test
    void cgGuard_defaultDepthIsTwo() {
        // No "depth" arg — should still produce a report (not crash)
        var result = mcpServer.handleCgGuard(Map.of("symbol", "Order"));
        assertThat(result).contains("## Guard Report");
        assertThat(result).contains("depth 2");
    }
```

- [ ] **Run — expect COMPILE FAIL** (`handleCgGuard` does not exist yet):

```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && \
./gradlew test --tests "com.codenavigator.mcp.CodeNavigatorMcpServerTest.cgGuard*" 2>&1 | tail -20
```

Expected: compilation error — `cannot find symbol: handleCgGuard`.

- [ ] **Implement** — in `CodeNavigatorMcpServer.java`:

  1. Add `handleCgGuard` method after `handleCgImpact` (around line 286):

```java
    String handleCgGuard(Map<String, Object> args) {
        String symbol = (String) args.get("symbol");
        int depth = getIntArg(args, "depth", 2);
        String nodeId = resolveSymbol(symbol);
        if (nodeId == null) return "Symbol '" + symbol + "' not found.";

        var builder = new GuardReportBuilder(store, traversal, domainHandlers.store());
        return builder.build(nodeId, depth);
    }
```

  2. Register the tool in `start()` — insert the following `.toolCall(...)` block after the `cg_impact` registration (after line 63):

```java
            .toolCall(
                Tool.builder()
                    .name("cg_guard")
                    .description("Domain-aware modification guard — before an edit, reports: blast radius, DDD node types touched (calls out AGGREGATE/COMMAND/DOMAIN_EVENT/COMMAND_HANDLER/PROJECTION_HANDLER), bounded contexts touched, and matching domain business rules.")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of(
                            "symbol", propString("Symbol name or qualified name to guard"),
                            "depth", propInt("Blast-radius traversal depth (default 2)"))),
                        List.of("symbol")))
                    .build(),
                (exchange, request) -> textResult(handleCgGuard(request.arguments()))
            )
```

- [ ] **Run — expect GREEN**:

```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && \
./gradlew test --tests "com.codenavigator.mcp.CodeNavigatorMcpServerTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` — all existing tests plus 4 new `cgGuard_*` tests passing.

- [ ] **Commit**:

```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && \
git add src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java \
        src/test/java/com/codenavigator/mcp/CodeNavigatorMcpServerTest.java && \
git commit -m "feat(mcp): register cg_guard tool and handleCgGuard handler"
```

---

### Task 4: Edge-case tests

**Files:**
- `src/test/java/com/codenavigator/mcp/GuardReportBuilderTest.java` — extend with edge-case tests

#### Steps

- [ ] **Write failing tests** — add the following to `GuardReportBuilderTest` (inside the existing class):

```java
    @Test
    void build_symbolWithNoImpactedNodes() {
        // A leaf node with no edges produces a valid but minimal report
        graphStore.saveNode(new Node("com.Leaf", NodeType.SERVICE, "Leaf",
            "com.Leaf", "Leaf.java", 5, "class Leaf", 0));
        graphStore.setConfig("tier", "DDD");

        var builder = new GuardReportBuilder(graphStore, traversal, domainStore);
        var report = builder.build("com.Leaf", 2);
        assertThat(report).contains("## Guard Report");
        assertThat(report).contains("0 node(s) affected");
        assertThat(report).contains("Bounded Contexts Touched");
        assertThat(report).contains("Matching Domain Rules");
    }

    @Test
    void build_genericTierShowsOnlyBlastRadius() {
        graphStore.setConfig("tier", "GENERIC");
        var builder = new GuardReportBuilder(graphStore, traversal, domainStore);
        var report = builder.build("com.Order", 2);
        assertThat(report).contains("Blast Radius");
        assertThat(report).doesNotContain("Bounded Contexts Touched");
        assertThat(report).doesNotContain("Matching Domain Rules");
        assertThat(report).contains("GENERIC");
    }

    @Test
    void build_nullTierDegradesSafely() {
        // No tier set at all (getConfig returns null)
        graphStore.saveNode(new Node("com.NoTierNode", NodeType.CLASS, "NoTierNode",
            "com.NoTierNode", "X.java", 1, "class X", 0));
        // create a fresh graph store without tier config
        var freshStore = new GraphStore(tempDir.resolve("notier.db"));
        freshStore.saveNode(new Node("com.NoTierNode", NodeType.CLASS, "NoTierNode",
            "com.NoTierNode", "X.java", 1, "class X", 0));
        var freshTraversal = new GraphTraversal(freshStore);
        var builder = new GuardReportBuilder(freshStore, freshTraversal, domainStore);
        var report = builder.build("com.NoTierNode", 2);
        assertThat(report).contains("Blast Radius");
        assertThat(report).doesNotContain("Bounded Contexts Touched");
        freshStore.close();
    }

    @Test
    void build_depthZeroShowsNoImpact() {
        var builder = new GuardReportBuilder(graphStore, traversal, domainStore);
        var report = builder.build("com.Order", 0);
        assertThat(report).contains("0 node(s) affected");
    }
```

- [ ] **Run — expect GREEN** (these tests use existing code; they should pass immediately):

```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && \
./gradlew test --tests "com.codenavigator.mcp.GuardReportBuilderTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` — 11 tests passing (7 existing + 4 new).

- [ ] **Full test suite — verify no regressions**:

```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && \
./gradlew test 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Commit**:

```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && \
git add src/test/java/com/codenavigator/mcp/GuardReportBuilderTest.java && \
git commit -m "test(mcp): add edge-case tests for cg_guard (non-DDD tier, null tier, zero depth, leaf node)"
```

---

## Self-Review

| Spec item | Covered in | Key assertions |
|-----------|-----------|----------------|
| **(a) Blast radius** — reuse `GraphTraversal.impact(nodeId, depth)` | Task 2 (`GuardReportBuilderTest.build_containsBlastRadiusSection`) + Task 3 (`cgGuard_containsBlastRadiusAndAggregate`) | report contains impacted node names; depth label appears |
| **(b) DDD node-type grouping** — call out AGGREGATE / DOMAIN_EVENT / COMMAND / COMMAND_HANDLER / PROJECTION_HANDLER | Task 2 (`build_groupsDddNodeTypes`, `build_callsOutDddHighlightTypes`) | `### AGGREGATE`, `### DOMAIN_EVENT`, `### PROJECTION_HANDLER`, `### COMMAND_HANDLER` sections present; ⚠ marker on DDD-significant types |
| **(c) Bounded contexts** — via `DomainSqliteStore.findContextsContaining` | Task 1 (store helper tests) + Task 2 (`build_includesTouchedBoundedContexts`) + Task 3 (`cgGuard_containsBoundedContextSection`) | "Bounded Contexts Touched" section present; "Order Management" listed |
| **(d) Matching domain rules** — via `DomainSqliteStore.findRulesMentioning` | Task 1 (store helper tests) + Task 2 (`build_includesMatchingDomainRules`) | "Matching Domain Rules" section present; rule names listed with severity |
| **Non-DDD/GENERIC degradation** | Task 2 (`build_nonDddTierShowsBlastRadiusOnly`) + Task 4 (`build_genericTierShowsOnlyBlastRadius`, `build_nullTierDegradesSafely`) | Domain sections absent when tier != DDD |
| **Symbol not found** | Task 3 (`cgGuard_symbolNotFound`) | Returns "not found" message |
| **Zero-impact node** | Task 4 (`build_symbolWithNoImpactedNodes`) | "0 node(s) affected"; domain sections still present |
| **Default depth = 2** | Task 3 (`cgGuard_defaultDepthIsTwo`) | No depth arg → report contains "depth 2" |
