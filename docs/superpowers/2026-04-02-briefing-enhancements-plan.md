# Briefing Enhancements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enrich the code-navigator indexer with method/field extraction and enhance the BriefingGenerator to produce detailed endpoints, models, services, and components files.

**Architecture:** A new `methods` SQLite table stores structured data extracted from JavaParser AST during indexing. A new `MethodExtractor` class runs alongside `NodeExtractor` in `ProjectIndexer`. The `BriefingGenerator` queries this table to produce 4 enhanced/new briefing files. No new dependencies.

**Tech Stack:** Java 21, JavaParser 3.26.4, SQLite JDBC 3.47.2.0, Picocli 4.7.6, JUnit 5 + AssertJ

**Spec:** `docs/superpowers/2026-04-02-briefing-enhancements-design.md`

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `code-navigator/src/main/java/com/codenavigator/graph/GraphStore.java` | Modify | Add `methods` table schema, `MethodRecord` record, CRUD methods |
| `code-navigator/src/main/java/com/codenavigator/indexer/MethodExtractor.java` | Create | Extract methods/fields from JavaParser AST |
| `code-navigator/src/main/java/com/codenavigator/indexer/ProjectIndexer.java` | Modify | Call MethodExtractor after NodeExtractor in both full and incremental indexing |
| `code-navigator/src/main/java/com/codenavigator/briefing/BriefingGenerator.java` | Modify | Enhanced endpoints, new models/services/components generators |
| `code-navigator/src/test/java/com/codenavigator/graph/GraphStoreTest.java` | Modify | Test MethodRecord CRUD |
| `code-navigator/src/test/java/com/codenavigator/indexer/MethodExtractorTest.java` | Create | Test extraction from sample Java source |
| `code-navigator/src/test/java/com/codenavigator/briefing/BriefingGeneratorTest.java` | Modify | Test enhanced endpoints, new models/services/components output |
| `code-navigator/src/test/resources/sample-spring/src/main/java/com/sample/controller/GameTypeController.java` | Modify | Add HTTP method annotations for test coverage |
| `code-navigator/src/test/resources/sample-spring/src/main/java/com/sample/service/GameTypeService.java` | Modify | Add public methods for test coverage |

---

## Task 1: GraphStore — methods table + MethodRecord CRUD

**Files:**
- Modify: `code-navigator/src/main/java/com/codenavigator/graph/GraphStore.java`
- Test: `code-navigator/src/test/java/com/codenavigator/graph/GraphStoreTest.java`

- [ ] **Step 1: Write the failing tests**

In `GraphStoreTest.java`, add:

```java
@Test
void saveAndFindMethods() {
    store.saveNode(new Node("n1", NodeType.CONTROLLER, "Ctrl", "com.Ctrl", "f1", 1, "", 0));

    store.saveMethod(new GraphStore.MethodRecord("n1", "getAll", "ResponseEntity<List<Item>>", "", "GET /items", "public"));
    store.saveMethod(new GraphStore.MethodRecord("n1", "create", "ResponseEntity<Item>", "CreateRequest req", "POST /items", "public"));

    var methods = store.findMethodsByNodeId("n1");
    assertThat(methods).hasSize(2);
    assertThat(methods).extracting(GraphStore.MethodRecord::name).containsExactlyInAnyOrder("getAll", "create");
    assertThat(methods.stream().filter(m -> m.name().equals("getAll")).findFirst().get().annotations()).isEqualTo("GET /items");
}

@Test
void deleteMethodsByNodeId() {
    store.saveNode(new Node("n1", NodeType.SERVICE, "Svc", "com.Svc", "f1", 1, "", 0));
    store.saveMethod(new GraphStore.MethodRecord("n1", "findAll", "List<Item>", "", null, "public"));
    assertThat(store.findMethodsByNodeId("n1")).hasSize(1);

    store.deleteMethodsByNodeId("n1");
    assertThat(store.findMethodsByNodeId("n1")).isEmpty();
}

@Test
void findMethodsByNodeIds() {
    store.saveNode(new Node("n1", NodeType.SERVICE, "Svc1", "com.Svc1", "f1", 1, "", 0));
    store.saveNode(new Node("n2", NodeType.SERVICE, "Svc2", "com.Svc2", "f2", 1, "", 0));
    store.saveMethod(new GraphStore.MethodRecord("n1", "findAll", "List<A>", "", null, "public"));
    store.saveMethod(new GraphStore.MethodRecord("n2", "findAll", "List<B>", "", null, "public"));

    var methods = store.findMethodsByNodeIds(List.of("n1", "n2"));
    assertThat(methods).hasSize(2);
}

@Test
void deleteNodeCascadesMethodsToo() {
    store.saveNode(new Node("n1", NodeType.SERVICE, "Svc", "com.Svc", "f1", 1, "", 0));
    store.saveMethod(new GraphStore.MethodRecord("n1", "findAll", "List<Item>", "", null, "public"));

    store.deleteNode("n1");
    assertThat(store.findMethodsByNodeId("n1")).isEmpty();
}

@Test
void recordFieldsStoredAsMethods() {
    store.saveNode(new Node("n1", NodeType.RECORD, "Habit", "com.Habit", "f1", 1, "", 0));
    store.saveMethod(new GraphStore.MethodRecord("n1", "id", "UUID", null, null, "field"));
    store.saveMethod(new GraphStore.MethodRecord("n1", "name", "String", null, null, "field"));

    var fields = store.findMethodsByNodeId("n1");
    assertThat(fields).hasSize(2);
    assertThat(fields).allMatch(m -> m.visibility().equals("field"));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /home/kamil/Documents/Project/My/Mcp/code-navigator && ./gradlew test --tests "com.codenavigator.graph.GraphStoreTest.saveAndFindMethods" -q`
