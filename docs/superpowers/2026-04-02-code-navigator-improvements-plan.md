# Code-Navigator Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 5 analysis/export features to code-navigator: dead code detection, hotspot analysis, package dependency graph, export formats (JSON/Mermaid/PlantUML), and compact index generation (briefing) that reads both code-navigator and domain-navigator databases.

**Architecture:** All features are pure read queries on the existing SQLite graph database — no schema changes, no new dependencies. New MCP tools are added to `CodeNavigatorMcpServer` following the existing pattern (tool builder + handler method). Two features also get CLI commands (`briefing` and `export`). The briefing feature reads domain-navigator's DB via a lightweight `DomainDbReader` using raw JDBC — no code dependency on domain-navigator.

**Tech Stack:** Java 21, SQLite JDBC 3.47.2.0, Picocli 4.7.6, Jackson 2.18.2, JUnit 5 + AssertJ

**Spec:** `docs/superpowers/2026-04-02-code-navigator-improvements-design.md`

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `code-navigator/src/main/java/com/codenavigator/graph/GraphStore.java` | Modify | Add `findDeadNodes()` and `findHotspots()` query methods |
| `code-navigator/src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java` | Modify | Register 4 new MCP tools (`cg_dead`, `cg_hotspots`, `cg_packages`, `cg_export`), add handler methods |
| `code-navigator/src/main/java/com/codenavigator/export/ExportService.java` | Create | JSON, Mermaid, PlantUML formatting from node/edge lists |
| `code-navigator/src/main/java/com/codenavigator/cli/ExportCommand.java` | Create | `code-navigator export` picocli subcommand |
| `code-navigator/src/main/java/com/codenavigator/briefing/BriefingGenerator.java` | Create | Read both DBs, generate 3-6 markdown files to `.ai-briefing/` |
| `code-navigator/src/main/java/com/codenavigator/briefing/DomainDbReader.java` | Create | Read-only JDBC access to domain-navigator.db (contexts, flows, rules, entities, terms) |
| `code-navigator/src/main/java/com/codenavigator/cli/BriefingCommand.java` | Create | `code-navigator briefing` picocli subcommand |
| `code-navigator/src/main/java/com/codenavigator/CodeNavigatorApplication.java` | Modify | Register `BriefingCommand` and `ExportCommand` subcommands |
| `code-navigator/src/main/java/com/codenavigator/cli/ProjectPaths.java` | Modify | Add `domainDb()` path helper |
| `code-navigator/src/test/java/com/codenavigator/graph/GraphStoreTest.java` | Modify | Add tests for `findDeadNodes()` and `findHotspots()` |
| `code-navigator/src/test/java/com/codenavigator/mcp/CodeNavigatorMcpServerTest.java` | Modify | Add tests for `cg_dead`, `cg_hotspots`, `cg_packages`, `cg_export` handlers |
| `code-navigator/src/test/java/com/codenavigator/export/ExportServiceTest.java` | Create | Unit tests for JSON/Mermaid/PlantUML formatting |
| `code-navigator/src/test/java/com/codenavigator/briefing/BriefingGeneratorTest.java` | Create | Integration test: populate a GraphStore, run briefing, assert file contents |
| `code-navigator/src/test/java/com/codenavigator/briefing/DomainDbReaderTest.java` | Create | Unit test: create domain DB, read via DomainDbReader, assert data |
| `CLAUDE.md` | Modify | Update tool count (11 → 15), remove `my-mcp` from MCP Servers Available |

---

## Task 1: Dead Code Detection — GraphStore Query

**Files:**
- Modify: `code-navigator/src/main/java/com/codenavigator/graph/GraphStore.java`
- Test: `code-navigator/src/test/java/com/codenavigator/graph/GraphStoreTest.java`

- [ ] **Step 1: Write the failing test**

In `GraphStoreTest.java`, add:

```java
@Test
void findDeadNodes_returnsNodesWithNoIncomingEdges() {
    // n1 -> n2 -> n3, n4 is orphan (no incoming edges to n4)
    store.saveNode(new Node("n1", NodeType.CONTROLLER, "Ctrl", "com.Ctrl", "f1", 1, "", 0));
    store.saveNode(new Node("n2", NodeType.SERVICE, "Svc", "com.Svc", "f2", 1, "", 0));
    store.saveNode(new Node("n3", NodeType.REPOSITORY, "Repo", "com.Repo", "f3", 1, "", 0));
    store.saveNode(new Node("n4", NodeType.SERVICE, "DeadSvc", "com.DeadSvc", "f4", 1, "", 0));
    store.saveNode(new Node("n5", NodeType.CONFIGURATION, "Config", "com.Config", "f5", 1, "", 0));

    store.saveEdge(new Edge("e1", EdgeType.INJECTS, "n1", "n2"));
    store.saveEdge(new Edge("e2", EdgeType.CALLS_METHOD, "n2", "n3"));

    // n1 has no incoming but is CONTROLLER (entry point) — should still show
    // n4 has no incoming and is SERVICE — dead code
    // n5 has no incoming but is CONFIGURATION — excluded
    List<Node> dead = store.findDeadNodes(null);
    assertThat(dead).extracting(Node::name).contains("Ctrl", "DeadSvc");
    assertThat(dead).extracting(Node::name).doesNotContain("Config");
}

@Test
void findDeadNodes_filteredByType() {
    store.saveNode(new Node("n1", NodeType.CONTROLLER, "Ctrl", "com.Ctrl", "f1", 1, "", 0));
    store.saveNode(new Node("n2", NodeType.SERVICE, "Svc", "com.Svc", "f2", 1, "", 0));

    List<Node> dead = store.findDeadNodes(NodeType.SERVICE);
    assertThat(dead).extracting(Node::name).containsExactly("Svc");
    assertThat(dead).extracting(Node::name).doesNotContain("Ctrl");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd code-navigator && ./gradlew test --tests "com.codenavigator.graph.GraphStoreTest.findDeadNodes*" -q`
Expected: Compilation error — `findDeadNodes` method does not exist

- [ ] **Step 3: Implement `findDeadNodes` in GraphStore**

Add to `GraphStore.java` after the `getAllEdges()` method:

```java
/**
 * Find nodes with no incoming edges (potential dead code).
 * Excludes CONFIGURATION, ENTITY, ENUM, RECORD, FE_MODEL by default.
 * Optional type filter narrows to a specific NodeType.
 */
public List<Node> findDeadNodes(NodeType typeFilter) {
    String sql;
    if (typeFilter != null) {
        sql = """
            SELECT n.* FROM nodes n
            LEFT JOIN edges e ON e.target_id = n.id
            WHERE e.id IS NULL AND n.type = ?
            ORDER BY n.type, n.name""";
    } else {
        sql = """
            SELECT n.* FROM nodes n
            LEFT JOIN edges e ON e.target_id = n.id
            WHERE e.id IS NULL
            AND n.type NOT IN ('CONFIGURATION', 'ENTITY', 'ENUM', 'RECORD', 'FE_MODEL')
            ORDER BY n.type, n.name""";
    }
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
        if (typeFilter != null) {
            ps.setString(1, typeFilter.name());
        }
        return mapNodes(ps.executeQuery());
    } catch (SQLException e) {
        throw new RuntimeException("Failed to find dead nodes", e);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd code-navigator && ./gradlew test --tests "com.codenavigator.graph.GraphStoreTest.findDeadNodes*" -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd code-navigator && git add src/main/java/com/codenavigator/graph/GraphStore.java src/test/java/com/codenavigator/graph/GraphStoreTest.java
git commit -m "feat(code-navigator): add findDeadNodes query to GraphStore"
```

---

## Task 2: Dead Code Detection — MCP Tool

**Files:**
- Modify: `code-navigator/src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java`
- Test: `code-navigator/src/test/java/com/codenavigator/mcp/CodeNavigatorMcpServerTest.java`

- [ ] **Step 1: Write the failing test**

In `CodeNavigatorMcpServerTest.java`, add:

```java
@Test
void cgDead() {
    // Add an orphan node (no edges pointing to it, not excluded type)
    store.saveNode(new Node("com.DeadService", NodeType.SERVICE, "DeadService",
        "com.DeadService", "Dead.java", 1, "class DeadService", 0));

    var result = mcpServer.handleCgDead(Map.of());
    assertThat(result).contains("DeadService");
    assertThat(result).contains("Potentially Dead Code");
}

@Test
void cgDeadWithTypeFilter() {
    store.saveNode(new Node("com.DeadService", NodeType.SERVICE, "DeadService",
        "com.DeadService", "Dead.java", 1, "class DeadService", 0));

    var result = mcpServer.handleCgDead(Map.of("type", "SERVICE"));
    assertThat(result).contains("DeadService");

    var result2 = mcpServer.handleCgDead(Map.of("type", "REPOSITORY"));
    assertThat(result2).doesNotContain("DeadService");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd code-navigator && ./gradlew test --tests "com.codenavigator.mcp.CodeNavigatorMcpServerTest.cgDead*" -q`
