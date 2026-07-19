# Library / Dependency Boundary Indexing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Index declared build-file dependencies as first-class graph nodes so that `cg_callees` and `cg_impact` show the library boundary a user's code touches, and a new `cg_deps` tool reports which dependencies are actually used.

**Architecture:** A new `DependencyParser` reads `build.gradle` / `pom.xml` from the project root (reusing `Files.readString` as ProjectDetector does, no network or jar decompilation) and returns a `List<Dependency>` (group/artifact/version). During edge extraction, `EdgeExtractor` checks unresolved import types against known dependency package-prefixes; when a match is found it mints a `NodeType.LIBRARY` node (id = fully-qualified type name, tagged with the artifact coordinate in `codeSnippet`) and saves a `EdgeType.USES_LIBRARY` edge. All new nodes and edges flow through the existing `store.saveNode` / `store.saveEdge` calls. `cg_deps` queries the store to list declared dependencies and, per dependency, the count of LIBRARY nodes whose `codeSnippet` tag matches that artifact.

**Tech Stack:** Java 21, Gradle shadowJar, JavaParser 3.26.4 (`cu.getImports()`, `StaticJavaParser.parse`), SQLite via `GraphStore` (existing `saveNode`/`saveEdge`/`findNodesByType`), JUnit 5 + AssertJ + `@TempDir`, MCP SDK 1.1.0 `Tool.builder()`.

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `src/main/java/com/codenavigator/graph/NodeType.java` | **Modify** | Add `LIBRARY` constant in a new `// External` section |
| `src/main/java/com/codenavigator/graph/EdgeType.java` | **Modify** | Add `USES_LIBRARY` constant in a new `// External` section |
| `src/main/java/com/codenavigator/graph/GraphTraversal.java` | **Modify** | Add `EdgeType.USES_LIBRARY` to the `CALL_EDGE_TYPES` set so `callees`/`callers` traverse into LIBRARY nodes (`impact` already follows all edges) |
| `src/main/java/com/codenavigator/indexer/Dependency.java` | **Create** | Value record `Dependency(String group, String artifact, String version)` with `coordinate()` helper |
| `src/main/java/com/codenavigator/indexer/DependencyParser.java` | **Create** | Parses `build.gradle` / `pom.xml` via regex; returns `List<Dependency>` |
| `src/main/java/com/codenavigator/indexer/EdgeExtractor.java` | **Modify** | Accept `List<Dependency>` via constructor overload; extend `resolveNode` to mint LIBRARY nodes on import-match; new private method `mintLibraryNodeIfMatches` |
| `src/main/java/com/codenavigator/indexer/ProjectIndexer.java` | **Modify** | Call `DependencyParser.parse(projectPath)` in `indexFull` and pass result to `EdgeExtractor`; also call in `indexIncremental` |
| `src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java` | **Modify** | Register `cg_deps` tool + add `handleCgDeps` method |
| `src/test/java/com/codenavigator/indexer/DependencyParserTest.java` | **Create** | Unit tests for Gradle and Maven parsing over fixture strings |
| `src/test/java/com/codenavigator/indexer/EdgeExtractorLibraryTest.java` | **Create** | Unit tests for LIBRARY node minting from Spring imports |
| `src/test/java/com/codenavigator/mcp/CgDepsHandlerTest.java` | **Create** | Handler test for `cg_deps` response content |
| `src/test/resources/sample-spring/build.gradle` | **Create** | Minimal fixture build file with Spring Boot dependency |

---

### Task 1: Add `NodeType.LIBRARY` and `EdgeType.USES_LIBRARY` enum constants

**Files:** `NodeType.java`, `EdgeType.java`

- [ ] **Failing test** — Create `src/test/java/com/codenavigator/graph/NodeTypeEdgeTypeTest.java`:

```java
package com.codenavigator.graph;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class NodeTypeEdgeTypeTest {

    @Test
    void nodeTypeLibraryExists() {
        assertThat(NodeType.LIBRARY).isNotNull();
    }

    @Test
    void edgeTypeUsesLibraryExists() {
        assertThat(EdgeType.USES_LIBRARY).isNotNull();
    }
}
```

- [ ] **Run (expect FAIL — compile error):**
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.graph.NodeTypeEdgeTypeTest" 2>&1 | tail -20
```
Expected: compilation failure — `LIBRARY` and `USES_LIBRARY` do not exist yet.

- [ ] **Implement** — Edit `src/main/java/com/codenavigator/graph/NodeType.java`:

```java
package com.codenavigator.graph;

public enum NodeType {
    // Tier 1: Generic Java
    CLASS, INTERFACE, RECORD, ENUM,
    CONTROLLER, SERVICE, REPOSITORY, ENTITY,
    CONFIGURATION, MAPPER,
    // Tier 2: Spring Boot
    VIEW, EVENT_PUBLISHER,
    // Tier 3: DDD/CQRS
    COMMAND, COMMAND_HANDLER, QUERY, QUERY_HANDLER,
    AGGREGATE, DOMAIN_EVENT, EVENT_APPLIER,
    PROJECTION_HANDLER, EVENT_LISTENER,
    // Frontend
    FE_SERVICE, FE_COMPONENT, FE_MODEL,
    // External
    LIBRARY
}
```

- [ ] **Implement** — Edit `src/main/java/com/codenavigator/graph/EdgeType.java`:

```java
package com.codenavigator.graph;

public enum EdgeType {
    // Tier 1: Generic
    INJECTS, CALLS_METHOD, IMPLEMENTS, EXTENDS, RETURNS_TYPE,
    // Tier 2: Spring
    READS_VIEW, UPDATES_VIEW, PUBLISHES_EVENT,
    // Tier 3: DDD
    DISPATCHES_COMMAND, DISPATCHES_QUERY, HANDLES,
    LOADS_AGGREGATE, EMITS_EVENT, APPLIES_EVENT,
    PROJECTS_EVENT, LISTENS_TO,
    // Frontend
    CALLS_API, USES_SERVICE,
    // External
    USES_LIBRARY
}
```

- [ ] **Run (expect PASS):**
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.graph.NodeTypeEdgeTypeTest" 2>&1 | tail -20
```