Expected: Compilation error — `MethodRecord`, `saveMethod`, `findMethodsByNodeId` don't exist

- [ ] **Step 3: Implement schema + MethodRecord + CRUD in GraphStore**

Add to `GraphStore.java`:

1. Add `MethodRecord` record after `NodeCount`:

```java
public record MethodRecord(String nodeId, String name, String returnType,
                            String parameters, String annotations, String visibility) {}
```

2. Add `methods` table creation in `initSchema()` after the existing tables:

```java
stmt.execute("""
    CREATE TABLE IF NOT EXISTS methods (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        node_id TEXT REFERENCES nodes(id) ON DELETE CASCADE,
        name TEXT NOT NULL,
        return_type TEXT,
        parameters TEXT,
        annotations TEXT,
        visibility TEXT
    )""");

stmt.execute("CREATE INDEX IF NOT EXISTS idx_methods_node ON methods(node_id)");
```

3. Add CRUD methods after the edge operations section:

```java
// ---- Method operations ----

public void saveMethod(MethodRecord method) {
    try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO methods (node_id, name, return_type, parameters, annotations, visibility)
            VALUES (?, ?, ?, ?, ?, ?)""")) {
        ps.setString(1, method.nodeId());
        ps.setString(2, method.name());
        ps.setString(3, method.returnType());
        ps.setString(4, method.parameters());
        ps.setString(5, method.annotations());
        ps.setString(6, method.visibility());
        ps.executeUpdate();
    } catch (SQLException e) {
        throw new RuntimeException("Failed to save method: " + method.name(), e);
    }
}

public void deleteMethodsByNodeId(String nodeId) {
    try (PreparedStatement ps = connection.prepareStatement("DELETE FROM methods WHERE node_id = ?")) {
        ps.setString(1, nodeId);
        ps.executeUpdate();
    } catch (SQLException e) {
        throw new RuntimeException("Failed to delete methods for node: " + nodeId, e);
    }
}

public List<MethodRecord> findMethodsByNodeId(String nodeId) {
    try (PreparedStatement ps = connection.prepareStatement(
            "SELECT node_id, name, return_type, parameters, annotations, visibility FROM methods WHERE node_id = ? ORDER BY name")) {
        ps.setString(1, nodeId);
        return mapMethods(ps.executeQuery());
    } catch (SQLException e) {
        throw new RuntimeException("Failed to find methods for node: " + nodeId, e);
    }
}

public List<MethodRecord> findMethodsByNodeIds(List<String> nodeIds) {
    if (nodeIds.isEmpty()) return List.of();
    String placeholders = String.join(",", nodeIds.stream().map(id -> "?").toList());
    String sql = "SELECT node_id, name, return_type, parameters, annotations, visibility FROM methods WHERE node_id IN (" + placeholders + ") ORDER BY node_id, name";
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
        for (int i = 0; i < nodeIds.size(); i++) {
            ps.setString(i + 1, nodeIds.get(i));
        }
        return mapMethods(ps.executeQuery());
    } catch (SQLException e) {
        throw new RuntimeException("Failed to find methods for nodes", e);
    }
}

private List<MethodRecord> mapMethods(ResultSet rs) throws SQLException {
    List<MethodRecord> methods = new ArrayList<>();
    while (rs.next()) {
        methods.add(new MethodRecord(
            rs.getString("node_id"), rs.getString("name"), rs.getString("return_type"),
            rs.getString("parameters"), rs.getString("annotations"), rs.getString("visibility")));
    }
    return methods;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/kamil/Documents/Project/My/Mcp/code-navigator && ./gradlew test --tests "com.codenavigator.graph.GraphStoreTest" -q`
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
cd /home/kamil/Documents/Project/My/Mcp && git add code-navigator/src/main/java/com/codenavigator/graph/GraphStore.java code-navigator/src/test/java/com/codenavigator/graph/GraphStoreTest.java
git commit -m "feat(code-navigator): add methods table and MethodRecord CRUD to GraphStore"
```

---

## Task 2: MethodExtractor — core extraction logic

**Files:**
- Create: `code-navigator/src/main/java/com/codenavigator/indexer/MethodExtractor.java`
- Create: `code-navigator/src/test/java/com/codenavigator/indexer/MethodExtractorTest.java`
- Modify: `code-navigator/src/test/resources/sample-spring/src/main/java/com/sample/controller/GameTypeController.java`

- [ ] **Step 1: Update test sample with HTTP annotations**

Replace `code-navigator/src/test/resources/sample-spring/src/main/java/com/sample/controller/GameTypeController.java` with:

```java
package com.sample.controller;