Expected: Compilation error — `handleCgDead` does not exist

- [ ] **Step 3: Implement handler and register tool**

In `CodeNavigatorMcpServer.java`, add the handler method after `handleCgFiles`:

```java
String handleCgDead(Map<String, Object> args) {
    NodeType typeFilter = null;
    if (args.containsKey("type")) {
        try {
            typeFilter = NodeType.valueOf(((String) args.get("type")).toUpperCase());
        } catch (IllegalArgumentException e) {
            return "Unknown type: " + args.get("type");
        }
    }

    var dead = store.findDeadNodes(typeFilter);
    if (dead.isEmpty()) return "No dead code found.";

    var byType = dead.stream().collect(java.util.stream.Collectors.groupingBy(
        Node::type, java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()));

    var sb = new StringBuilder();
    sb.append("## Potentially Dead Code\n\n");
    for (var entry : byType.entrySet()) {
        sb.append("### ").append(entry.getKey()).append(" (").append(entry.getValue().size()).append(")\n");
        for (var node : entry.getValue()) {
            sb.append("- `").append(node.name()).append("` (").append(node.filePath()).append(":").append(node.lineNumber()).append(")\n");
        }
        sb.append("\n");
    }
    sb.append("Total: ").append(dead.size()).append(" unreferenced symbols\n");
    return sb.toString();
}
```

Register the tool in the `start()` method's builder chain (add before `.build()`):

```java
.toolCall(
    Tool.builder()
        .name("cg_dead")
        .description("Find potentially dead code - nodes with no incoming edges (nothing references them).")
        .inputSchema(jsonSchema(
            withProjectPath(Map.of("type", propString("Optional NodeType filter (e.g. COMMAND, SERVICE)"))),
            List.of()))
        .build(),
    (exchange, request) -> textResult(handleCgDead(request.arguments()))
)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd code-navigator && ./gradlew test --tests "com.codenavigator.mcp.CodeNavigatorMcpServerTest.cgDead*" -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd code-navigator && git add src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java src/test/java/com/codenavigator/mcp/CodeNavigatorMcpServerTest.java
git commit -m "feat(code-navigator): add cg_dead MCP tool for dead code detection"
```

---

## Task 3: Hotspot Detection — GraphStore Query

**Files:**
- Modify: `code-navigator/src/main/java/com/codenavigator/graph/GraphStore.java`
- Test: `code-navigator/src/test/java/com/codenavigator/graph/GraphStoreTest.java`

- [ ] **Step 1: Write the failing test**

In `GraphStoreTest.java`, add:

```java
@Test
void findHotspots_fanIn() {
    store.saveNode(new Node("n1", NodeType.SERVICE, "PopularSvc", "com.PopularSvc", "f1", 1, "", 0));
    store.saveNode(new Node("n2", NodeType.CONTROLLER, "Ctrl1", "com.Ctrl1", "f2", 1, "", 0));
    store.saveNode(new Node("n3", NodeType.CONTROLLER, "Ctrl2", "com.Ctrl2", "f3", 1, "", 0));
    store.saveNode(new Node("n4", NodeType.CONTROLLER, "Ctrl3", "com.Ctrl3", "f4", 1, "", 0));
    store.saveNode(new Node("n5", NodeType.SERVICE, "LessSvc", "com.LessSvc", "f5", 1, "", 0));

    store.saveEdge(new Edge("e1", EdgeType.INJECTS, "n2", "n1"));
    store.saveEdge(new Edge("e2", EdgeType.INJECTS, "n3", "n1"));
    store.saveEdge(new Edge("e3", EdgeType.INJECTS, "n4", "n1"));
    store.saveEdge(new Edge("e4", EdgeType.INJECTS, "n2", "n5"));

    var fanIn = store.findTopFanIn(2);
    assertThat(fanIn).hasSize(2);
    assertThat(fanIn.get(0).node().name()).isEqualTo("PopularSvc");
    assertThat(fanIn.get(0).count()).isEqualTo(3);
}

@Test
void findHotspots_fanOut() {
    store.saveNode(new Node("n1", NodeType.CONTROLLER, "BigCtrl", "com.BigCtrl", "f1", 1, "", 0));
    store.saveNode(new Node("n2", NodeType.SERVICE, "Svc1", "com.Svc1", "f2", 1, "", 0));
    store.saveNode(new Node("n3", NodeType.SERVICE, "Svc2", "com.Svc2", "f3", 1, "", 0));
    store.saveNode(new Node("n4", NodeType.REPOSITORY, "Repo", "com.Repo", "f4", 1, "", 0));

    store.saveEdge(new Edge("e1", EdgeType.INJECTS, "n1", "n2"));
    store.saveEdge(new Edge("e2", EdgeType.INJECTS, "n1", "n3"));
    store.saveEdge(new Edge("e3", EdgeType.CALLS_METHOD, "n1", "n4"));

    var fanOut = store.findTopFanOut(2);
    assertThat(fanOut).hasSize(2);
    assertThat(fanOut.get(0).node().name()).isEqualTo("BigCtrl");
    assertThat(fanOut.get(0).count()).isEqualTo(3);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd code-navigator && ./gradlew test --tests "com.codenavigator.graph.GraphStoreTest.findHotspots*" -q`
Expected: Compilation error — `findTopFanIn`, `findTopFanOut`, `NodeCount` do not exist

- [ ] **Step 3: Implement hotspot queries in GraphStore**

Add the record and methods to `GraphStore.java`:

```java
public record NodeCount(Node node, int count) {}

public List<NodeCount> findTopFanIn(int limit) {
    String sql = """
        SELECT n.*, COUNT(e.id) as edge_count
        FROM nodes n JOIN edges e ON e.target_id = n.id
        GROUP BY n.id ORDER BY edge_count DESC LIMIT ?""";
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
        ps.setInt(1, limit);
        return mapNodeCounts(ps.executeQuery());
    } catch (SQLException e) {
        throw new RuntimeException("Failed to find top fan-in", e);
    }
}

public List<NodeCount> findTopFanOut(int limit) {
    String sql = """
        SELECT n.*, COUNT(e.id) as edge_count
        FROM nodes n JOIN edges e ON e.source_id = n.id
        GROUP BY n.id ORDER BY edge_count DESC LIMIT ?""";
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
        ps.setInt(1, limit);
        return mapNodeCounts(ps.executeQuery());
    } catch (SQLException e) {
        throw new RuntimeException("Failed to find top fan-out", e);
    }
}

private List<NodeCount> mapNodeCounts(ResultSet rs) throws SQLException {
    List<NodeCount> results = new ArrayList<>();
    while (rs.next()) {
        results.add(new NodeCount(mapNode(rs), rs.getInt("edge_count")));
    }
    return results;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd code-navigator && ./gradlew test --tests "com.codenavigator.graph.GraphStoreTest.findHotspots*" -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd code-navigator && git add src/main/java/com/codenavigator/graph/GraphStore.java src/test/java/com/codenavigator/graph/GraphStoreTest.java
git commit -m "feat(code-navigator): add findTopFanIn/findTopFanOut hotspot queries"
```

---

## Task 4: Hotspot Detection — MCP Tool

**Files:**
- Modify: `code-navigator/src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java`
- Test: `code-navigator/src/test/java/com/codenavigator/mcp/CodeNavigatorMcpServerTest.java`

- [ ] **Step 1: Write the failing test**

In `CodeNavigatorMcpServerTest.java`, add:

```java
@Test
void cgHotspots() {
    // The test graph has Order with 2 incoming (EMITS_EVENT from Order, PROJECTS_EVENT to it)
    // and OrderController with outgoing DISPATCHES_COMMAND
    var result = mcpServer.handleCgHotspots(Map.of("limit", 3));
    assertThat(result).contains("Hotspots");
    assertThat(result).contains("Fan-In");
    assertThat(result).contains("Fan-Out");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd code-navigator && ./gradlew test --tests "com.codenavigator.mcp.CodeNavigatorMcpServerTest.cgHotspots" -q`
Expected: Compilation error — `handleCgHotspots` does not exist

- [ ] **Step 3: Implement handler and register tool**

Add handler to `CodeNavigatorMcpServer.java`:

```java
String handleCgHotspots(Map<String, Object> args) {
    int limit = getIntArg(args, "limit", 10);

    var fanIn = store.findTopFanIn(limit);
    var fanOut = store.findTopFanOut(limit);

    var sb = new StringBuilder();
    sb.append("## Hotspots (top ").append(limit).append(")\n\n");

    sb.append("### Highest Fan-In (change risk — many things depend on these)\n");
    for (int i = 0; i < fanIn.size(); i++) {
        var nc = fanIn.get(i);
        sb.append(String.format(" %d. %s (%s) — %d incoming edges%n",
            i + 1, nc.node().name(), nc.node().type(), nc.count()));
    }

    sb.append("\n### Highest Fan-Out (complexity risk — these depend on many things)\n");
    for (int i = 0; i < fanOut.size(); i++) {
        var nc = fanOut.get(i);
        sb.append(String.format(" %d. %s (%s) — %d outgoing edges%n",
            i + 1, nc.node().name(), nc.node().type(), nc.count()));
    }

    return sb.toString();
}
```