- [ ] **Commit:**
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && git add src/main/java/com/codenavigator/graph/NodeType.java src/main/java/com/codenavigator/graph/EdgeType.java src/test/java/com/codenavigator/graph/NodeTypeEdgeTypeTest.java && git commit -m "feat(graph): add NodeType.LIBRARY and EdgeType.USES_LIBRARY enum constants"
```

---

### Task 2: `Dependency` record + `DependencyParser` with Gradle and Maven support

**Files:** `Dependency.java` (new), `DependencyParser.java` (new), `DependencyParserTest.java` (new)

- [ ] **Failing test** — Create `src/test/java/com/codenavigator/indexer/DependencyParserTest.java`:

```java
package com.codenavigator.indexer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyParserTest {

    @TempDir
    Path projectRoot;

    private List<Dependency> parse() {
        return new DependencyParser().parse(projectRoot);
    }

    // ── build.gradle ──

    @Test
    void parsesGradleSingleQuoteShorthand() throws IOException {
        Files.writeString(projectRoot.resolve("build.gradle"), """
            plugins { id 'java' }
            dependencies {
                implementation 'org.springframework.boot:spring-boot-starter-web:3.2.0'
                testImplementation 'org.junit.jupiter:junit-jupiter:5.10.0'
            }
            """);

        var deps = parse();
        assertThat(deps).anyMatch(d ->
            d.group().equals("org.springframework.boot")
            && d.artifact().equals("spring-boot-starter-web")
            && d.version().equals("3.2.0"));
        assertThat(deps).anyMatch(d ->
            d.group().equals("org.junit.jupiter")
            && d.artifact().equals("junit-jupiter")
            && d.version().equals("5.10.0"));
    }

    @Test
    void parsesGradleDoubleQuoteShorthand() throws IOException {
        Files.writeString(projectRoot.resolve("build.gradle"), """
            dependencies {
                implementation "com.fasterxml.jackson.core:jackson-databind:2.18.2"
            }
            """);

        var deps = parse();
        assertThat(deps).anyMatch(d ->
            d.group().equals("com.fasterxml.jackson.core")
            && d.artifact().equals("jackson-databind"));
    }

    @Test
    void parsesGradleGroupNameVersionMap() throws IOException {
        Files.writeString(projectRoot.resolve("build.gradle"), """
            dependencies {
                implementation group: 'org.hibernate', name: 'hibernate-core', version: '6.4.0'
            }
            """);

        var deps = parse();
        assertThat(deps).anyMatch(d ->
            d.group().equals("org.hibernate")
            && d.artifact().equals("hibernate-core")
            && d.version().equals("6.4.0"));
    }

    @Test
    void parsesGradleKtsFile() throws IOException {
        Files.writeString(projectRoot.resolve("build.gradle.kts"), """
            dependencies {
                implementation("io.modelcontextprotocol.sdk:mcp:1.1.0")
            }
            """);

        var deps = parse();
        assertThat(deps).anyMatch(d ->
            d.group().equals("io.modelcontextprotocol.sdk")
            && d.artifact().equals("mcp"));
    }

    @Test
    void coordinateFormatIsGroupColonArtifact() throws IOException {
        Files.writeString(projectRoot.resolve("build.gradle"), """
            dependencies {
                implementation 'org.springframework.boot:spring-boot-starter-web:3.2.0'
            }
            """);

        var deps = parse();
        assertThat(deps.get(0).coordinate()).isEqualTo("org.springframework.boot:spring-boot-starter-web");
    }

    // ── pom.xml ──

    @Test
    void parsesMavenPom() throws IOException {
        Files.writeString(projectRoot.resolve("pom.xml"), """
            <project>
              <dependencies>
                <dependency>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-starter-data-jpa</artifactId>
                  <version>3.2.0</version>
                </dependency>
              </dependencies>
            </project>
            """);

        var deps = parse();
        assertThat(deps).anyMatch(d ->
            d.group().equals("org.springframework.boot")
            && d.artifact().equals("spring-boot-starter-data-jpa")
            && d.version().equals("3.2.0"));
    }

    @Test
    void returnsEmptyWhenNoBuildFile() {
        // projectRoot has no build file
        assertThat(parse()).isEmpty();
    }

    @Test
    void deduplicatesDependencies() throws IOException {
        Files.writeString(projectRoot.resolve("build.gradle"), """
            dependencies {
                implementation 'org.springframework.boot:spring-boot-starter-web:3.2.0'
                runtimeOnly 'org.springframework.boot:spring-boot-starter-web:3.2.0'
            }
            """);

        var deps = parse();
        long count = deps.stream()
            .filter(d -> d.artifact().equals("spring-boot-starter-web"))
            .count();
        assertThat(count).isEqualTo(1);
    }
}
```

- [ ] **Run (expect FAIL — compile error):**
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.indexer.DependencyParserTest" 2>&1 | tail -20
```

- [ ] **Implement** — Create `src/main/java/com/codenavigator/indexer/Dependency.java`:

```java
package com.codenavigator.indexer;

public record Dependency(String group, String artifact, String version) {

    /** Returns "group:artifact" — the stable identifier without version. */
    public String coordinate() {
        return group + ":" + artifact;
    }

    /**
     * Returns the root package prefix for this artifact, derived from the group id.
     * E.g. "org.springframework.boot:spring-boot-starter-web" -> "org.springframework"
     * Uses up to 2 segments for broad matching (avoids false positives from sub-artifacts).
     */
    public String packagePrefix() {
        String[] parts = group.split("\\.");
        if (parts.length <= 2) return group;
        return parts[0] + "." + parts[1];
    }
}
```

- [ ] **Implement** — Create `src/main/java/com/codenavigator/indexer/DependencyParser.java`:

```java
package com.codenavigator.indexer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses declared dependencies from build.gradle, build.gradle.kts, or pom.xml.
 * Purely offline — reads only the build file text, no network or jar inspection.
 */
public class DependencyParser {

    // Matches: implementation 'g:a:v'  OR  implementation "g:a:v"
    private static final Pattern GRADLE_SHORTHAND = Pattern.compile(
        """
        (?:implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|annotationProcessor)\
        \\s+['"]([\\.\\w-]+):([\\.\\w-]+)(?::([\\.\\w-]+))?['"]""");

    // Matches: group: 'g', name: 'a', version: 'v'  (any quote style)
    private static final Pattern GRADLE_MAP = Pattern.compile(
        "group:\\s*['\"]([^'\"]+)['\"].*?name:\\s*['\"]([^'\"]+)['\"](?:.*?version:\\s*['\"]([^'\"]+)['\"])?");

    // Maven pom.xml <dependency> block
    private static final Pattern MAVEN_GROUP    = Pattern.compile("<groupId>([^<]+)</groupId>");
    private static final Pattern MAVEN_ARTIFACT = Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern MAVEN_VERSION  = Pattern.compile("<version>([^<]+)</version>");
    private static final Pattern MAVEN_DEP_BLOCK = Pattern.compile(
        "<dependency>(.*?)</dependency>", Pattern.DOTALL);

    public List<Dependency> parse(Path projectRoot) {
        var seen = new LinkedHashSet<Dependency>();

        // Try Gradle files first
        for (String name : List.of("build.gradle", "build.gradle.kts")) {
            Path buildFile = projectRoot.resolve(name);
            if (Files.exists(buildFile)) {
                parseGradle(buildFile, seen);
                break; // stop after first found
            }
        }

        // Try Maven pom
        Path pomFile = projectRoot.resolve("pom.xml");
        if (Files.exists(pomFile)) {
            parseMaven(pomFile, seen);
        }

        return new ArrayList<>(seen);
    }

    private void parseGradle(Path buildFile, LinkedHashSet<Dependency> seen) {
        String content;
        try {
            content = Files.readString(buildFile);
        } catch (IOException e) {
            return;
        }

        // Shorthand: 'g:a:v' or "g:a:v"
        var m1 = GRADLE_SHORTHAND.matcher(content);
        while (m1.find()) {
            String group    = m1.group(1);
            String artifact = m1.group(2);
            String version  = m1.group(3) != null ? m1.group(3) : "";
            seen.add(new Dependency(group, artifact, version));
        }

        // Map form: group: 'g', name: 'a', version: 'v'
        var m2 = GRADLE_MAP.matcher(content);
        while (m2.find()) {
            String group    = m2.group(1);
            String artifact = m2.group(2);
            String version  = m2.group(3) != null ? m2.group(3) : "";
            seen.add(new Dependency(group, artifact, version));
        }
    }

    private void parseMaven(Path pomFile, LinkedHashSet<Dependency> seen) {
        String content;
        try {
            content = Files.readString(pomFile);
        } catch (IOException e) {
            return;
        }

        var blockMatcher = MAVEN_DEP_BLOCK.matcher(content);
        while (blockMatcher.find()) {
            String block = blockMatcher.group(1);
            var gm = MAVEN_GROUP.matcher(block);
            var am = MAVEN_ARTIFACT.matcher(block);
            var vm = MAVEN_VERSION.matcher(block);
            if (gm.find() && am.find()) {
                String group    = gm.group(1).trim();
                String artifact = am.group(1).trim();
                String version  = vm.find() ? vm.group(1).trim() : "";
                seen.add(new Dependency(group, artifact, version));
            }
        }
    }
}
```

- [ ] **Run (expect PASS):**
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.indexer.DependencyParserTest" 2>&1 | tail -30
```

- [ ] **Commit:**
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && git add src/main/java/com/codenavigator/indexer/Dependency.java src/main/java/com/codenavigator/indexer/DependencyParser.java src/test/java/com/codenavigator/indexer/DependencyParserTest.java && git commit -m "feat(indexer): add DependencyParser for Gradle and Maven build files"
```

---

### Task 3: Library-node minting in `EdgeExtractor`

**Files:** `EdgeExtractor.java` (modify), `EdgeExtractorLibraryTest.java` (new)

- [ ] **Failing test** — Create `src/test/java/com/codenavigator/indexer/EdgeExtractorLibraryTest.java`:

```java
package com.codenavigator.indexer;

import com.codenavigator.graph.*;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeExtractorLibraryTest {

    @TempDir
    Path tempDir;

    private GraphStore store;

    @BeforeAll
    static void configureParser() {
        StaticJavaParser.getParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
    }

    @BeforeEach
    void setUp() {
        store = new GraphStore(tempDir.resolve("test.db"));
    }

    private List<Dependency> springDeps() {
        return List.of(new Dependency("org.springframework.boot", "spring-boot-starter-web", "3.2.0"));
    }

    private Node controllerNode(String fqn) {
        String name = fqn.substring(fqn.lastIndexOf('.') + 1);
        var node = new Node(fqn, NodeType.CONTROLLER, name, fqn, "test.java", 1, "", 0L);
        store.saveNode(node);
        return node;
    }

    @Test
    void mintsLibraryNodeForUnresolvedSpringImport() {
        var source = controllerNode("com.example.OrderController");

        String code = """
            package com.example;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            class OrderController {}
            """;
        var cu = StaticJavaParser.parse(code);
        new EdgeExtractor(Project.CRUD, store, springDeps()).extract(cu, source);

        var libraryNodes = store.findNodesByType(NodeType.LIBRARY);
        assertThat(libraryNodes).anyMatch(n ->
            n.id().equals("org.springframework.web.bind.annotation.RestController"));
    }

    @Test
    void mintsUsesLibraryEdgeFromControllerToLibraryNode() {
        var source = controllerNode("com.example.OrderController");

        String code = """
            package com.example;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            class OrderController {}
            """;
        var cu = StaticJavaParser.parse(code);
        new EdgeExtractor(Project.CRUD, store, springDeps()).extract(cu, source);

        var edges = store.findEdgesFrom(source.id());
        assertThat(edges).anyMatch(e ->
            e.type() == EdgeType.USES_LIBRARY
            && e.targetId().equals("org.springframework.web.bind.annotation.RestController"));
    }

    @Test
    void libraryNodeSnippetContainsArtifactCoordinate() {
        var source = controllerNode("com.example.OrderController");

        String code = """
            package com.example;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            class OrderController {}
            """;
        var cu = StaticJavaParser.parse(code);
        new EdgeExtractor(Project.CRUD, store, springDeps()).extract(cu, source);

        var libraryNodes = store.findNodesByType(NodeType.LIBRARY);
        assertThat(libraryNodes).anyMatch(n ->
            n.codeSnippet() != null
            && n.codeSnippet().contains("org.springframework.boot:spring-boot-starter-web"));
    }

    @Test
    void doesNotMintLibraryNodeForInternalImport() {
        var source = controllerNode("com.example.OrderController");
        store.saveNode(new Node("com.example.OrderService", NodeType.SERVICE, "OrderService",
            "com.example.OrderService", "OrderService.java", 1, "", 0L));

        String code = """
            package com.example;
            import com.example.OrderService;
            class OrderController {
                private OrderService service;
            }
            """;
        var cu = StaticJavaParser.parse(code);
        new EdgeExtractor(Project.CRUD, store, springDeps()).extract(cu, source);

        assertThat(store.findNodesByType(NodeType.LIBRARY)).isEmpty();
    }

    @Test
    void deduplicatesLibraryNodeAcrossMultipleSources() {
        var source1 = controllerNode("com.example.OrderController");
        var source2 = new Node("com.example.PaymentController", NodeType.CONTROLLER,
            "PaymentController", "com.example.PaymentController", "test.java", 1, "", 0L);
        store.saveNode(source2);

        String code1 = """
            package com.example;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            class OrderController {}
            """;
        String code2 = """
            package com.example;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            class PaymentController {}
            """;
        var extractor = new EdgeExtractor(Project.CRUD, store, springDeps());
        extractor.extract(StaticJavaParser.parse(code1), source1);
        extractor.extract(StaticJavaParser.parse(code2), source2);

        long libraryNodeCount = store.findNodesByType(NodeType.LIBRARY).stream()
            .filter(n -> n.id().equals("org.springframework.web.bind.annotation.RestController"))
            .count();
        assertThat(libraryNodeCount).isEqualTo(1);
    }

    @Test
    void emptyDependencyListMintsNoLibraryNodes() {
        var source = controllerNode("com.example.OrderController");

        String code = """
            package com.example;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            class OrderController {}
            """;
        var cu = StaticJavaParser.parse(code);
        new EdgeExtractor(Project.CRUD, store, List.of()).extract(cu, source);

        assertThat(store.findNodesByType(NodeType.LIBRARY)).isEmpty();
    }
}
```

- [ ] **Run (expect FAIL — compile error: no 3-arg constructor):**
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.indexer.EdgeExtractorLibraryTest" 2>&1 | tail -20
```

- [ ] **Implement** — Modify `EdgeExtractor.java`. Add:
  1. A `List<Dependency> dependencies` field.
  2. A new constructor overload `EdgeExtractor(Project, GraphStore, List<Dependency>)`.
  3. Keep the existing 2-arg constructor delegating to the new one with `List.of()`.
  4. A private `extractUsesLibrary(CompilationUnit, Node, List<Edge>)` method that walks `cu.getImports()`, and for each import not matching any node, checks package-prefix against `dependencies.stream().anyMatch(d -> importFqn.startsWith(d.packagePrefix()))`. If matched, calls `mintLibraryNodeIfMatches`.
  5. A private `mintLibraryNodeIfMatches(String importFqn, Dependency dep, Node source, List<Edge> edges)` that calls `store.findNodeById(importFqn)` first (to avoid duplicates), then creates a `new Node(importFqn, NodeType.LIBRARY, simpleName, importFqn, "", 0, dep.coordinate(), 0L)` and saves it, then adds a `USES_LIBRARY` edge.
  6. A call to `extractUsesLibrary(cu, sourceNode, edges)` at the end of the `extract` method.

Full replacement of the constructor and relevant section:

```java
package com.codenavigator.indexer;