import com.sample.service.GameTypeService;

@RestController
@RequestMapping("/api/game-types")
public class GameTypeController {
    private final GameTypeService service;
    public GameTypeController(GameTypeService service) { this.service = service; }

    @GetMapping("")
    public Object list() { return service.findAll(); }

    @PostMapping("")
    public Object create(Object request) { return service.create(request); }

    @DeleteMapping("/{id}")
    public void delete(String id) { service.delete(id); }
}
```

- [ ] **Step 2: Write the failing tests**

Create `code-navigator/src/test/java/com/codenavigator/indexer/MethodExtractorTest.java`:

```java
package com.codenavigator.indexer;

import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.Node;
import com.codenavigator.graph.NodeType;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MethodExtractorTest {

    private MethodExtractor extractor;

    @BeforeEach
    void setUp() {
        StaticJavaParser.getParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        extractor = new MethodExtractor();
    }

    @Test
    void extractsControllerHttpMethods() throws Exception {
        var cu = StaticJavaParser.parse(Path.of(
            "src/test/resources/sample-spring/src/main/java/com/sample/controller/GameTypeController.java"));
        var node = new Node("com.sample.controller.GameTypeController", NodeType.CONTROLLER,
            "GameTypeController", "com.sample.controller.GameTypeController", "ctrl.java", 1, "", 0);

        var methods = extractor.extract(cu, List.of(node));

        assertThat(methods).hasSizeGreaterThanOrEqualTo(3);
        assertThat(methods).anyMatch(m -> m.annotations() != null && m.annotations().equals("GET /api/game-types"));
        assertThat(methods).anyMatch(m -> m.annotations() != null && m.annotations().equals("POST /api/game-types"));
        assertThat(methods).anyMatch(m -> m.annotations() != null && m.annotations().equals("DELETE /api/game-types/{id}"));
    }

    @Test
    void extractsServicePublicMethods() throws Exception {
        var cu = StaticJavaParser.parse(Path.of(
            "src/test/resources/sample-spring/src/main/java/com/sample/service/GameTypeService.java"));
        var node = new Node("com.sample.service.GameTypeService", NodeType.SERVICE,
            "GameTypeService", "com.sample.service.GameTypeService", "svc.java", 1, "", 0);

        var methods = extractor.extract(cu, List.of(node));

        assertThat(methods).allMatch(m -> m.visibility().equals("public"));
        assertThat(methods).anyMatch(m -> m.name().equals("findAll"));
    }

    @Test
    void extractsRecordFields() throws Exception {
        // Parse inline source for a record
        var cu = StaticJavaParser.parse(
            "package com.sample;" +
            "public record Habit(java.util.UUID id, String name, int currentStreak) {}");
        var node = new Node("com.sample.Habit", NodeType.RECORD,
            "Habit", "com.sample.Habit", "Habit.java", 1, "", 0);

        var methods = extractor.extract(cu, List.of(node));

        assertThat(methods).hasSize(3);
        assertThat(methods).allMatch(m -> m.visibility().equals("field"));
        assertThat(methods).anyMatch(m -> m.name().equals("id") && m.returnType().equals("UUID"));
        assertThat(methods).anyMatch(m -> m.name().equals("name") && m.returnType().equals("String"));
        assertThat(methods).anyMatch(m -> m.name().equals("currentStreak") && m.returnType().equals("int"));
    }

    @Test
    void skipsPrivateMethods() throws Exception {
        var cu = StaticJavaParser.parse(
            "package com.sample;" +
            "public class Svc {" +
            "  public String doWork() { return helper(); }" +
            "  private String helper() { return \"\"; }" +
            "}");
        var node = new Node("com.sample.Svc", NodeType.SERVICE,
            "Svc", "com.sample.Svc", "Svc.java", 1, "", 0);

        var methods = extractor.extract(cu, List.of(node));

        assertThat(methods).hasSize(1);
        assertThat(methods.get(0).name()).isEqualTo("doWork");
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd /home/kamil/Documents/Project/My/Mcp/code-navigator && ./gradlew test --tests "com.codenavigator.indexer.MethodExtractorTest" -q`
Expected: Compilation error — `MethodExtractor` does not exist

- [ ] **Step 4: Implement MethodExtractor**

Create `code-navigator/src/main/java/com/codenavigator/indexer/MethodExtractor.java`:

```java
package com.codenavigator.indexer;

import com.codenavigator.graph.GraphStore.MethodRecord;
import com.codenavigator.graph.Node;
import com.codenavigator.graph.NodeType;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class MethodExtractor {

    private static final Set<NodeType> METHOD_TYPES = Set.of(
        NodeType.CONTROLLER, NodeType.SERVICE, NodeType.REPOSITORY,
        NodeType.COMMAND_HANDLER, NodeType.QUERY_HANDLER, NodeType.EVENT_LISTENER,
        NodeType.PROJECTION_HANDLER
    );

    public List<MethodRecord> extract(CompilationUnit cu, List<Node> nodes) {
        var results = new ArrayList<MethodRecord>();
        var packageName = cu.getPackageDeclaration()
            .map(pd -> pd.getNameAsString()).orElse("");

        // Extract from classes/interfaces
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(decl -> {
            String qualifiedName = packageName.isEmpty() ? decl.getNameAsString()
                : packageName + "." + decl.getNameAsString();
            var node = nodes.stream().filter(n -> n.id().equals(qualifiedName)).findFirst();
            if (node.isEmpty()) return;
            if (!METHOD_TYPES.contains(node.get().type())) return;

            String basePath = extractAnnotationValue(decl, "RequestMapping");

            decl.getMethods().stream()
                .filter(m -> m.isPublic() || decl.isInterface())
                .forEach(m -> {
                    String httpAnnotation = extractHttpAnnotation(basePath, m);
                    String params = m.getParameters().stream()
                        .map(p -> p.getTypeAsString() + " " + p.getNameAsString())
                        .collect(Collectors.joining(", "));
                    results.add(new MethodRecord(
                        qualifiedName,
                        m.getNameAsString(),
                        m.getTypeAsString(),
                        params.isEmpty() ? "" : params,
                        httpAnnotation,
                        m.isPublic() ? "public" : "interface"
                    ));
                });
        });

        // Extract from records
        cu.findAll(RecordDeclaration.class).forEach(decl -> {
            String qualifiedName = packageName.isEmpty() ? decl.getNameAsString()
                : packageName + "." + decl.getNameAsString();
            var node = nodes.stream().filter(n -> n.id().equals(qualifiedName)).findFirst();
            if (node.isEmpty()) return;

            decl.getParameters().forEach(p ->
                results.add(new MethodRecord(
                    qualifiedName,
                    p.getNameAsString(),
                    p.getTypeAsString(),
                    null,
                    null,
                    "field"
                ))
            );
        });

        return results;
    }

    private String extractHttpAnnotation(String basePath, MethodDeclaration method) {
        for (String httpMethod : List.of("Get", "Post", "Put", "Delete", "Patch")) {
            String annotName = httpMethod + "Mapping";
            String path = extractAnnotationValue(method, annotName);
            if (path != null) {
                String fullPath = (basePath != null ? basePath : "") + path;
                return httpMethod.toUpperCase() + " " + fullPath;
            }
        }
        return null;
    }

    private String extractAnnotationValue(NodeWithAnnotations<?> node, String annotationName) {
        for (var annot : node.getAnnotations()) {
            if (!annot.getNameAsString().equals(annotationName)) continue;

            if (annot instanceof SingleMemberAnnotationExpr sma) {
                return stripQuotes(sma.getMemberValue().toString());
            }
            if (annot instanceof NormalAnnotationExpr na) {
                for (var pair : na.getPairs()) {
                    if (pair.getNameAsString().equals("value") || pair.getNameAsString().equals("path")) {
                        return stripQuotes(pair.getValue().toString());
                    }
                }
            }
            if (annot instanceof MarkerAnnotationExpr) {
                return "";
            }
        }
        return null;
    }

    private static String stripQuotes(String value) {
        if (value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
```

- [ ] **Step 5: Update GameTypeService test sample**

Check `code-navigator/src/test/resources/sample-spring/src/main/java/com/sample/service/GameTypeService.java` and ensure it has at least one public method. If it only has `findAll`, that's fine — the test checks for it.

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd /home/kamil/Documents/Project/My/Mcp/code-navigator && ./gradlew test --tests "com.codenavigator.indexer.MethodExtractorTest" -q`
Expected: All PASS

- [ ] **Step 7: Commit**

```bash
cd /home/kamil/Documents/Project/My/Mcp && git add code-navigator/src/main/java/com/codenavigator/indexer/MethodExtractor.java code-navigator/src/test/java/com/codenavigator/indexer/MethodExtractorTest.java code-navigator/src/test/resources/sample-spring/src/main/java/com/sample/controller/GameTypeController.java
git commit -m "feat(code-navigator): add MethodExtractor for AST-based method/field extraction"
```

---

## Task 3: ProjectIndexer integration

**Files:**
- Modify: `code-navigator/src/main/java/com/codenavigator/indexer/ProjectIndexer.java`

- [ ] **Step 1: Add MethodExtractor to full indexing**

In `ProjectIndexer.java`, add a `MethodExtractor` field:

```java
private final MethodExtractor methodExtractor;
```

Initialize in constructor:

```java
this.methodExtractor = new MethodExtractor();
```

In `indexFull()`, after the Java node extraction loop (after line 60 `} catch`), add a new phase for method extraction:

```java
// 3b. Phase 1c: Extract methods from Java files
for (Path file : javaFiles) {
    try {
        var cu = StaticJavaParser.parse(file);
        var filePath = projectPath.relativize(file).toString();
        var fileNodes = store.findNodesByFilePath(filePath);
        var methods = methodExtractor.extract(cu, fileNodes);
        for (var method : methods) {
            store.saveMethod(method);
        }
    } catch (Exception e) {
        // Skip unparseable files
    }
}
```

- [ ] **Step 2: Add MethodExtractor to incremental indexing**

In `reindexChangedJavaFiles()`, after saving nodes (around line 144), add method extraction for the changed file:

After `store.saveNode(nodeWithTime);` loop ends, add:

```java
// Re-extract methods for this file's nodes
var updatedNodes = store.findNodesByFilePath(filePath);
var methods = methodExtractor.extract(cu, updatedNodes);
for (var method : methods) {
    store.saveMethod(method);
}
```

Note: `deleteNodesByFilePath` cascades to methods via foreign key, so stale methods are already cleaned up.

- [ ] **Step 3: Run the full integration test**

Run: `cd /home/kamil/Documents/Project/My/Mcp/code-navigator && ./gradlew test --tests "com.codenavigator.indexer.ProjectIndexerIntegrationTest" -q`
Expected: PASS (existing tests still work)

- [ ] **Step 4: Run full test suite**

Run: `cd /home/kamil/Documents/Project/My/Mcp/code-navigator && ./gradlew test -q`
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
cd /home/kamil/Documents/Project/My/Mcp && git add code-navigator/src/main/java/com/codenavigator/indexer/ProjectIndexer.java
git commit -m "feat(code-navigator): integrate MethodExtractor into ProjectIndexer"
```

---

## Task 4: Enhanced BriefingGenerator — endpoints + models + services + components

**Files:**
- Modify: `code-navigator/src/main/java/com/codenavigator/briefing/BriefingGenerator.java`
- Modify: `code-navigator/src/test/java/com/codenavigator/briefing/BriefingGeneratorTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `BriefingGeneratorTest.java`. First, update `setUp()` to also save methods for the test nodes:

After the existing edge saves, add:

```java
// Methods for controller
store.saveMethod(new GraphStore.MethodRecord("com.orders.api.OrderController", "getAll",
    "ResponseEntity<List<OrderResponse>>", "", "GET /api/orders", "public"));
store.saveMethod(new GraphStore.MethodRecord("com.orders.api.OrderController", "create",
    "ResponseEntity<OrderResponse>", "CreateOrderRequest req", "POST /api/orders", "public"));

// Methods for record fields
store.saveMethod(new GraphStore.MethodRecord("com.orders.commands.CreateOrderCommand", "name", "String", null, null, "field"));
store.saveMethod(new GraphStore.MethodRecord("com.orders.commands.CreateOrderCommand", "quantity", "int", null, null, "field"));

// Methods for service (reuse existing OrderListProjection as a proxy — or add a SERVICE node)
store.saveNode(new Node("com.orders.services.OrderService", NodeType.SERVICE, "OrderService",
    "com.orders.services.OrderService", "OrderService.java", 1, "", 0));
store.saveMethod(new GraphStore.MethodRecord("com.orders.services.OrderService", "findAll",
    "List<Order>", "", null, "public"));
store.saveMethod(new GraphStore.MethodRecord("com.orders.services.OrderService", "create",
    "Order", "String name, int quantity", null, "public"));

// FE nodes for components test
store.saveNode(new Node("OrderPageComponent", NodeType.FE_COMPONENT, "OrderPageComponent",
    "OrderPageComponent", "order-page.component.ts", 1, "", 0));
store.saveNode(new Node("OrderService", NodeType.FE_SERVICE, "OrderService",
    "OrderService", "order.service.ts", 1, "", 0));
store.saveEdge(new Edge("fe1", EdgeType.USES_SERVICE, "OrderPageComponent", "OrderService"));
store.saveEdge(new Edge("fe2", EdgeType.CALLS_API, "OrderService", "com.orders.api.OrderController"));
```

Then add the test methods:

```java
@Test
void endpointsShowHttpMethods() throws IOException {
    new BriefingGenerator(store, null).generate(outputDir);

    String content = Files.readString(outputDir.resolve("endpoints.md"));
    assertThat(content).contains("GET");
    assertThat(content).contains("/api/orders");
    assertThat(content).contains("POST");
    assertThat(content).contains("List<OrderResponse>");
}

@Test
void generatesModelsFile() throws IOException {
    new BriefingGenerator(store, null).generate(outputDir);

    String content = Files.readString(outputDir.resolve("models.md"));
    assertThat(content).contains("CreateOrderCommand");
    assertThat(content).contains("name: String");
    assertThat(content).contains("quantity: int");
}

@Test
void generatesServicesFile() throws IOException {
    new BriefingGenerator(store, null).generate(outputDir);

    String content = Files.readString(outputDir.resolve("services.md"));
    assertThat(content).contains("OrderService");
    assertThat(content).contains("findAll()");
    assertThat(content).contains("List<Order>");
}

@Test
void generatesComponentsFile() throws IOException {
    new BriefingGenerator(store, null).generate(outputDir);

    String content = Files.readString(outputDir.resolve("components.md"));
    assertThat(content).contains("OrderPageComponent");
    assertThat(content).contains("OrderService");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /home/kamil/Documents/Project/My/Mcp/code-navigator && ./gradlew test --tests "com.codenavigator.briefing.BriefingGeneratorTest" -q`
Expected: FAIL — new test methods fail (models.md, services.md, components.md don't exist yet; endpoints.md doesn't have HTTP methods)

- [ ] **Step 3: Implement enhanced generateEndpoints()**

Replace `generateEndpoints()` in `BriefingGenerator.java`:

```java
private String generateEndpoints() {
    var controllers = store.findNodesByType(NodeType.CONTROLLER);
    if (controllers.isEmpty()) return null;

    var sb = new StringBuilder();
    sb.append("# Endpoints\n\n");

    for (var ctrl : controllers) {
        String basePath = extractBasePath(ctrl.codeSnippet());
        sb.append("## ").append(ctrl.name()).append(" (").append(basePath).append(")\n");

        var methods = store.findMethodsByNodeId(ctrl.id());
        var httpMethods = methods.stream()
            .filter(m -> m.annotations() != null && !m.annotations().isEmpty())
            .toList();

        if (!httpMethods.isEmpty()) {
            for (var method : httpMethods) {
                String returnType = simplifyReturnType(method.returnType());
                sb.append("  ").append(String.format("%-7s", method.annotations().split(" ")[0]));
                sb.append(method.annotations().substring(method.annotations().indexOf(' ')));
                sb.append(" -> ").append(returnType).append("\n");
            }
        } else {
            // Fallback: show dispatched commands/queries (DDD mode)
            var dispatched = store.findEdgesFrom(ctrl.id()).stream()
                .filter(e -> e.type() == EdgeType.DISPATCHES_COMMAND || e.type() == EdgeType.DISPATCHES_QUERY)
                .toList();
            for (var edge : dispatched) {
                var target = store.findNodeById(edge.targetId());
                String targetName = target.map(Node::name).orElse(edge.targetId());
                sb.append("  ").append(basePath).append(" -> ").append(targetName).append("\n");
            }
        }
        sb.append("\n");
    }
    return sb.toString();
}

private static String simplifyReturnType(String returnType) {
    if (returnType == null) return "void";
    // Strip ResponseEntity wrapper: ResponseEntity<List<Foo>> -> List<Foo>
    if (returnType.startsWith("ResponseEntity<") && returnType.endsWith(">")) {
        return returnType.substring("ResponseEntity<".length(), returnType.length() - 1);
    }
    return returnType;
}
```

- [ ] **Step 4: Implement generateModels()**

Add to `BriefingGenerator.java`:

```java
private String generateModels() {
    var records = store.findNodesByType(NodeType.RECORD);
    if (records.isEmpty()) return null;

    var recordIds = records.stream().map(Node::id).toList();
    var allFields = store.findMethodsByNodeIds(recordIds);
    var fieldsByNode = allFields.stream()
        .filter(m -> "field".equals(m.visibility()))
        .collect(Collectors.groupingBy(GraphStore.MethodRecord::nodeId));

    if (fieldsByNode.isEmpty()) return null;

    var byPackage = records.stream()
        .filter(r -> fieldsByNode.containsKey(r.id()))
        .collect(Collectors.groupingBy(
            r -> extractPackage(r.qualifiedName()), LinkedHashMap::new, Collectors.toList()));

    var sb = new StringBuilder();
    sb.append("# Models\n\n");

    for (var entry : byPackage.entrySet()) {
        sb.append("## ").append(entry.getKey()).append("\n");
        for (var record : entry.getValue()) {
            var fields = fieldsByNode.get(record.id());
            if (fields == null || fields.isEmpty()) continue;
            String fieldStr = fields.stream()
                .map(f -> f.name() + ": " + f.returnType())
                .collect(Collectors.joining(", "));
            sb.append("- **").append(record.name()).append("**: ").append(fieldStr).append("\n");
        }
        sb.append("\n");
    }
    return sb.toString();
}
```

- [ ] **Step 5: Implement generateServices()**

Add to `BriefingGenerator.java`:

```java
private String generateServices() {
    var services = store.findNodesByType(NodeType.SERVICE);
    if (services.isEmpty()) return null;

    var serviceIds = services.stream().map(Node::id).toList();
    var allMethods = store.findMethodsByNodeIds(serviceIds);
    var methodsByNode = allMethods.stream()
        .filter(m -> "public".equals(m.visibility()))
        .collect(Collectors.groupingBy(GraphStore.MethodRecord::nodeId));

    if (methodsByNode.isEmpty()) return null;

    var sb = new StringBuilder();
    sb.append("# Services\n\n");

    for (var svc : services) {
        var methods = methodsByNode.get(svc.id());
        if (methods == null || methods.isEmpty()) continue;
        sb.append("## ").append(svc.name()).append("\n");
        for (var method : methods) {
            sb.append("  ").append(method.name()).append("(");
            if (method.parameters() != null && !method.parameters().isEmpty()) {
                sb.append(method.parameters());
            }
            sb.append(") -> ").append(method.returnType() != null ? method.returnType() : "void").append("\n");
        }
        sb.append("\n");
    }
    return sb.toString();
}
```

- [ ] **Step 6: Implement generateComponents()**

Add to `BriefingGenerator.java`:

```java
private String generateComponents() {
    var components = store.findNodesByType(NodeType.FE_COMPONENT);
    if (components.isEmpty()) return null;

    var sb = new StringBuilder();
    sb.append("# Components\n\n");

    for (var comp : components) {
        var serviceEdges = store.findEdgesFrom(comp.id()).stream()
            .filter(e -> e.type() == EdgeType.USES_SERVICE)
            .toList();
        if (serviceEdges.isEmpty()) continue;

        sb.append("## ").append(comp.name()).append("\n");
        for (var edge : serviceEdges) {
            var feService = store.findNodeById(edge.targetId());
            String serviceName = feService.map(Node::name).orElse(edge.targetId());

            // Follow CALLS_API to find backend controller
            String controllerName = feService.flatMap(s ->
                store.findEdgesFrom(s.id()).stream()
                    .filter(e -> e.type() == EdgeType.CALLS_API)
                    .findFirst()
                    .flatMap(e -> store.findNodeById(e.targetId()))
                    .map(Node::name)
            ).orElse(null);

            sb.append("  uses: ").append(serviceName);
            if (controllerName != null) sb.append(" -> ").append(controllerName);
            sb.append("\n");
        }
        sb.append("\n");
    }
    return sb.toString();
}
```

- [ ] **Step 7: Wire new generators into generate()**

Update the `generate()` method:

```java
public void generate(Path outputDir) throws IOException {
    Files.createDirectories(outputDir);
    writeFile(outputDir, "overview.md", generateOverview());
    writeFile(outputDir, "endpoints.md", generateEndpoints());
    writeFile(outputDir, "models.md", generateModels());
    writeFile(outputDir, "services.md", generateServices());
    writeFile(outputDir, "components.md", generateComponents());
    writeFile(outputDir, "projections.md", generateProjections());
    if (domainReader != null) {
        writeFile(outputDir, "domain.md", generateDomain());
        writeFile(outputDir, "flows.md", generateFlows());
        writeFile(outputDir, "rules.md", generateRules());
    }
}
```

Add the import at the top:

```java
import com.codenavigator.graph.GraphStore;
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `cd /home/kamil/Documents/Project/My/Mcp/code-navigator && ./gradlew test -q`
Expected: All PASS

- [ ] **Step 9: Commit**

```bash
cd /home/kamil/Documents/Project/My/Mcp && git add code-navigator/src/main/java/com/codenavigator/briefing/BriefingGenerator.java code-navigator/src/test/java/com/codenavigator/briefing/BriefingGeneratorTest.java
git commit -m "feat(code-navigator): enhanced briefing with endpoints, models, services, components"
```

---

## Task 5: Build, Re-index woa, Verify

- [ ] **Step 1: Run full test suite**

Run: `cd /home/kamil/Documents/Project/My/Mcp/code-navigator && ./gradlew test`
Expected: All tests pass

- [ ] **Step 2: Build shadow JAR**

Run: `cd /home/kamil/Documents/Project/My/Mcp/code-navigator && ./gradlew shadowJar -q && cp build/libs/code-navigator-0.1.0.jar /home/kamil/Documents/Project/My/Mcp/jars/code-navigator.jar`

- [ ] **Step 3: Re-index woa**

Run: `java -jar /home/kamil/Documents/Project/My/Mcp/jars/code-navigator.jar init /home/kamil/Documents/Project/My/woa`
Expected: "Done! Tier: CRUD, ..." with node/edge counts

- [ ] **Step 4: Generate briefing**

Run: `java -jar /home/kamil/Documents/Project/My/Mcp/jars/code-navigator.jar briefing /home/kamil/Documents/Project/My/woa`
Expected: "Briefing generated at /home/kamil/Documents/Project/My/woa/.ai-briefing"

- [ ] **Step 5: Verify output files**

Check that these files exist and have meaningful content:
- `.ai-briefing/endpoints.md` — should show `GET`, `POST`, `DELETE` with paths and return types
- `.ai-briefing/models.md` — should show record fields grouped by package
- `.ai-briefing/services.md` — should show public method signatures
- `.ai-briefing/components.md` — should show component→service→controller chains

- [ ] **Step 6: Commit plan**

```bash
cd /home/kamil/Documents/Project/My/Mcp && git add docs/superpowers/2026-04-02-briefing-enhancements-plan.md
git commit -m "docs: add briefing enhancements implementation plan"
```