Register in `start()`:

```java
.toolCall(
    Tool.builder()
        .name("cg_hotspots")
        .description("Find architectural hotspots - nodes with highest fan-in (change risk) and fan-out (complexity risk).")
        .inputSchema(jsonSchema(
            withProjectPath(Map.of("limit", propInt("Max results per category (default 10)"))),
            List.of()))
        .build(),
    (exchange, request) -> textResult(handleCgHotspots(request.arguments()))
)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd code-navigator && ./gradlew test --tests "com.codenavigator.mcp.CodeNavigatorMcpServerTest.cgHotspots" -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd code-navigator && git add src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java src/test/java/com/codenavigator/mcp/CodeNavigatorMcpServerTest.java
git commit -m "feat(code-navigator): add cg_hotspots MCP tool for coupling analysis"
```

---

## Task 5: Package Dependency Graph — MCP Tool

**Files:**
- Modify: `code-navigator/src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java`
- Test: `code-navigator/src/test/java/com/codenavigator/mcp/CodeNavigatorMcpServerTest.java`

- [ ] **Step 1: Write the failing test**

In `CodeNavigatorMcpServerTest.java`, add:

```java
@Test
void cgPackages() {
    var result = mcpServer.handleCgPackages(Map.of());
    assertThat(result).contains("Package Dependencies");
    // All test nodes are in "com" package, so mainly intra-package
    assertThat(result).contains("com");
}

@Test
void cgPackagesDetectsCircularDeps() {
    // Add cross-package nodes and edges
    store.saveNode(new Node("pkg.a.Foo", NodeType.SERVICE, "Foo", "pkg.a.Foo", "a/Foo.java", 1, "", 0));
    store.saveNode(new Node("pkg.b.Bar", NodeType.SERVICE, "Bar", "pkg.b.Bar", "b/Bar.java", 1, "", 0));
    store.saveEdge(new Edge("ex1", EdgeType.CALLS_METHOD, "pkg.a.Foo", "pkg.b.Bar"));
    store.saveEdge(new Edge("ex2", EdgeType.CALLS_METHOD, "pkg.b.Bar", "pkg.a.Foo"));

    var result = mcpServer.handleCgPackages(Map.of());
    assertThat(result).contains("Circular");
    assertThat(result).contains("pkg.a");
    assertThat(result).contains("pkg.b");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd code-navigator && ./gradlew test --tests "com.codenavigator.mcp.CodeNavigatorMcpServerTest.cgPackages*" -q`
Expected: Compilation error — `handleCgPackages` does not exist

- [ ] **Step 3: Implement handler and register tool**

Add handler to `CodeNavigatorMcpServer.java`:

```java
String handleCgPackages(Map<String, Object> args) {
    var allNodes = store.getAllNodes();
    var allEdges = store.getAllEdges();

    // Build node-id -> package map
    Map<String, String> nodePackage = new HashMap<>();
    Map<String, List<Node>> byPackage = new LinkedHashMap<>();
    for (var node : allNodes) {
        String pkg = extractPackage(node.qualifiedName());
        nodePackage.put(node.id(), pkg);
        byPackage.computeIfAbsent(pkg, k -> new ArrayList<>()).add(node);
    }

    // Aggregate inter-package edges
    // Map: sourcePackage -> targetPackage -> Map<EdgeType, count>
    Map<String, Map<String, Map<EdgeType, Integer>>> pkgEdges = new LinkedHashMap<>();
    for (var edge : allEdges) {
        String srcPkg = nodePackage.get(edge.sourceId());
        String tgtPkg = nodePackage.get(edge.targetId());
        if (srcPkg == null || tgtPkg == null || srcPkg.equals(tgtPkg)) continue;
        pkgEdges.computeIfAbsent(srcPkg, k -> new LinkedHashMap<>())
                .computeIfAbsent(tgtPkg, k -> new LinkedHashMap<>())
                .merge(edge.type(), 1, Integer::sum);
    }

    var sb = new StringBuilder();
    sb.append("## Package Dependencies\n\n");

    for (var entry : byPackage.entrySet()) {
        String pkg = entry.getKey();
        sb.append("### ").append(pkg).append(" (").append(entry.getValue().size()).append(" nodes)\n");

        // Outgoing
        var outgoing = pkgEdges.getOrDefault(pkg, Map.of());
        for (var target : outgoing.entrySet()) {
            int total = target.getValue().values().stream().mapToInt(Integer::intValue).sum();
            String detail = target.getValue().entrySet().stream()
                .map(e -> e.getKey() + " x" + e.getValue())
                .collect(Collectors.joining(", "));
            sb.append("  -> ").append(target.getKey()).append(" (").append(total).append(" edges: ").append(detail).append(")\n");
        }

        // Incoming
        for (var src : pkgEdges.entrySet()) {
            if (src.getValue().containsKey(pkg)) {
                int total = src.getValue().get(pkg).values().stream().mapToInt(Integer::intValue).sum();
                String detail = src.getValue().get(pkg).entrySet().stream()
                    .map(e -> e.getKey() + " x" + e.getValue())
                    .collect(Collectors.joining(", "));
                sb.append("  <- ").append(src.getKey()).append(" (").append(total).append(" edges: ").append(detail).append(")\n");
            }
        }
        sb.append("\n");
    }

    // Circular dependency detection
    List<String> circulars = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (var src : pkgEdges.entrySet()) {
        for (var tgt : src.getValue().keySet()) {
            String pair = src.getKey().compareTo(tgt) < 0 ? src.getKey() + "|" + tgt : tgt + "|" + src.getKey();
            if (!seen.add(pair)) continue;
            if (pkgEdges.containsKey(tgt) && pkgEdges.get(tgt).containsKey(src.getKey())) {
                int fwd = src.getValue().get(tgt).values().stream().mapToInt(Integer::intValue).sum();
                int rev = pkgEdges.get(tgt).get(src.getKey()).values().stream().mapToInt(Integer::intValue).sum();
                circulars.add(String.format("- %s <-> %s (%s->%s: %d edges, %s->%s: %d edges)",
                    src.getKey(), tgt, src.getKey(), tgt, fwd, tgt, src.getKey(), rev));
            }
        }
    }

    if (!circulars.isEmpty()) {
        sb.append("## Circular Dependencies\n");
        circulars.forEach(c -> sb.append(c).append("\n"));
    }

    return sb.toString();
}

private static String extractPackage(String qualifiedName) {
    int lastDot = qualifiedName.lastIndexOf('.');
    return lastDot > 0 ? qualifiedName.substring(0, lastDot) : qualifiedName;
}
```

Register in `start()`:

```java
.toolCall(
    Tool.builder()
        .name("cg_packages")
        .description("Show package-level dependency graph with inter-package edges and circular dependency detection.")
        .inputSchema(jsonSchema(withProjectPath(Map.of()), List.of()))
        .build(),
    (exchange, request) -> textResult(handleCgPackages(request.arguments()))
)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd code-navigator && ./gradlew test --tests "com.codenavigator.mcp.CodeNavigatorMcpServerTest.cgPackages*" -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd code-navigator && git add src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java src/test/java/com/codenavigator/mcp/CodeNavigatorMcpServerTest.java
git commit -m "feat(code-navigator): add cg_packages MCP tool for package dependency graph"
```

---

## Task 6: Export Service

**Files:**
- Create: `code-navigator/src/main/java/com/codenavigator/export/ExportService.java`
- Test: `code-navigator/src/test/java/com/codenavigator/export/ExportServiceTest.java`

- [ ] **Step 1: Write the failing tests**

Create `ExportServiceTest.java`:

```java
package com.codenavigator.export;

import com.codenavigator.graph.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ExportServiceTest {

    private final ExportService exportService = new ExportService();

    private final List<Node> nodes = List.of(
        new Node("com.orders.OrderController", NodeType.CONTROLLER, "OrderController",
            "com.orders.OrderController", "OrderController.java", 1, "", 0),
        new Node("com.orders.CreateOrderCommand", NodeType.COMMAND, "CreateOrderCommand",
            "com.orders.CreateOrderCommand", "CreateOrderCommand.java", 1, "", 0),
        new Node("com.payments.PaymentService", NodeType.SERVICE, "PaymentService",
            "com.payments.PaymentService", "PaymentService.java", 1, "", 0)
    );

    private final List<Edge> edges = List.of(
        new Edge("e1", EdgeType.DISPATCHES_COMMAND, "com.orders.OrderController", "com.orders.CreateOrderCommand"),
        new Edge("e2", EdgeType.CALLS_METHOD, "com.orders.OrderController", "com.payments.PaymentService")
    );

    @Test
    void toJson() {
        String json = exportService.toJson(nodes, edges, "DDD");
        assertThat(json).contains("\"tier\":\"DDD\"");
        assertThat(json).contains("\"name\":\"OrderController\"");
        assertThat(json).contains("\"type\":\"DISPATCHES_COMMAND\"");
        assertThat(json).contains("\"source\":\"com.orders.OrderController\"");
    }

    @Test
    void toMermaid() {
        String mermaid = exportService.toMermaid(nodes, edges);
        assertThat(mermaid).startsWith("graph LR");
        assertThat(mermaid).contains("subgraph com.orders");
        assertThat(mermaid).contains("OrderController[OrderController");
        assertThat(mermaid).contains("-->|DISPATCHES_COMMAND|");
    }

    @Test
    void toPlantUml() {
        String plantuml = exportService.toPlantUml(nodes, edges);
        assertThat(plantuml).contains("@startuml");
        assertThat(plantuml).contains("@enduml");
        assertThat(plantuml).contains("package \"com.orders\"");
        assertThat(plantuml).contains("<<CONTROLLER>>");
        assertThat(plantuml).contains("--> [CreateOrderCommand]");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd code-navigator && ./gradlew test --tests "com.codenavigator.export.ExportServiceTest" -q`
Expected: Compilation error — `ExportService` does not exist

- [ ] **Step 3: Implement ExportService**

Create `code-navigator/src/main/java/com/codenavigator/export/ExportService.java`:

```java
package com.codenavigator.export;

import com.codenavigator.graph.*;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

public class ExportService {

    public String toJson(List<Node> nodes, List<Edge> edges, String tier) {
        var sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"tier\": \"").append(tier != null ? tier : "unknown").append("\",\n");
        sb.append("  \"generated\": \"").append(LocalDate.now()).append("\",\n");
        sb.append("  \"nodes\": [\n");
        for (int i = 0; i < nodes.size(); i++) {
            var n = nodes.get(i);
            sb.append("    {\"id\": \"").append(n.id())
              .append("\", \"type\": \"").append(n.type())
              .append("\", \"name\": \"").append(n.name())
              .append("\", \"filePath\": \"").append(n.filePath())
              .append("\", \"lineNumber\": ").append(n.lineNumber())
              .append("}");
            if (i < nodes.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n");
        sb.append("  \"edges\": [\n");
        for (int i = 0; i < edges.size(); i++) {
            var e = edges.get(i);
            sb.append("    {\"type\": \"").append(e.type())
              .append("\", \"source\": \"").append(e.sourceId())
              .append("\", \"target\": \"").append(e.targetId())
              .append("\"}");
            if (i < edges.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    public String toMermaid(List<Node> nodes, List<Edge> edges) {
        var sb = new StringBuilder();
        sb.append("graph LR\n");

        // Group nodes by package into subgraphs
        var byPackage = nodes.stream().collect(Collectors.groupingBy(
            n -> extractPackage(n.qualifiedName()), LinkedHashMap::new, Collectors.toList()));

        // Build a set of valid node IDs for edge filtering
        Set<String> nodeIds = nodes.stream().map(Node::id).collect(Collectors.toSet());

        for (var entry : byPackage.entrySet()) {
            sb.append("  subgraph ").append(entry.getKey()).append("\n");
            for (var node : entry.getValue()) {
                String safeId = mermaidId(node.name());
                sb.append("    ").append(safeId).append("[").append(node.name())
                  .append("<br/>").append(node.type()).append("]\n");
            }
            sb.append("  end\n");
        }
        sb.append("\n");

        // Name lookup for edge rendering
        Map<String, String> idToName = nodes.stream().collect(Collectors.toMap(Node::id, Node::name));

        for (var edge : edges) {
            String src = idToName.get(edge.sourceId());
            String tgt = idToName.get(edge.targetId());
            if (src == null || tgt == null) continue;
            sb.append("  ").append(mermaidId(src)).append(" -->|").append(edge.type())
              .append("| ").append(mermaidId(tgt)).append("\n");
        }

        // Style classes by node type
        sb.append("\n");
        sb.append("  classDef controller fill:#4A90D9,color:#fff\n");
        sb.append("  classDef command fill:#E8A838,color:#fff\n");
        sb.append("  classDef aggregate fill:#D94A4A,color:#fff\n");
        sb.append("  classDef event fill:#4AD94A,color:#fff\n");
        sb.append("  classDef projection fill:#9B59B6,color:#fff\n");

        Map<NodeType, String> styleMap = Map.of(
            NodeType.CONTROLLER, "controller",
            NodeType.COMMAND, "command", NodeType.QUERY, "command",
            NodeType.AGGREGATE, "aggregate",
            NodeType.DOMAIN_EVENT, "event",
            NodeType.PROJECTION_HANDLER, "projection"
        );

        for (var node : nodes) {
            String style = styleMap.get(node.type());
            if (style != null) {
                sb.append("  class ").append(mermaidId(node.name())).append(" ").append(style).append("\n");
            }
        }

        return sb.toString();
    }

    public String toPlantUml(List<Node> nodes, List<Edge> edges) {
        var sb = new StringBuilder();
        sb.append("@startuml\n");

        var byPackage = nodes.stream().collect(Collectors.groupingBy(
            n -> extractPackage(n.qualifiedName()), LinkedHashMap::new, Collectors.toList()));

        Map<String, String> idToName = nodes.stream().collect(Collectors.toMap(Node::id, Node::name));

        for (var entry : byPackage.entrySet()) {
            sb.append("package \"").append(entry.getKey()).append("\" {\n");
            for (var node : entry.getValue()) {
                sb.append("  [").append(node.name()).append("] <<").append(node.type()).append(">>\n");
            }
            sb.append("}\n\n");
        }

        for (var edge : edges) {
            String src = idToName.get(edge.sourceId());
            String tgt = idToName.get(edge.targetId());
            if (src == null || tgt == null) continue;
            sb.append("[").append(src).append("] --> [").append(tgt).append("] : ").append(edge.type()).append("\n");
        }

        sb.append("@enduml\n");
        return sb.toString();
    }

    private static String extractPackage(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot > 0 ? qualifiedName.substring(0, lastDot) : qualifiedName;
    }

    private static String mermaidId(String name) {
        return name.replaceAll("[^a-zA-Z0-9]", "_");
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd code-navigator && ./gradlew test --tests "com.codenavigator.export.ExportServiceTest" -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd code-navigator && git add src/main/java/com/codenavigator/export/ExportService.java src/test/java/com/codenavigator/export/ExportServiceTest.java
git commit -m "feat(code-navigator): add ExportService for JSON/Mermaid/PlantUML output"
```

---

## Task 7: Export — MCP Tool + CLI Command

**Files:**
- Modify: `code-navigator/src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java`
- Create: `code-navigator/src/main/java/com/codenavigator/cli/ExportCommand.java`
- Modify: `code-navigator/src/main/java/com/codenavigator/CodeNavigatorApplication.java`
- Test: `code-navigator/src/test/java/com/codenavigator/mcp/CodeNavigatorMcpServerTest.java`

- [ ] **Step 1: Write the failing test**

In `CodeNavigatorMcpServerTest.java`, add:

```java
@Test
void cgExportJson() {
    var result = mcpServer.handleCgExport(Map.of("format", "json"));
    assertThat(result).contains("\"tier\":\"DDD\"");
    assertThat(result).contains("OrderController");
}

@Test
void cgExportMermaid() {
    var result = mcpServer.handleCgExport(Map.of("format", "mermaid"));
    assertThat(result).contains("graph LR");
    assertThat(result).contains("DISPATCHES_COMMAND");
}

@Test
void cgExportPlantUml() {
    var result = mcpServer.handleCgExport(Map.of("format", "plantuml"));
    assertThat(result).contains("@startuml");
    assertThat(result).contains("<<CONTROLLER>>");
}

@Test
void cgExportWithSymbolScope() {
    var result = mcpServer.handleCgExport(Map.of("format", "json", "symbol", "Order"));
    assertThat(result).contains("Order");
    // Scoped export should include chain members
    assertThat(result).contains("CreateOrderCommand");
}

@Test
void cgExportInvalidFormat() {
    var result = mcpServer.handleCgExport(Map.of("format", "xml"));
    assertThat(result).contains("Unknown format");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd code-navigator && ./gradlew test --tests "com.codenavigator.mcp.CodeNavigatorMcpServerTest.cgExport*" -q`
Expected: Compilation error — `handleCgExport` does not exist

- [ ] **Step 3: Implement MCP handler**

