# Code-Navigator Improvements: Compact Index + Analysis Tools

## Problem

AI assistants waste tokens at conversation start exploring codebases via repeated MCP tool calls (`cg_map`, `cg_overview`, etc.). Additionally, code-navigator has a rich graph database but lacks analysis capabilities (dead code, hotspots, coupling) and export formats that would make the data more actionable.

Inspired by [ai-codex](https://github.com/skibidiskib/ai-codex) (static markdown index generator) and [cymbal](https://github.com/1broseidon/cymbal) (tree-sitter based code indexer), but leveraging code-navigator's existing domain-aware graph data.

## Design Principles

- All features are pure queries on existing SQLite data — zero new dependencies, zero schema changes
- No duplication with domain-navigator: structural data from code-navigator DB, semantic data from domain-navigator DB
- Follow existing patterns: picocli CLI commands, MCP tool registrations in `CodeNavigatorMcpServer`

---

## Feature 1: Compact Index Generation (`briefing`)

### CLI Command

```bash
code-navigator briefing <path> [--output .ai-briefing]
```

### MCP Tool

```
cg_briefing(projectPath?, output?)
```

### New Classes

- `com.codenavigator.briefing.BriefingGenerator` — reads both DBs, generates markdown files
- `com.codenavigator.cli.BriefingCommand` — picocli subcommand

### Data Sources

BriefingGenerator reads two databases:
1. `navigators/code/code-navigator.db` (always present)
2. `navigators/domain/domain-navigator.db` (optional — if missing, only structural files are generated)

### Output Files

#### `overview.md` (source: code DB)

System-level summary. One glance gives you the project shape.

```markdown
# Project Briefing (generated 2026-04-02)
Tier: DDD | 142 nodes | 89 edges | 34 files

## Modules
- com.example.orders: 3 aggregates, 8 commands, 12 events, 4 projections
- com.example.payments: 1 aggregate, 3 commands, 5 events, 2 projections
- com.example.notifications: 2 services, 1 event_listener
```

**Query:** `getAllNodes()` grouped by package prefix (2 segments after base), count by `NodeType`.

#### `endpoints.md` (source: code DB)

Controller HTTP map with dispatched commands/queries.

```markdown
## OrderController (com.example.orders.api)
POST /api/orders         -> CreateOrderCommand
GET  /api/orders/{id}    -> GetOrderQuery
PUT  /api/orders/{id}    -> UpdateOrderCommand
DELETE /api/orders/{id}  -> CancelOrderCommand

## PaymentController (com.example.payments.api)
POST /api/payments       -> ProcessPaymentCommand
GET  /api/payments/{id}  -> GetPaymentQuery
```

**Query:** `findNodesByType(CONTROLLER)` -> for each controller, parse `code_snippet` for `@RequestMapping`/`@GetMapping`/`@PostMapping`/etc. annotations to extract HTTP method + path. Follow `findEdgesFrom(controllerId)` with type `DISPATCHES_COMMAND` or `DISPATCHES_QUERY` to get dispatched targets.

**HTTP annotation parsing:** Regex on code_snippet:
- `@(Get|Post|Put|Delete|Patch)Mapping\("([^"]+)"\)` -> method + path
- `@RequestMapping\(.*value\s*=\s*"([^"]+)"` -> base path
- Combine class-level `@RequestMapping` base with method-level paths

#### `projections.md` (source: code DB)

Read model map showing what events each projection subscribes to and what views it updates.

```markdown
## OrderListProjection
  listens: OrderCreatedEvent, OrderUpdatedEvent, OrderCancelledEvent
  updates: OrderListView

## PaymentProjection
  listens: PaymentProcessedEvent, PaymentRefundedEvent
  updates: PaymentView
```

**Query:** `findNodesByType(PROJECTION_HANDLER)` -> for each, `findEdgesTo(id)` with type `PROJECTS_EVENT` to get subscribed events, `findEdgesFrom(id)` with type `UPDATES_VIEW` to get target views.

#### `domain.md` (source: domain DB, optional)

Compact dump of bounded contexts, key entities, and glossary.

```markdown
## Bounded Contexts

### Order Management
Entities: Order (aggregate), OrderItem (entity), OrderStatus (value-object)
Events: OrderCreated, OrderUpdated, OrderCancelled
Commands: CreateOrder, UpdateOrder, CancelOrder
Communicates: -> Payment (async, EventBus), -> Notification (async, EventBus)

### Payment
Entities: Payment (aggregate), PaymentMethod (value-object)
Events: PaymentProcessed, PaymentRefunded
Commands: ProcessPayment, RefundPayment

## Glossary
- **Order**: A customer purchase request containing line items and shipping info
- **Aggregate**: Transaction boundary ensuring consistency
- **Saga**: Long-running process coordinating multiple aggregates
```

**Query:** Read domain-navigator.db tables: `contexts` + `context_entities` + `context_communications` for context section. `entities` + `entity_fields` for entity details. `terms` + `term_aliases` for glossary.

#### `flows.md` (source: domain DB, optional)

Business process flows.

```markdown
## Create Order
Trigger: Customer submits order form
1. [OrderController] Receives HTTP request, dispatches CreateOrderCommand
2. [CreateOrderHandler] Validates order data, loads OrderAggregate
3. [OrderAggregate] Applies business rules, emits OrderCreatedEvent
4. [OrderListProjection] Updates read model
Outcome: Order persisted, customer notified
Failure: ValidationException -> 400 Bad Request

## Process Payment
Trigger: OrderCreatedEvent received
1. [PaymentEventListener] Receives event, dispatches ProcessPaymentCommand
2. [ProcessPaymentHandler] Loads PaymentAggregate
3. [PaymentAggregate] Processes payment, emits PaymentProcessedEvent
Outcome: Payment recorded
```

**Query:** Read domain-navigator.db `flows` + `flow_steps` tables.

#### `rules.md` (source: domain DB, optional)

Business invariants and constraints.

```markdown
## ERROR
- **Order minimum amount**: Order total must be > 0 (OrderAggregate)
- **Payment idempotency**: Cannot process same payment twice (PaymentAggregate)

## WARNING
- **Large order review**: Orders over $10,000 require manual review (OrderAggregate)
```

**Query:** Read domain-navigator.db `rules` table, grouped by severity.

### Generation Logic

```java
public class BriefingGenerator {
    private final GraphStore codeStore;
    private final DomainSqliteStore domainStore; // nullable

    public void generate(Path outputDir) {
        Files.createDirectories(outputDir);
        writeFile(outputDir, "overview.md", generateOverview());
        writeFile(outputDir, "endpoints.md", generateEndpoints());
        writeFile(outputDir, "projections.md", generateProjections());
        if (domainStore != null) {
            writeFile(outputDir, "domain.md", generateDomain());
            writeFile(outputDir, "flows.md", generateFlows());
            writeFile(outputDir, "rules.md", generateRules());
        }
    }
}
```

### Domain DB Access

BriefingGenerator needs to read domain-navigator's SQLite DB. Two approaches:

**Chosen approach:** Direct SQLite read with a lightweight `DomainDbReader` class in code-navigator that opens `navigators/domain/domain-navigator.db` read-only and executes the needed SELECT queries. No dependency on domain-navigator's code — just raw SQL reads against a known schema. This keeps the projects decoupled.

---

## Feature 2: Dead Code Detection (`cg_dead`)

### MCP Tool

```
cg_dead(type?, projectPath?)
```

- `type` — optional NodeType filter (e.g., "COMMAND", "SERVICE")

### Query

```sql
SELECT n.* FROM nodes n
LEFT JOIN edges e ON e.target_id = n.id
WHERE e.id IS NULL
AND n.type NOT IN ('CONFIGURATION', 'ENTITY', 'ENUM', 'RECORD', 'FE_MODEL')
ORDER BY n.type, n.name
```

Nodes with zero incoming edges = nothing references them. Excluded types:
- `CONFIGURATION` — standalone by design (Spring `@Configuration`)
- `ENTITY` — JPA entities may be referenced only via repository queries, not tracked as edges
- `ENUM` — often used in annotations/constants not captured as edges
- `RECORD` — DTOs may be used in ways not tracked
- `FE_MODEL` — TypeScript interfaces used in templates

Optional `type` filter adds `AND n.type = ?` clause.

### Output Format

```markdown
## Potentially Dead Code

### COMMAND (2)
- `ArchiveOrderCommand` (orders/ArchiveOrderCommand.java:5)
- `LegacyImportCommand` (legacy/LegacyImportCommand.java:3)

### SERVICE (1)
- `OldNotificationService` (notifications/OldNotificationService.java:8)

Total: 3 unreferenced symbols
```

### Implementation

- New method `findNodesWithNoIncomingEdges(NodeType filter)` in `GraphStore` (~15 lines)
- New handler `handleCgDead()` in `CodeNavigatorMcpServer` (~30 lines)
- New tool registration in `start()` method

---

## Feature 3: Hotspot Detection (`cg_hotspots`)

### MCP Tool

```
cg_hotspots(limit?, projectPath?)
```

- `limit` — max results per category (default 10)

### Queries

```sql
-- Fan-in: most depended-on nodes (high change risk)
SELECT n.id, n.type, n.name, n.file_path, n.line_number, COUNT(e.id) as edge_count
FROM nodes n
JOIN edges e ON e.target_id = n.id
GROUP BY n.id
ORDER BY edge_count DESC
LIMIT ?

-- Fan-out: nodes depending on most things (complexity risk)
SELECT n.id, n.type, n.name, n.file_path, n.line_number, COUNT(e.id) as edge_count
FROM nodes n
JOIN edges e ON e.source_id = n.id
GROUP BY n.id
ORDER BY edge_count DESC
LIMIT ?
```

### Output Format

```markdown
## Hotspots (top 10)

### Highest Fan-In (change risk — many things depend on these)
 1. OrderAggregate (AGGREGATE) — 18 incoming edges
 2. UserService (SERVICE) — 14 incoming edges
 3. PaymentService (SERVICE) — 11 incoming edges

### Highest Fan-Out (complexity risk — these depend on many things)
 1. OrderController (CONTROLLER) — 12 outgoing edges
 2. CheckoutService (SERVICE) — 9 outgoing edges
 3. OrderSagaHandler (COMMAND_HANDLER) — 7 outgoing edges
```

### Implementation

- New method `findHotspots(int limit)` in `GraphStore` returning a record with two lists (~30 lines)
- New handler `handleCgHotspots()` in `CodeNavigatorMcpServer` (~35 lines)

---

## Feature 4: Export Formats (`cg_export`)

### MCP Tool

```
cg_export(format, symbol?, projectPath?)
```

- `format` — required: `json`, `mermaid`, `plantuml`
- `symbol` — optional: export only the chain for this symbol (default: full graph)

### CLI Command

```bash
code-navigator export <path> --format mermaid [--symbol OrderAggregate] [--output diagram.md]
```

### New Classes

- `com.codenavigator.export.ExportService` — format conversion logic
- `com.codenavigator.cli.ExportCommand` — picocli subcommand

### JSON Format

```json
{
  "tier": "DDD",
  "generated": "2026-04-02",
  "nodes": [
    {
      "id": "com.example.OrderAggregate",
      "type": "AGGREGATE",
      "name": "OrderAggregate",
      "filePath": "src/main/java/com/example/orders/OrderAggregate.java",
      "lineNumber": 15
    }
  ],
  "edges": [
    {
      "type": "EMITS_EVENT",
      "source": "com.example.OrderAggregate",
      "target": "com.example.OrderCreatedEvent"
    }
  ]
}
```

### Mermaid Format

```
graph LR
  subgraph orders
    OrderController[OrderController<br/>CONTROLLER]
    CreateOrderCommand[CreateOrderCommand<br/>COMMAND]
    CreateOrderHandler[CreateOrderHandler<br/>COMMAND_HANDLER]
    OrderAggregate[OrderAggregate<br/>AGGREGATE]
    OrderCreatedEvent[OrderCreatedEvent<br/>DOMAIN_EVENT]
  end

  OrderController -->|DISPATCHES_COMMAND| CreateOrderCommand
  CreateOrderCommand -->|HANDLES| CreateOrderHandler
  CreateOrderHandler -->|LOADS_AGGREGATE| OrderAggregate
  OrderAggregate -->|EMITS_EVENT| OrderCreatedEvent
```

Node styling by type:
- CONTROLLER: blue
- COMMAND/QUERY: orange
- AGGREGATE: red
- DOMAIN_EVENT: green
- PROJECTION_HANDLER: purple

### PlantUML Format

```
@startuml
package "orders" {
  [OrderController] <<CONTROLLER>>
  [CreateOrderCommand] <<COMMAND>>
  [CreateOrderHandler] <<COMMAND_HANDLER>>
  [OrderAggregate] <<AGGREGATE>>
  [OrderCreatedEvent] <<DOMAIN_EVENT>>
}

[OrderController] --> [CreateOrderCommand] : DISPATCHES_COMMAND
[CreateOrderHandler] --> [OrderAggregate] : LOADS_AGGREGATE
[OrderAggregate] --> [OrderCreatedEvent] : EMITS_EVENT
@enduml
```

### Scoped Export

When `symbol` is provided, only export the chain from that symbol:
1. Resolve symbol via `resolveSymbol()`
2. Get chain via `traversal.traceChain(nodeId)`
3. Filter edges to only those between chain nodes
4. Export filtered graph

### Implementation

- `ExportService` with 3 methods: `toJson()`, `toMermaid()`, `toPlantUml()` (~150 lines total)
- Each takes `List<Node>` + `List<Edge>` and returns `String`
- Mermaid: group nodes by package prefix into subgraphs
- PlantUML: group nodes by package prefix into packages
- JSON: Jackson serialization

---

## Feature 5: Package Dependency Graph (`cg_packages`)

### MCP Tool

```
cg_packages(projectPath?)
```

### Logic

```java
// 1. Group nodes by package
Map<String, List<Node>> byPackage = getAllNodes().stream()
    .collect(groupingBy(n -> extractPackage(n.qualifiedName())));

// 2. For each edge, resolve source/target packages
// 3. Aggregate inter-package edges (skip intra-package)
// 4. Detect circular dependencies (A->B and B->A both exist)
```

`extractPackage()`: takes `com.example.orders.OrderAggregate` -> `com.example.orders`

### Output Format

```markdown
## Package Dependencies

### com.example.orders (28 nodes)
  -> com.example.payments (3 edges: DISPATCHES_COMMAND x2, DISPATCHES_QUERY x1)
  -> com.example.notifications (2 edges: PUBLISHES_EVENT x2)
  <- com.example.api (5 edges: CALLS_METHOD x3, INJECTS x2)

### com.example.payments (12 nodes)
  -> com.example.orders (1 edge: DISPATCHES_QUERY x1)
  <- com.example.orders (3 edges: DISPATCHES_COMMAND x2, DISPATCHES_QUERY x1)

## Circular Dependencies
- com.example.orders <-> com.example.payments (orders->payments: 3 edges, payments->orders: 1 edge)
```

### Implementation

- New method in `GraphTraversal` or standalone in `CodeNavigatorMcpServer` handler (~80 lines)
- Pure in-memory computation: load all nodes + all edges, group, aggregate, detect cycles
- Circular detection: build directed adjacency map of packages, find pairs where both A->B and B->A exist

---

## Summary

| Feature | New Files | Est. Lines | New Deps | Schema Changes |
|---------|-----------|-----------|----------|----------------|
| Briefing Generation | `BriefingGenerator.java`, `BriefingCommand.java`, `DomainDbReader.java` | ~300 | None | None |
| Dead Code | Method + handler | ~45 | None | None |
| Hotspots | Method + handler | ~65 | None | None |
| Export Formats | `ExportService.java`, `ExportCommand.java` | ~200 | None | None |
| Package Graph | Handler method | ~80 | None | None |
| **Total** | **~6 new files** | **~690 lines** | **None** | **None** |

## Implementation Order

1. **Dead code + Hotspots** — smallest, validates the pattern of adding new tools
2. **Package graph** — medium, standalone
3. **Export formats** — medium, new service class + CLI command
4. **Briefing generation** — largest, depends on understanding all data available from both DBs