import com.codenavigator.graph.*;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class EdgeExtractor {

    private final Project tier;
    private final GraphStore store;
    private final List<Dependency> dependencies;

    /** Backward-compatible constructor: no library dependency awareness. */
    public EdgeExtractor(Project tier, GraphStore store) {
        this(tier, store, List.of());
    }

    /** Full constructor with library dependency list for boundary detection. */
    public EdgeExtractor(Project tier, GraphStore store, List<Dependency> dependencies) {
        this.tier = tier;
        this.store = store;
        this.dependencies = dependencies;
    }

    public List<Edge> extract(CompilationUnit cu, Node sourceNode) {
        var edges = new ArrayList<Edge>();
        var packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(decl -> {
            var declName = decl.getNameAsString();
            var qualifiedName = packageName.isEmpty() ? declName : packageName + "." + declName;
            if (!qualifiedName.equals(sourceNode.qualifiedName())) return;

            // Project 1: always active
            extractInjects(decl, sourceNode, packageName, edges);
            extractCallsMethod(decl, sourceNode, packageName, edges);
            extractImplements(decl, sourceNode, packageName, edges);
            extractExtends(decl, sourceNode, packageName, edges);
            extractReturnsType(decl, sourceNode, packageName, edges);

            // Project 2: Spring or DDD
            if (tier == Project.CRUD || tier == Project.DDD) {
                extractUpdatesView(decl, sourceNode, packageName, edges);
                extractReadsView(decl, sourceNode, packageName, edges);
            }

            // Project 3: DDD only
            if (tier == Project.DDD) {
                extractDispatchesCommand(decl, sourceNode, packageName, edges);
                extractDispatchesQuery(decl, sourceNode, packageName, edges);
                extractHandles(decl, sourceNode, packageName, edges);
                extractLoadsAggregate(decl, sourceNode, packageName, edges);
                extractEmitsEvent(decl, sourceNode, packageName, edges);
                extractAppliesEvent(decl, sourceNode, packageName, edges);
                extractProjectsEvent(decl, sourceNode, packageName, edges);
                extractListensTo(decl, sourceNode, packageName, edges);
            }
        });

        // Library boundary detection (all tiers, only when dependencies are supplied)
        if (!dependencies.isEmpty()) {
            extractUsesLibrary(cu, sourceNode, edges);
        }

        return edges;
    }
```

Add the following private methods just before the `// ── Node resolution ──` comment in the existing file:

```java
    // ── Library boundary ──

    private void extractUsesLibrary(CompilationUnit cu, Node source, List<Edge> edges) {
        cu.getImports().forEach(importDecl -> {
            if (importDecl.isAsterisk()) return;
            String importFqn = importDecl.getNameAsString();

            // Skip if this type is already an internal node
            if (store.findNodeById(importFqn).isPresent()) return;
            var simpleParts = importFqn.split("\\.");
            var simpleName = simpleParts[simpleParts.length - 1];
            if (!store.findNodesByName(simpleName).isEmpty()) return;

            // Find matching declared dependency
            dependencies.stream()
                .filter(dep -> importFqn.startsWith(dep.packagePrefix()))
                .findFirst()
                .ifPresent(dep -> mintLibraryNode(importFqn, dep, source, edges));
        });
    }

    private void mintLibraryNode(String importFqn, Dependency dep, Node source, List<Edge> edges) {
        // Idempotent: reuse existing LIBRARY node if already minted by another source
        if (store.findNodeById(importFqn).isEmpty()) {
            var simpleParts = importFqn.split("\\.");
            var simpleName = simpleParts[simpleParts.length - 1];
            var libraryNode = new Node(
                importFqn,
                NodeType.LIBRARY,
                simpleName,
                importFqn,
                "",
                0,
                dep.coordinate(),   // codeSnippet stores the artifact coordinate tag
                0L
            );
            store.saveNode(libraryNode);
        }
        edges.add(new Edge(UUID.randomUUID().toString(), EdgeType.USES_LIBRARY, source.id(), importFqn));
    }
```

- [ ] **Run (expect PASS):**
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.indexer.EdgeExtractorLibraryTest" 2>&1 | tail -30
```

- [ ] **Verify existing EdgeExtractor tests still pass:**
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.indexer.EdgeExtractorTest" 2>&1 | tail -20
```

- [ ] **Commit:**
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && git add src/main/java/com/codenavigator/indexer/EdgeExtractor.java src/test/java/com/codenavigator/indexer/EdgeExtractorLibraryTest.java && git commit -m "feat(indexer): mint LIBRARY nodes and USES_LIBRARY edges from unresolved imports"
```

---

### Task 4: Wire `DependencyParser` into `ProjectIndexer.indexFull` and `indexIncremental`

**Files:** `ProjectIndexer.java` (modify), `ProjectIndexerIntegrationTest.java` (modify), `src/test/resources/sample-spring/build.gradle` (create)

- [ ] **Fixture** — Create `src/test/resources/sample-spring/build.gradle`:

```groovy
plugins {
    id 'org.springframework.boot' version '3.2.0'
    id 'java'
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web:3.2.0'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa:3.2.0'
}
```

- [ ] **Failing test** — Add the following test method to `ProjectIndexerIntegrationTest.java`:

```java
    @Test
    void indexFullCreatesLibraryNodesForSpringDependencies() {
        indexer.indexFull(Paths.get("src/test/resources/sample-spring"));
        var libraryNodes = store.findNodesByType(NodeType.LIBRARY);
        // sample-spring has Spring annotations (@RestController, @Service, @Repository, @Entity)
        // At least one library node should be minted for org.springframework imports
        assertThat(libraryNodes).isNotEmpty();
        assertThat(libraryNodes).allMatch(n -> n.id().startsWith("org.springframework"));
    }
```

Also add `import com.codenavigator.graph.NodeType;` if not already present (it is — see existing file line 5).

- [ ] **Run (expect FAIL — 0 library nodes because DependencyParser not yet wired):**
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.indexer.ProjectIndexerIntegrationTest.indexFullCreatesLibraryNodesForSpringDependencies" 2>&1 | tail -20
```

- [ ] **Implement** — Modify `ProjectIndexer.java` to add a `DependencyParser` field and wire it into `indexFull` and `indexIncremental`. The changes are:

1. Add field: `private final DependencyParser dependencyParser;`
2. Initialize in constructor: `this.dependencyParser = new DependencyParser();`
3. In `indexFull`, after step 1 (detect project type), add:
   ```java
   List<Dependency> dependencies = dependencyParser.parse(projectPath);
   ```
4. Change the `EdgeExtractor` instantiation on the next line to:
   ```java
   var edgeExtractor = new EdgeExtractor(project, store, dependencies);
   ```
5. In `indexIncremental`, do the same: add `List<Dependency> dependencies = dependencyParser.parse(projectPath);` after the `store.setConfig("tier", ...)` line, and change `EdgeExtractor` instantiation to `new EdgeExtractor(project, store, dependencies)`.

Full updated constructor and `indexFull` opening (lines 25–45 replacement):

```java
    public ProjectIndexer(GraphStore store) {
        this.store = store;
        this.projectDetector = new ProjectDetector();
        this.tsIndexer = new TypeScriptIndexer();
        this.methodExtractor = new MethodExtractor();
        this.dependencyParser = new DependencyParser();
    }

    public void indexFull(Path projectPath) {
        configureParser();

        // 1. Detect project type
        Project project = projectDetector.detect(projectPath);
        store.setConfig("tier", project.name());

        // 1b. Parse declared dependencies for library boundary indexing
        List<Dependency> dependencies = dependencyParser.parse(projectPath);

        var nodeExtractor = new NodeExtractor(project);
        var edgeExtractor = new EdgeExtractor(project, store, dependencies);
```

And update the field declarations at the top of the class (after `methodExtractor`):

```java
    private final DependencyParser dependencyParser;
```

For `indexIncremental`, update lines around the `EdgeExtractor` instantiation:

```java
        List<Dependency> dependencies = dependencyParser.parse(projectPath);

        var nodeExtractor = new NodeExtractor(project);
        var edgeExtractor = new EdgeExtractor(project, store, dependencies);
```

Also add `import java.util.List;` if not already present (it is — see line 14 of original).

- [ ] **Run (expect PASS):**
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.indexer.ProjectIndexerIntegrationTest" 2>&1 | tail -30
```

- [ ] **Commit:**
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && git add src/main/java/com/codenavigator/indexer/ProjectIndexer.java src/test/java/com/codenavigator/indexer/ProjectIndexerIntegrationTest.java src/test/resources/sample-spring/build.gradle && git commit -m "feat(indexer): wire DependencyParser into ProjectIndexer indexFull and indexIncremental"
```

---

### Task 5: `cg_deps` MCP tool

**Files:** `CodeNavigatorMcpServer.java` (modify), `CgDepsHandlerTest.java` (new)

- [ ] **Failing test** — Create `src/test/java/com/codenavigator/mcp/CgDepsHandlerTest.java`:

```java
package com.codenavigator.mcp;

import com.codenavigator.domain.DomainSqliteStore;
import com.codenavigator.domain.DomainToolHandlers;
import com.codenavigator.graph.*;
import com.codenavigator.search.SearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CgDepsHandlerTest {

    @TempDir Path tempDir;
    private GraphStore store;
    private CodeNavigatorMcpServer mcpServer;

    @BeforeEach
    void setUp() {
        store = new GraphStore(tempDir.resolve("test.db"));
        var traversal = new GraphTraversal(store);
        var search = new SearchService(store, traversal);
        var domainHandlers = new DomainToolHandlers(new DomainSqliteStore(tempDir.resolve("domain.db")), null);
        mcpServer = new CodeNavigatorMcpServer(store, traversal, search, domainHandlers);
        buildLibraryGraph();
    }

    @AfterEach
    void tearDown() { store.close(); }

    private void buildLibraryGraph() {
        // Two internal nodes
        store.saveNode(new Node("com.example.OrderController", NodeType.CONTROLLER, "OrderController",
            "com.example.OrderController", "Ctrl.java", 1, "", 0));
        store.saveNode(new Node("com.example.PaymentController", NodeType.CONTROLLER, "PaymentController",
            "com.example.PaymentController", "Pay.java", 1, "", 0));

        // Two LIBRARY nodes from the same artifact
        store.saveNode(new Node(
            "org.springframework.web.bind.annotation.RestController",
            NodeType.LIBRARY, "RestController",
            "org.springframework.web.bind.annotation.RestController",
            "", 0, "org.springframework.boot:spring-boot-starter-web", 0));
        store.saveNode(new Node(
            "org.springframework.web.bind.annotation.RequestMapping",
            NodeType.LIBRARY, "RequestMapping",
            "org.springframework.web.bind.annotation.RequestMapping",
            "", 0, "org.springframework.boot:spring-boot-starter-web", 0));

        // One LIBRARY node from a different artifact
        store.saveNode(new Node(
            "org.hibernate.annotations.Entity",
            NodeType.LIBRARY, "Entity",
            "org.hibernate.annotations.Entity",
            "", 0, "org.hibernate:hibernate-core", 0));

        // USES_LIBRARY edges
        store.saveEdge(new Edge("e1", EdgeType.USES_LIBRARY,
            "com.example.OrderController", "org.springframework.web.bind.annotation.RestController"));
        store.saveEdge(new Edge("e2", EdgeType.USES_LIBRARY,
            "com.example.OrderController", "org.springframework.web.bind.annotation.RequestMapping"));
        store.saveEdge(new Edge("e3", EdgeType.USES_LIBRARY,
            "com.example.PaymentController", "org.springframework.web.bind.annotation.RestController"));
        store.saveEdge(new Edge("e4", EdgeType.USES_LIBRARY,
            "com.example.PaymentController", "org.hibernate.annotations.Entity"));

        // Store declared dependencies in config (JSON array of "g:a:v")
        store.setConfig("dependencies",
            "[\"org.springframework.boot:spring-boot-starter-web:3.2.0\",\"org.hibernate:hibernate-core:6.4.0\"]");
    }

    @Test
    void cgDepsListsDeclaredDependencies() {
        var result = mcpServer.handleCgDeps(Map.of());
        assertThat(result).contains("spring-boot-starter-web");
        assertThat(result).contains("hibernate-core");
    }

    @Test
    void cgDepsShowsUsageCountPerDependency() {
        var result = mcpServer.handleCgDeps(Map.of());
        // spring-boot-starter-web: 2 LIBRARY nodes (RestController, RequestMapping)
        assertThat(result).contains("spring-boot-starter-web");
        assertThat(result).contains("2");
        // hibernate-core: 1 LIBRARY node
        assertThat(result).contains("hibernate-core");
        assertThat(result).contains("1");
    }

    @Test
    void cgDepsReturnsMessageWhenNoDependenciesStored() {
        // Use a fresh store/server with NO "dependencies" config key set.
        // (Do NOT call setConfig("dependencies", null) — the project_config.value
        // column is TEXT NOT NULL, so a null bind throws a SQLiteException.
        // getConfig returns null for an absent key, which is the case under test.)
        var emptyStore = new GraphStore(tempDir.resolve("empty.db"));
        var emptyTraversal = new GraphTraversal(emptyStore);
        var emptySearch = new SearchService(emptyStore, emptyTraversal);
        var emptyDomain = new DomainToolHandlers(
            new DomainSqliteStore(tempDir.resolve("empty-domain.db")), null);
        var emptyServer = new CodeNavigatorMcpServer(emptyStore, emptyTraversal, emptySearch, emptyDomain);

        var result = emptyServer.handleCgDeps(Map.of());
        assertThat(result).contains("No dependency");
        emptyStore.close();
    }
}
```

> **Handler contract:** `handleCgDeps` must treat a `null` return from `store.getConfig("dependencies")` as "no data" and return a message containing `"No dependency"`. The implementation below already does this via the `if (depsJson == null || depsJson.isBlank())` guard.

- [ ] **Run (expect FAIL — `handleCgDeps` does not exist):**
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.mcp.CgDepsHandlerTest" 2>&1 | tail -20
```

- [ ] **Implement** — In `CodeNavigatorMcpServer.java`:

1. Register the tool in `start()` before the `dm_context` call block, following the same `Tool.builder()` pattern:

```java
            .toolCall(
                Tool.builder()
                    .name("cg_deps")
                    .description("List declared build dependencies and the count of internal nodes that touch each one (USES_LIBRARY edges). Shows what the project actually uses from each dependency.")
                    .inputSchema(jsonSchema(withProjectPath(Map.of()), List.of()))
                    .build(),
                (exchange, request) -> textResult(handleCgDeps(request.arguments()))
            )
```

2. Add the handler method (package-visible for tests):

```java
    String handleCgDeps(Map<String, Object> args) {
        String depsJson = store.getConfig("dependencies");
        if (depsJson == null || depsJson.isBlank()) {
            return "No dependency information stored. Re-index the project to populate dependency data.";
        }

        // Parse the stored JSON array "g:a:v" strings
        List<String> coordsWithVersion = new ArrayList<>();
        String stripped = depsJson.strip();
        if (stripped.startsWith("[")) stripped = stripped.substring(1);
        if (stripped.endsWith("]")) stripped = stripped.substring(0, stripped.length() - 1);
        for (String token : stripped.split(",")) {
            String s = token.strip().replaceAll("^\"|\"$", "");
            if (!s.isBlank()) coordsWithVersion.add(s);
        }

        if (coordsWithVersion.isEmpty()) {
            return "No dependency information stored. Re-index the project to populate dependency data.";
        }

        // Count LIBRARY nodes per artifact coordinate (codeSnippet stores "g:a")
        var libraryNodes = store.findNodesByType(NodeType.LIBRARY);
        Map<String, Long> countByCoordinate = libraryNodes.stream()
            .filter(n -> n.codeSnippet() != null && !n.codeSnippet().isBlank())
            .collect(Collectors.groupingBy(Node::codeSnippet, Collectors.counting()));

        var sb = new StringBuilder();
        sb.append("## Declared Dependencies\n\n");
        sb.append(coordsWithVersion.size()).append(" declared dependenc")
          .append(coordsWithVersion.size() == 1 ? "y" : "ies").append(":\n\n");

        for (String gav : coordsWithVersion) {
            // gav is "g:a:v" — strip version for display and lookup
            String[] parts = gav.split(":");
            String coordinate = parts[0] + ":" + parts[1];  // g:a
            String version    = parts.length > 2 ? parts[2] : "?";
            long usedCount    = countByCoordinate.getOrDefault(coordinate, 0L);
            sb.append("- **").append(coordinate).append("** (").append(version).append(") — ")
              .append(usedCount).append(" type(s) used\n");
        }

        long totalLibraryNodes = libraryNodes.size();
        if (totalLibraryNodes > 0) {
            sb.append("\n").append(totalLibraryNodes)
              .append(" total library type(s) indexed across all dependencies.\n");
        }

        return sb.toString();
    }
```

3. Wire `dependencies` config persistence into `ProjectIndexer.indexFull` by adding, after `store.setConfig("tier", ...)`:

In `ProjectIndexer.java` `indexFull`, after parsing dependencies:

```java
        // Persist declared dependencies for cg_deps tool
        String depsJson = dependencies.stream()
            .map(d -> "\"" + d.coordinate() + ":" + d.version() + "\"")
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        store.setConfig("dependencies", depsJson);
```

- [ ] **Run (expect PASS):**
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.mcp.CgDepsHandlerTest" 2>&1 | tail -30
```

- [ ] **Run all existing MCP server tests to ensure no regression:**
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.mcp.CodeNavigatorMcpServerTest" 2>&1 | tail -20
```

- [ ] **Commit:**
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && git add src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java src/main/java/com/codenavigator/indexer/ProjectIndexer.java src/test/java/com/codenavigator/mcp/CgDepsHandlerTest.java && git commit -m "feat(mcp): add cg_deps tool listing declared dependencies with usage counts"
```

---

### Task 6: Make `cg_callees` and `cg_impact` surface `LIBRARY` nodes

**Files:**
- Modify: `src/main/java/com/codenavigator/graph/GraphTraversal.java` (the `CALL_EDGE_TYPES` set, lines 7-9)
- Test: `src/test/java/com/codenavigator/mcp/CodeNavigatorMcpServerTest.java` (add two new test methods)

**Why a code change is required:** `GraphTraversal.impact()` follows *all* outgoing/incoming edges, so it surfaces `USES_LIBRARY` edges with no change. But `GraphTraversal.callees()`/`callers()` filter edges to the `CALL_EDGE_TYPES` set (`GraphTraversal.java:7-9`, applied at line 92). `USES_LIBRARY` is not in that set, so `cg_callees` will *not* show LIBRARY nodes until we add it. This task adds the failing test, then makes it pass by extending the set.

- [ ] **Step 1: Write the failing test** — Add the following two test methods to the `CodeNavigatorMcpServerTest` class:

```java
    // ── Library boundary visibility ──

    @Test
    void cgCalleesIncludesLibraryNodes() {
        // Add a LIBRARY node and a USES_LIBRARY edge from OrderController
        store.saveNode(new Node(
            "org.springframework.web.bind.annotation.RestController",
            NodeType.LIBRARY, "RestController",
            "org.springframework.web.bind.annotation.RestController",
            "", 0, "org.springframework.boot:spring-boot-starter-web", 0));
        store.saveEdge(new Edge("lib-e1", EdgeType.USES_LIBRARY,
            "com.OrderController", "org.springframework.web.bind.annotation.RestController"));

        var result = mcpServer.handleCgCallees(Map.of("symbol", "OrderController", "depth", 1));
        assertThat(result).contains("RestController");
        assertThat(result).contains("LIBRARY");
    }

    @Test
    void cgImpactIncludesLibraryNodes() {
        // Add a LIBRARY node and USES_LIBRARY edges from two different nodes
        store.saveNode(new Node(
            "org.springframework.web.bind.annotation.RequestMapping",
            NodeType.LIBRARY, "RequestMapping",
            "org.springframework.web.bind.annotation.RequestMapping",
            "", 0, "org.springframework.boot:spring-boot-starter-web", 0));
        store.saveEdge(new Edge("lib-e2", EdgeType.USES_LIBRARY,
            "com.OrderController", "org.springframework.web.bind.annotation.RequestMapping"));

        var result = mcpServer.handleCgImpact(Map.of("symbol", "OrderController", "depth", 1));
        assertThat(result).contains("RequestMapping");
    }
```

- [ ] **Step 2: Run to verify the split** — `cgImpactIncludesLibraryNodes` PASSES (impact follows all edges) but `cgCalleesIncludesLibraryNodes` FAILS:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.mcp.CodeNavigatorMcpServerTest.cgCalleesIncludesLibraryNodes" --tests "com.codenavigator.mcp.CodeNavigatorMcpServerTest.cgImpactIncludesLibraryNodes" 2>&1 | tail -25
```
Expected: `cgImpactIncludesLibraryNodes` PASS; `cgCalleesIncludesLibraryNodes` FAIL with an AssertJ error — the result does not contain `"RestController"` because `callees` filters out `USES_LIBRARY` edges.

- [ ] **Step 3: Add `USES_LIBRARY` to the call-edge set** — In `src/main/java/com/codenavigator/graph/GraphTraversal.java`, the existing set (lines 7-9) reads:

```java
    private static final Set<EdgeType> CALL_EDGE_TYPES = Set.of(
        EdgeType.CALLS_METHOD, EdgeType.INJECTS, EdgeType.CALLS_API, EdgeType.USES_SERVICE,
        EdgeType.DISPATCHES_COMMAND, EdgeType.DISPATCHES_QUERY);
```

Change it to include `USES_LIBRARY` so `callees`/`callers` traverse the library boundary:

```java
    private static final Set<EdgeType> CALL_EDGE_TYPES = Set.of(
        EdgeType.CALLS_METHOD, EdgeType.INJECTS, EdgeType.CALLS_API, EdgeType.USES_SERVICE,
        EdgeType.DISPATCHES_COMMAND, EdgeType.DISPATCHES_QUERY, EdgeType.USES_LIBRARY);
```

- [ ] **Step 4: Run to verify both pass:**
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.mcp.CodeNavigatorMcpServerTest.cgCalleesIncludesLibraryNodes" --tests "com.codenavigator.mcp.CodeNavigatorMcpServerTest.cgImpactIncludesLibraryNodes" 2>&1 | tail -20
```
Expected: both PASS.

- [ ] **Step 5: Run full test suite to confirm no regressions** (adding `USES_LIBRARY` to `CALL_EDGE_TYPES` only widens traversal for graphs that contain such edges — existing fixtures have none, so prior `callees`/`callers` tests are unaffected):
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test 2>&1 | tail -40
```

- [ ] **Commit:**
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && git add src/main/java/com/codenavigator/graph/GraphTraversal.java src/test/java/com/codenavigator/mcp/CodeNavigatorMcpServerTest.java && git commit -m "feat(graph): traverse USES_LIBRARY in callees so cg_callees/cg_impact show LIBRARY nodes"
```

---

## Self-Review

| Spec Item | Task(s) |
|-----------|---------|
| `NodeType.LIBRARY` constant added following existing enum style | T1 |
| `EdgeType.USES_LIBRARY` constant added following existing enum style | T1 |
| `DependencyParser` parses Gradle shorthand (`'g:a:v'` / `"g:a:v"`) | T2 |
| `DependencyParser` parses Gradle map form (`group:, name:, version:`) | T2 |
| `DependencyParser` parses `build.gradle.kts` | T2 |
| `DependencyParser` parses Maven `pom.xml` `<dependency>` blocks | T2 |
| `Dependency.coordinate()` returns `g:a` without version | T2 |
| `Dependency.packagePrefix()` returns first-2-segment group prefix | T2 |
| Library node minted with `NodeType.LIBRARY` id = FQN of import | T3 |
| Library node `codeSnippet` tagged with artifact `g:a` coordinate | T3 |
| `USES_LIBRARY` edge from user node to library node | T3 |
| Only unresolved imports that match a declared dependency prefix are minted | T3 |
| Idempotent: second source file importing same type reuses existing node | T3 |
| No library nodes when dependency list is empty (backward compat) | T3 |
| Existing 2-arg `EdgeExtractor` constructor preserved | T3 |
| `DependencyParser.parse` wired into `ProjectIndexer.indexFull` | T4 |
| `DependencyParser.parse` wired into `ProjectIndexer.indexIncremental` | T4 |
| Declared dependencies persisted to `project_config` as JSON for `cg_deps` | T5 |
| `cg_deps` tool registered in MCP server | T5 |
| `cg_deps` lists all declared dependencies with `g:a (version)` | T5 |
| `cg_deps` shows count of LIBRARY nodes per artifact | T5 |
| `cg_deps` graceful message when no dependency data stored | T5 |
| `cg_callees` traverses `USES_LIBRARY` edges and shows LIBRARY nodes (requires adding `USES_LIBRARY` to `GraphTraversal.CALL_EDGE_TYPES`) | T6 |
| `cg_impact` traverses `USES_LIBRARY` edges and shows LIBRARY nodes (no code change — `impact` already follows all edges) | T6 |
| Fully offline — no network calls, no jar decompilation | all (DependencyParser uses only `Files.readString`) |
| Deterministic — all derived from source + build file text | all |