Update `CodeNavigatorMcpServer` — add `ExportService` field and constructor parameter:

In the constructor, add a new field:
```java
private final ExportService exportService = new ExportService();
```

Add handler method:

```java
String handleCgExport(Map<String, Object> args) {
    String format = (String) args.get("format");
    if (format == null) return "Missing required parameter: format";

    List<Node> nodes;
    List<Edge> edges;

    if (args.containsKey("symbol")) {
        String symbol = (String) args.get("symbol");
        String nodeId = resolveSymbol(symbol);
        if (nodeId == null) return "Symbol '" + symbol + "' not found.";
        nodes = traversal.traceChain(nodeId);
        Set<String> nodeIds = nodes.stream().map(Node::id).collect(Collectors.toSet());
        edges = store.getAllEdges().stream()
            .filter(e -> nodeIds.contains(e.sourceId()) && nodeIds.contains(e.targetId()))
            .toList();
    } else {
        nodes = store.getAllNodes();
        edges = store.getAllEdges();
    }

    String tier = store.getConfig("tier");

    return switch (format.toLowerCase()) {
        case "json" -> exportService.toJson(nodes, edges, tier);
        case "mermaid" -> exportService.toMermaid(nodes, edges);
        case "plantuml" -> exportService.toPlantUml(nodes, edges);
        default -> "Unknown format: " + format + ". Supported: json, mermaid, plantuml";
    };
}
```

Register in `start()`:

```java
.toolCall(
    Tool.builder()
        .name("cg_export")
        .description("Export graph as JSON, Mermaid diagram, or PlantUML. Optionally scope to a symbol's chain.")
        .inputSchema(jsonSchema(
            withProjectPath(Map.of(
                "format", propString("Output format: json, mermaid, plantuml"),
                "symbol", propString("Optional: scope export to this symbol's chain"))),
            List.of("format")))
        .build(),
    (exchange, request) -> textResult(handleCgExport(request.arguments()))
)
```

- [ ] **Step 4: Implement CLI command**

Create `code-navigator/src/main/java/com/codenavigator/cli/ExportCommand.java`:

```java
package com.codenavigator.cli;

import com.codenavigator.export.ExportService;
import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.GraphTraversal;
import com.codenavigator.graph.Node;
import com.codenavigator.graph.Edge;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Command(name = "export", description = "Export graph as JSON, Mermaid, or PlantUML")
public class ExportCommand implements Runnable {

    @Parameters(index = "0", description = "Path to the project root")
    private Path projectPath;

    @Option(names = "--format", required = true, description = "Output format: json, mermaid, plantuml")
    private String format;

    @Option(names = "--symbol", description = "Scope export to a symbol's chain")
    private String symbol;

    @Option(names = "--output", description = "Output file path (default: stdout)")
    private Path output;

    @Override
    public void run() {
        try (var store = new GraphStore(ProjectPaths.graphDb(projectPath))) {
            var traversal = new GraphTraversal(store);
            var exportService = new ExportService();

            List<Node> nodes;
            List<Edge> edges;

            if (symbol != null) {
                var nodeId = store.findNodesByName(symbol).stream().findFirst()
                    .map(Node::id).orElse(null);
                if (nodeId == null) {
                    System.err.println("Symbol not found: " + symbol);
                    return;
                }
                nodes = traversal.traceChain(nodeId);
                Set<String> nodeIds = nodes.stream().map(Node::id).collect(Collectors.toSet());
                edges = store.getAllEdges().stream()
                    .filter(e -> nodeIds.contains(e.sourceId()) && nodeIds.contains(e.targetId()))
                    .toList();
            } else {
                nodes = store.getAllNodes();
                edges = store.getAllEdges();
            }

            String tier = store.getConfig("tier");
            String result = switch (format.toLowerCase()) {
                case "json" -> exportService.toJson(nodes, edges, tier);
                case "mermaid" -> exportService.toMermaid(nodes, edges);
                case "plantuml" -> exportService.toPlantUml(nodes, edges);
                default -> { System.err.println("Unknown format: " + format); yield ""; }
            };

            if (output != null) {
                Files.writeString(output, result);
                System.out.println("Exported to " + output);
            } else {
                System.out.println(result);
            }
        } catch (IOException e) {
            System.err.println("Export failed: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 5: Register CLI command in application**

In `CodeNavigatorApplication.java`, add `ExportCommand.class` to the `subcommands` array:

```java
subcommands = {
    InitCommand.class,
    SyncCommand.class,
    ServeCommand.class,
    StatusCommand.class,
    MarkDirtyCommand.class,
    SyncIfDirtyCommand.class,
    InstallCommand.class,
    ExportCommand.class
}
```

- [ ] **Step 6: Run all tests**

Run: `cd code-navigator && ./gradlew test -q`
Expected: All tests PASS

- [ ] **Step 7: Commit**

```bash
cd code-navigator && git add src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java src/main/java/com/codenavigator/cli/ExportCommand.java src/main/java/com/codenavigator/CodeNavigatorApplication.java src/test/java/com/codenavigator/mcp/CodeNavigatorMcpServerTest.java
git commit -m "feat(code-navigator): add cg_export MCP tool and export CLI command"
```

---

## Task 8: Domain DB Reader

**Files:**
- Create: `code-navigator/src/main/java/com/codenavigator/briefing/DomainDbReader.java`
- Test: `code-navigator/src/test/java/com/codenavigator/briefing/DomainDbReaderTest.java`
- Modify: `code-navigator/src/main/java/com/codenavigator/cli/ProjectPaths.java`

- [ ] **Step 1: Add domain DB path helper**

In `ProjectPaths.java`, add:

```java
private static final String DOMAIN_GRAPH_DIR = "navigators/domain";
private static final String DOMAIN_DB_FILE = "domain-navigator.db";

public static Path domainDb(Path projectRoot) {
    return projectRoot.resolve(DOMAIN_GRAPH_DIR).resolve(DOMAIN_DB_FILE);
}

public static boolean hasDomainIndex(Path projectRoot) {
    return Files.exists(domainDb(projectRoot));
}
```

- [ ] **Step 2: Write the failing tests**

Create `DomainDbReaderTest.java`:

```java
package com.codenavigator.briefing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;

import static org.assertj.core.api.Assertions.assertThat;

class DomainDbReaderTest {

    @TempDir Path tempDir;
    private DomainDbReader reader;
    private Connection setupConn;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("domain.db");
        // Create domain DB with schema and test data
        setupConn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement stmt = setupConn.createStatement()) {
            stmt.execute("CREATE TABLE contexts (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, description TEXT, owner TEXT)");
            stmt.execute("CREATE TABLE context_entities (context_id INTEGER, entity_name TEXT)");
            stmt.execute("CREATE TABLE context_communications (context_id INTEGER, target_context TEXT, type TEXT, via TEXT)");
            stmt.execute("CREATE TABLE entities (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, context TEXT, type TEXT, description TEXT, code_mapping TEXT)");
            stmt.execute("CREATE TABLE flows (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, description TEXT, context TEXT, trigger_text TEXT, outcome TEXT)");
            stmt.execute("CREATE TABLE flow_steps (flow_id INTEGER, step_order INTEGER, action TEXT, actor TEXT, on_failure TEXT)");
            stmt.execute("CREATE TABLE rules (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, description TEXT, context TEXT, entity TEXT, severity TEXT, invariant TEXT)");
            stmt.execute("CREATE TABLE terms (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, definition TEXT, context TEXT, business_rule TEXT)");

            stmt.execute("INSERT INTO contexts (name, description, owner) VALUES ('Orders', 'Order management', 'team-orders')");
            stmt.execute("INSERT INTO context_entities (context_id, entity_name) VALUES (1, 'Order')");
            stmt.execute("INSERT INTO context_communications (context_id, target_context, type, via) VALUES (1, 'Payments', 'async', 'EventBus')");
            stmt.execute("INSERT INTO entities (name, context, type, description) VALUES ('Order', 'Orders', 'aggregate', 'Main order aggregate')");
            stmt.execute("INSERT INTO flows (name, context, trigger_text, outcome) VALUES ('Create Order', 'Orders', 'Customer submits form', 'Order created')");
            stmt.execute("INSERT INTO flow_steps (flow_id, step_order, action, actor) VALUES (1, 0, 'Validate order', 'OrderController')");
            stmt.execute("INSERT INTO rules (name, description, context, entity, severity, invariant) VALUES ('Min amount', 'Order must have positive total', 'Orders', 'Order', 'ERROR', 'total > 0')");
            stmt.execute("INSERT INTO terms (name, definition, context) VALUES ('Order', 'A customer purchase request', 'Orders')");
        }
        reader = new DomainDbReader(dbPath);
    }

    @AfterEach
    void tearDown() throws Exception {
        reader.close();
        setupConn.close();
    }

    @Test
    void readContexts() {
        var contexts = reader.readContexts();
        assertThat(contexts).hasSize(1);
        assertThat(contexts.get(0).name()).isEqualTo("Orders");
        assertThat(contexts.get(0).entities()).contains("Order");
        assertThat(contexts.get(0).communications()).hasSize(1);
    }

    @Test
    void readEntities() {
        var entities = reader.readEntities();
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).name()).isEqualTo("Order");
        assertThat(entities.get(0).type()).isEqualTo("aggregate");
    }

    @Test
    void readFlows() {
        var flows = reader.readFlows();
        assertThat(flows).hasSize(1);
        assertThat(flows.get(0).name()).isEqualTo("Create Order");
        assertThat(flows.get(0).steps()).hasSize(1);
    }

    @Test
    void readRules() {
        var rules = reader.readRules();
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).name()).isEqualTo("Min amount");
        assertThat(rules.get(0).severity()).isEqualTo("ERROR");
    }

    @Test
    void readTerms() {
        var terms = reader.readTerms();
        assertThat(terms).hasSize(1);
        assertThat(terms.get(0).name()).isEqualTo("Order");
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd code-navigator && ./gradlew test --tests "com.codenavigator.briefing.DomainDbReaderTest" -q`
Expected: Compilation error — `DomainDbReader` does not exist

- [ ] **Step 4: Implement DomainDbReader**

Create `code-navigator/src/main/java/com/codenavigator/briefing/DomainDbReader.java`:

```java
package com.codenavigator.briefing;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class DomainDbReader implements AutoCloseable {

    private final Connection connection;

    public record Context(String name, String description, String owner,
                          List<String> entities, List<Communication> communications) {}
    public record Communication(String targetContext, String type, String via) {}
    public record Entity(String name, String context, String type, String description) {}
    public record Flow(String name, String context, String trigger, String outcome, List<FlowStep> steps) {}
    public record FlowStep(String action, String actor, String onFailure) {}
    public record Rule(String name, String description, String context, String entity, String severity, String invariant) {}
    public record Term(String name, String definition, String context) {}

    public DomainDbReader(Path dbPath) {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
            connection.createStatement().execute("PRAGMA journal_mode=WAL");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open domain database: " + dbPath, e);
        }
    }

    public List<Context> readContexts() {
        try {
            List<Context> contexts = new ArrayList<>();
            try (ResultSet rs = connection.createStatement().executeQuery(
                    "SELECT id, name, description, owner FROM contexts ORDER BY id")) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    List<String> entities = new ArrayList<>();
                    try (PreparedStatement ps = connection.prepareStatement(
                            "SELECT entity_name FROM context_entities WHERE context_id = ?")) {
                        ps.setLong(1, id);
                        var ers = ps.executeQuery();
                        while (ers.next()) entities.add(ers.getString("entity_name"));
                    }
                    List<Communication> comms = new ArrayList<>();
                    try (PreparedStatement ps = connection.prepareStatement(
                            "SELECT target_context, type, via FROM context_communications WHERE context_id = ?")) {
                        ps.setLong(1, id);
                        var crs = ps.executeQuery();
                        while (crs.next()) comms.add(new Communication(
                            crs.getString("target_context"), crs.getString("type"), crs.getString("via")));
                    }
                    contexts.add(new Context(rs.getString("name"), rs.getString("description"),
                        rs.getString("owner"), entities, comms));
                }
            }
            return contexts;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read contexts", e);
        }
    }

    public List<Entity> readEntities() {
        try (ResultSet rs = connection.createStatement().executeQuery(
                "SELECT name, context, type, description FROM entities ORDER BY context, type, name")) {
            List<Entity> entities = new ArrayList<>();
            while (rs.next()) {
                entities.add(new Entity(rs.getString("name"), rs.getString("context"),
                    rs.getString("type"), rs.getString("description")));
            }
            return entities;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read entities", e);
        }
    }

    public List<Flow> readFlows() {
        try {
            List<Flow> flows = new ArrayList<>();
            try (ResultSet rs = connection.createStatement().executeQuery(
                    "SELECT id, name, context, trigger_text, outcome FROM flows ORDER BY id")) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    List<FlowStep> steps = new ArrayList<>();
                    try (PreparedStatement ps = connection.prepareStatement(
                            "SELECT action, actor, on_failure FROM flow_steps WHERE flow_id = ? ORDER BY step_order")) {
                        ps.setLong(1, id);
                        var srs = ps.executeQuery();
                        while (srs.next()) steps.add(new FlowStep(
                            srs.getString("action"), srs.getString("actor"), srs.getString("on_failure")));
                    }
                    flows.add(new Flow(rs.getString("name"), rs.getString("context"),
                        rs.getString("trigger_text"), rs.getString("outcome"), steps));
                }
            }
            return flows;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read flows", e);
        }
    }

    public List<Rule> readRules() {
        try (ResultSet rs = connection.createStatement().executeQuery(
                "SELECT name, description, context, entity, severity, invariant FROM rules ORDER BY severity, name")) {
            List<Rule> rules = new ArrayList<>();
            while (rs.next()) {
                rules.add(new Rule(rs.getString("name"), rs.getString("description"),
                    rs.getString("context"), rs.getString("entity"),
                    rs.getString("severity"), rs.getString("invariant")));
            }
            return rules;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read rules", e);
        }
    }

    public List<Term> readTerms() {
        try (ResultSet rs = connection.createStatement().executeQuery(
                "SELECT name, definition, context FROM terms ORDER BY name")) {
            List<Term> terms = new ArrayList<>();
            while (rs.next()) {
                terms.add(new Term(rs.getString("name"), rs.getString("definition"), rs.getString("context")));
            }
            return terms;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read terms", e);
        }
    }

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to close domain database", e);
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd code-navigator && ./gradlew test --tests "com.codenavigator.briefing.DomainDbReaderTest" -q`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
cd code-navigator && git add src/main/java/com/codenavigator/briefing/DomainDbReader.java src/main/java/com/codenavigator/cli/ProjectPaths.java src/test/java/com/codenavigator/briefing/DomainDbReaderTest.java
git commit -m "feat(code-navigator): add DomainDbReader for cross-DB briefing generation"
```

---

## Task 9: Briefing Generator

**Files:**
- Create: `code-navigator/src/main/java/com/codenavigator/briefing/BriefingGenerator.java`
- Test: `code-navigator/src/test/java/com/codenavigator/briefing/BriefingGeneratorTest.java`

- [ ] **Step 1: Write the failing test**

Create `BriefingGeneratorTest.java`:

```java
package com.codenavigator.briefing;

import com.codenavigator.graph.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BriefingGeneratorTest {

    @TempDir Path tempDir;
    private GraphStore store;
    private Path outputDir;

    @BeforeEach
    void setUp() {
        store = new GraphStore(tempDir.resolve("code.db"));
        outputDir = tempDir.resolve(".ai-briefing");

        // Build DDD test graph
        store.setConfig("tier", "DDD");
        store.saveNode(new Node("com.orders.api.OrderController", NodeType.CONTROLLER, "OrderController",
            "com.orders.api.OrderController", "OrderController.java", 1,
            "@RestController\n@RequestMapping(\"/api/orders\")\npublic class OrderController {\n    @PostMapping\n    public void create() {}", 0));
        store.saveNode(new Node("com.orders.commands.CreateOrderCommand", NodeType.COMMAND, "CreateOrderCommand",
            "com.orders.commands.CreateOrderCommand", "CreateOrderCommand.java", 1, "record CreateOrderCommand", 0));
        store.saveNode(new Node("com.orders.domain.Order", NodeType.AGGREGATE, "Order",
            "com.orders.domain.Order", "Order.java", 1, "class Order extends AggregateRoot", 0));
        store.saveNode(new Node("com.orders.events.OrderCreatedEvent", NodeType.DOMAIN_EVENT, "OrderCreatedEvent",
            "com.orders.events.OrderCreatedEvent", "OrderCreatedEvent.java", 1, "class OrderCreatedEvent", 0));
        store.saveNode(new Node("com.orders.projections.OrderListProjection", NodeType.PROJECTION_HANDLER, "OrderListProjection",
            "com.orders.projections.OrderListProjection", "OrderListProjection.java", 1, "class OrderListProjection", 0));
        store.saveNode(new Node("com.orders.views.OrderListView", NodeType.VIEW, "OrderListView",
            "com.orders.views.OrderListView", "OrderListView.java", 1, "class OrderListView", 0));

        store.saveEdge(new Edge("e1", EdgeType.DISPATCHES_COMMAND, "com.orders.api.OrderController", "com.orders.commands.CreateOrderCommand"));
        store.saveEdge(new Edge("e2", EdgeType.EMITS_EVENT, "com.orders.domain.Order", "com.orders.events.OrderCreatedEvent"));
        store.saveEdge(new Edge("e3", EdgeType.PROJECTS_EVENT, "com.orders.projections.OrderListProjection", "com.orders.events.OrderCreatedEvent"));
        store.saveEdge(new Edge("e4", EdgeType.UPDATES_VIEW, "com.orders.projections.OrderListProjection", "com.orders.views.OrderListView"));
    }

    @AfterEach
    void tearDown() { store.close(); }

    @Test
    void generatesOverviewFile() throws IOException {
        new BriefingGenerator(store, null).generate(outputDir);

        String content = Files.readString(outputDir.resolve("overview.md"));
        assertThat(content).contains("DDD");
        assertThat(content).contains("6 nodes");
        assertThat(content).contains("com.orders");
    }

    @Test
    void generatesEndpointsFile() throws IOException {
        new BriefingGenerator(store, null).generate(outputDir);

        String content = Files.readString(outputDir.resolve("endpoints.md"));
        assertThat(content).contains("OrderController");
        assertThat(content).contains("CreateOrderCommand");
    }

    @Test
    void generatesProjectionsFile() throws IOException {
        new BriefingGenerator(store, null).generate(outputDir);

        String content = Files.readString(outputDir.resolve("projections.md"));
        assertThat(content).contains("OrderListProjection");
        assertThat(content).contains("OrderCreatedEvent");
        assertThat(content).contains("OrderListView");
    }

    @Test
    void skipsDomainFilesWhenNoDomainDb() throws IOException {
        new BriefingGenerator(store, null).generate(outputDir);

        assertThat(outputDir.resolve("domain.md")).doesNotExist();
        assertThat(outputDir.resolve("flows.md")).doesNotExist();
        assertThat(outputDir.resolve("rules.md")).doesNotExist();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd code-navigator && ./gradlew test --tests "com.codenavigator.briefing.BriefingGeneratorTest" -q`
Expected: Compilation error — `BriefingGenerator` does not exist

- [ ] **Step 3: Implement BriefingGenerator**

Create `code-navigator/src/main/java/com/codenavigator/briefing/BriefingGenerator.java`:

```java
package com.codenavigator.briefing;

import com.codenavigator.graph.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class BriefingGenerator {

    private final GraphStore store;
    private final DomainDbReader domainReader; // nullable

    public BriefingGenerator(GraphStore store, DomainDbReader domainReader) {
        this.store = store;
        this.domainReader = domainReader;
    }

    public void generate(Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        writeFile(outputDir, "overview.md", generateOverview());
        writeFile(outputDir, "endpoints.md", generateEndpoints());
        writeFile(outputDir, "projections.md", generateProjections());
        if (domainReader != null) {
            writeFile(outputDir, "domain.md", generateDomain());
            writeFile(outputDir, "flows.md", generateFlows());
            writeFile(outputDir, "rules.md", generateRules());
        }
    }

    private String generateOverview() {
        var allNodes = store.getAllNodes();
        String tier = store.getConfig("tier");
        int edgeCount = store.getEdgeCount();
        int fileCount = store.getFileCount();

        var byPackage = allNodes.stream().collect(Collectors.groupingBy(
            n -> extractModulePackage(n.qualifiedName()), LinkedHashMap::new, Collectors.toList()));

        var sb = new StringBuilder();
        sb.append("# Project Briefing (generated ").append(LocalDate.now()).append(")\n");
        sb.append("Tier: ").append(tier != null ? tier : "unknown");
        sb.append(" | ").append(allNodes.size()).append(" nodes");
        sb.append(" | ").append(edgeCount).append(" edges");
        sb.append(" | ").append(fileCount).append(" files\n\n");

        sb.append("## Modules\n");
        for (var entry : byPackage.entrySet()) {
            var counts = entry.getValue().stream()
                .collect(Collectors.groupingBy(Node::type, Collectors.counting()));
            String summary = counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getValue() + " " + e.getKey().name().toLowerCase())
                .collect(Collectors.joining(", "));
            sb.append("- ").append(entry.getKey()).append(": ").append(summary).append("\n");
        }
        return sb.toString();
    }

    private String generateEndpoints() {
        var controllers = store.findNodesByType(NodeType.CONTROLLER);
        if (controllers.isEmpty()) return null;

        var sb = new StringBuilder();
        sb.append("# Endpoints\n\n");

        for (var ctrl : controllers) {
            String pkg = extractPackage(ctrl.qualifiedName());
            sb.append("## ").append(ctrl.name()).append(" (").append(pkg).append(")\n");

            // Parse HTTP annotations from code snippet
            String basePath = extractBasePath(ctrl.codeSnippet());

            // Get dispatched commands/queries
            var dispatched = store.findEdgesFrom(ctrl.id()).stream()
                .filter(e -> e.type() == EdgeType.DISPATCHES_COMMAND || e.type() == EdgeType.DISPATCHES_QUERY)
                .toList();

            if (!dispatched.isEmpty()) {
                for (var edge : dispatched) {
                    var target = store.findNodeById(edge.targetId());
                    String targetName = target.map(Node::name).orElse(edge.targetId());
                    sb.append("  ").append(basePath).append(" -> ").append(targetName).append("\n");
                }
            } else {
                sb.append("  ").append(basePath).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String generateProjections() {
        var projections = store.findNodesByType(NodeType.PROJECTION_HANDLER);
        if (projections.isEmpty()) return null;

        var sb = new StringBuilder();
        sb.append("# Projections\n\n");

        for (var proj : projections) {
            sb.append("## ").append(proj.name()).append("\n");

            // Events this projection handles (PROJECTS_EVENT edges point FROM projection TO event)
            var events = store.findEdgesFrom(proj.id()).stream()
                .filter(e -> e.type() == EdgeType.PROJECTS_EVENT)
                .map(e -> store.findNodeById(e.targetId()).map(Node::name).orElse(e.targetId()))
                .toList();
            // Also check incoming PROJECTS_EVENT (the edge direction can vary)
            var eventsIncoming = store.findEdgesTo(proj.id()).stream()
                .filter(e -> e.type() == EdgeType.PROJECTS_EVENT)
                .map(e -> store.findNodeById(e.sourceId()).map(Node::name).orElse(e.sourceId()))
                .toList();
            var allEvents = new ArrayList<>(events);
            allEvents.addAll(eventsIncoming);

            if (!allEvents.isEmpty()) {
                sb.append("  listens: ").append(String.join(", ", allEvents)).append("\n");
            }

            // Views this projection updates
            var views = store.findEdgesFrom(proj.id()).stream()
                .filter(e -> e.type() == EdgeType.UPDATES_VIEW)
                .map(e -> store.findNodeById(e.targetId()).map(Node::name).orElse(e.targetId()))
                .toList();
            if (!views.isEmpty()) {
                sb.append("  updates: ").append(String.join(", ", views)).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String generateDomain() {
        var sb = new StringBuilder();
        sb.append("# Domain Model\n\n");

        // Bounded contexts
        var contexts = domainReader.readContexts();
        if (!contexts.isEmpty()) {
            sb.append("## Bounded Contexts\n\n");
            for (var ctx : contexts) {
                sb.append("### ").append(ctx.name()).append("\n");
                if (ctx.description() != null) sb.append(ctx.description()).append("\n");
                if (!ctx.entities().isEmpty()) sb.append("Entities: ").append(String.join(", ", ctx.entities())).append("\n");
                for (var comm : ctx.communications()) {
                    sb.append("  -> ").append(comm.targetContext()).append(" (").append(comm.type()).append(", ").append(comm.via()).append(")\n");
                }
                sb.append("\n");
            }
        }

        // Key entities grouped by context and type
        var entities = domainReader.readEntities();
        if (!entities.isEmpty()) {
            var byContext = entities.stream().collect(Collectors.groupingBy(
                e -> e.context() != null ? e.context() : "unknown", LinkedHashMap::new, Collectors.toList()));
            sb.append("## Entities\n\n");
            for (var entry : byContext.entrySet()) {
                var byType = entry.getValue().stream().collect(Collectors.groupingBy(
                    e -> e.type() != null ? e.type() : "other"));
                for (var typeEntry : byType.entrySet()) {
                    String names = typeEntry.getValue().stream().map(DomainDbReader.Entity::name).collect(Collectors.joining(", "));
                    sb.append("- ").append(entry.getKey()).append(" ").append(typeEntry.getKey()).append("s: ").append(names).append("\n");
                }
            }
            sb.append("\n");
        }

        // Glossary
        var terms = domainReader.readTerms();
        if (!terms.isEmpty()) {
            sb.append("## Glossary\n");
            for (var term : terms) {
                sb.append("- **").append(term.name()).append("**: ").append(term.definition() != null ? term.definition() : "").append("\n");
            }
        }

        return sb.toString();
    }

    private String generateFlows() {
        var flows = domainReader.readFlows();
        if (flows.isEmpty()) return null;

        var sb = new StringBuilder();
        sb.append("# Business Flows\n\n");

        for (var flow : flows) {
            sb.append("## ").append(flow.name()).append("\n");
            if (flow.trigger() != null) sb.append("Trigger: ").append(flow.trigger()).append("\n");
            int i = 1;
            for (var step : flow.steps()) {
                sb.append(i++).append(". ");
                if (step.actor() != null) sb.append("[").append(step.actor()).append("] ");
                sb.append(step.action()).append("\n");
                if (step.onFailure() != null) sb.append("   Failure: ").append(step.onFailure()).append("\n");
            }
            if (flow.outcome() != null) sb.append("Outcome: ").append(flow.outcome()).append("\n");
            sb.append("\n");
        }
        return sb.toString();
    }

    private String generateRules() {
        var rules = domainReader.readRules();
        if (rules.isEmpty()) return null;

        var sb = new StringBuilder();
        sb.append("# Business Rules\n\n");

        var bySeverity = rules.stream().collect(Collectors.groupingBy(
            DomainDbReader.Rule::severity, LinkedHashMap::new, Collectors.toList()));

        for (var entry : bySeverity.entrySet()) {
            sb.append("## ").append(entry.getKey()).append("\n");
            for (var rule : entry.getValue()) {
                sb.append("- **").append(rule.name()).append("**: ");
                if (rule.description() != null) sb.append(rule.description());
                if (rule.entity() != null) sb.append(" (").append(rule.entity()).append(")");
                if (rule.invariant() != null) sb.append(" `").append(rule.invariant()).append("`");
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ---- Helpers ----

    private void writeFile(Path dir, String filename, String content) throws IOException {
        if (content != null && !content.isEmpty()) {
            Files.writeString(dir.resolve(filename), content);
        }
    }

    private static final Pattern BASE_PATH_PATTERN = Pattern.compile(
        "@RequestMapping\\([^)]*\"([^\"]+)\"");
    private static final Pattern METHOD_MAPPING_PATTERN = Pattern.compile(
        "@(Get|Post|Put|Delete|Patch)Mapping(?:\\(\"([^\"]+)\"\\))?");

    private static String extractBasePath(String codeSnippet) {
        if (codeSnippet == null) return "/";
        Matcher m = BASE_PATH_PATTERN.matcher(codeSnippet);
        return m.find() ? m.group(1) : "/";
    }

    private static String extractPackage(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot > 0 ? qualifiedName.substring(0, lastDot) : qualifiedName;
    }

    private static String extractModulePackage(String qualifiedName) {
        // Extract first 3 segments: com.example.orders
        String[] parts = qualifiedName.split("\\.");
        int depth = Math.min(parts.length - 1, 3);
        return String.join(".", Arrays.copyOf(parts, depth));
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd code-navigator && ./gradlew test --tests "com.codenavigator.briefing.BriefingGeneratorTest" -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd code-navigator && git add src/main/java/com/codenavigator/briefing/BriefingGenerator.java src/test/java/com/codenavigator/briefing/BriefingGeneratorTest.java
git commit -m "feat(code-navigator): add BriefingGenerator for compact index generation"
```

---

## Task 10: Briefing CLI Command + MCP Tool

**Files:**
- Create: `code-navigator/src/main/java/com/codenavigator/cli/BriefingCommand.java`
- Modify: `code-navigator/src/main/java/com/codenavigator/CodeNavigatorApplication.java`
- Modify: `code-navigator/src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java`

- [ ] **Step 1: Create BriefingCommand**

Create `code-navigator/src/main/java/com/codenavigator/cli/BriefingCommand.java`:

```java
package com.codenavigator.cli;

import com.codenavigator.briefing.BriefingGenerator;
import com.codenavigator.briefing.DomainDbReader;
import com.codenavigator.graph.GraphStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;

@Command(name = "briefing", description = "Generate compact codebase index for AI assistants")
public class BriefingCommand implements Runnable {

    @Parameters(index = "0", description = "Path to the project root")
    private Path projectPath;

    @Option(names = "--output", description = "Output directory (default: .ai-briefing)", defaultValue = ".ai-briefing")
    private String output;

    @Override
    public void run() {
        if (!ProjectPaths.hasIndex(projectPath)) {
            System.err.println("No index found. Run 'init' first.");
            return;
        }

        var outputDir = projectPath.resolve(output);
        DomainDbReader domainReader = null;

        try (var store = new GraphStore(ProjectPaths.graphDb(projectPath))) {
            if (ProjectPaths.hasDomainIndex(projectPath)) {
                domainReader = new DomainDbReader(ProjectPaths.domainDb(projectPath));
            }

            var generator = new BriefingGenerator(store, domainReader);
            generator.generate(outputDir);
            System.out.println("Briefing generated at " + outputDir);
        } catch (Exception e) {
            System.err.println("Briefing generation failed: " + e.getMessage());
        } finally {
            if (domainReader != null) domainReader.close();
        }
    }
}
```

- [ ] **Step 2: Register in application + MCP server**

In `CodeNavigatorApplication.java`, add `BriefingCommand.class` to subcommands:

```java
subcommands = {
    InitCommand.class,
    SyncCommand.class,
    ServeCommand.class,
    StatusCommand.class,
    MarkDirtyCommand.class,
    SyncIfDirtyCommand.class,
    InstallCommand.class,
    ExportCommand.class,
    BriefingCommand.class
}
```

In `CodeNavigatorMcpServer.java`, add the `cg_briefing` tool registration and handler:

```java
.toolCall(
    Tool.builder()
        .name("cg_briefing")
        .description("Generate compact codebase index files (.ai-briefing/) for AI assistants. Reads both code graph and domain knowledge.")
        .inputSchema(jsonSchema(
            withProjectPath(Map.of("output", propString("Output directory (default: .ai-briefing)"))),
            List.of()))
        .build(),
    (exchange, request) -> textResult(handleCgBriefing(request.arguments()))
)
```

Handler:

```java
String handleCgBriefing(Map<String, Object> args) {
    String projectPath = args.containsKey("projectPath") ? (String) args.get("projectPath") : null;
    String outputName = args.containsKey("output") ? (String) args.get("output") : ".ai-briefing";

    var root = projectPath != null ? java.nio.file.Path.of(projectPath)
        : java.nio.file.Path.of(System.getenv("CODE_NAVIGATOR_PROJECT") != null
            ? System.getenv("CODE_NAVIGATOR_PROJECT") : ".");
    var outputDir = root.resolve(outputName);

    DomainDbReader domainReader = null;
    try {
        if (ProjectPaths.hasDomainIndex(root)) {
            domainReader = new DomainDbReader(ProjectPaths.domainDb(root));
        }
        var generator = new BriefingGenerator(store, domainReader);
        generator.generate(outputDir);
        return "Briefing generated at " + outputDir.toAbsolutePath();
    } catch (Exception e) {
        return "Briefing generation failed: " + e.getMessage();
    } finally {
        if (domainReader != null) domainReader.close();
    }
}
```

Add the import at the top of `CodeNavigatorMcpServer.java`:

```java
import com.codenavigator.briefing.BriefingGenerator;
import com.codenavigator.briefing.DomainDbReader;
```

- [ ] **Step 3: Run all tests**

Run: `cd code-navigator && ./gradlew test -q`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
cd code-navigator && git add src/main/java/com/codenavigator/cli/BriefingCommand.java src/main/java/com/codenavigator/CodeNavigatorApplication.java src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java
git commit -m "feat(code-navigator): add briefing CLI command and cg_briefing MCP tool"
```

---

## Task 11: Update CLAUDE.md + Remove Unused MCP Reference

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update CLAUDE.md**

In `CLAUDE.md`, update the tool count and remove the `my-mcp` reference:

Change the code-navigator description from:
```
11 MCP tools for navigation and impact analysis.
```
to:
```
15 MCP tools for navigation, impact analysis, dead code detection, hotspot analysis, package dependencies, export, and compact index generation.
```

Remove this line from MCP Servers Available:
```
- **my-mcp** — configured externally, provides `activity_today`, `kb_list`, `activity_repos`
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md tool count, remove unused my-mcp reference"
```

---

## Task 12: Build and Verify

- [ ] **Step 1: Run full test suite**

Run: `cd code-navigator && ./gradlew test`
Expected: All tests pass

- [ ] **Step 2: Build shadow JAR**

Run: `cd code-navigator && ./gradlew shadowJar`
Expected: `build/libs/code-navigator-0.1.0.jar` produced

- [ ] **Step 3: Smoke test CLI commands**

Run: `java -jar code-navigator/build/libs/code-navigator-0.1.0.jar --help`
Expected: Output lists `briefing`, `export` alongside existing commands

- [ ] **Step 4: Commit plan**

```bash
git add docs/superpowers/2026-04-02-code-navigator-improvements-plan.md
git commit -m "docs: add code-navigator improvements implementation plan"
```
