# Merge domain-navigator into code-navigator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merge domain-navigator's 6 MCP tools, domain store, extractor, and search into code-navigator as a single unified MCP server, then remove the domain-navigator project.

**Architecture:** Domain knowledge becomes a subsystem within code-navigator. The domain SQLite store (`DomainSqliteStore`) moves into a `domain` package under `com.codenavigator`. The `CodeNavigatorMcpServer` gains the 6 `dm_*` tools. `DomainDbReader` is deleted — `BriefingGenerator` uses `DomainSqliteStore` directly. The `ServeCommand` bootstraps domain extraction on startup (same as domain-navigator's `ServeCommand` did). `DomainToolHandlers` includes staleness checking — if the code graph DB changes while the server is running, domain data is re-extracted automatically. The domain-navigator project directory is removed entirely.

**Design decisions:**
- **Multi-project `DOMAIN_NAVIGATOR_PROJECTS` env var is dropped.** Code-navigator already supports `projectPath` per tool call for secondary projects. Domain stores for secondary projects can be added later if needed.
- **`project` filter parameter on `dm_context` is dropped.** Replaced by code-navigator's existing `projectPath` mechanism.
- **`DomainNavigatorApplication` and `cli/ServeCommand` are not migrated** — their bootstrap logic is absorbed by code-navigator's `ServeCommand`.
- **No `build.gradle` dependency changes needed** — `jackson-databind` is already present in code-navigator.

**Tech Stack:** Java 21, SQLite JDBC, MCP SDK 1.1.0, Jackson, PicoCLI, Gradle Shadow

---

## File Map

### New files (migrated from domain-navigator, re-packaged)

- `code-navigator/src/main/java/com/codenavigator/domain/model/BoundedContext.java`
- `code-navigator/src/main/java/com/codenavigator/domain/model/BusinessFlow.java`
- `code-navigator/src/main/java/com/codenavigator/domain/model/BusinessRule.java`
- `code-navigator/src/main/java/com/codenavigator/domain/model/ContextCommunication.java`
- `code-navigator/src/main/java/com/codenavigator/domain/model/DomainEntity.java`
- `code-navigator/src/main/java/com/codenavigator/domain/model/DomainModel.java`
- `code-navigator/src/main/java/com/codenavigator/domain/model/EntityField.java`
- `code-navigator/src/main/java/com/codenavigator/domain/model/FlowStep.java`
- `code-navigator/src/main/java/com/codenavigator/domain/model/GlossaryTerm.java`
- `code-navigator/src/main/java/com/codenavigator/domain/model/Severity.java`
- `code-navigator/src/main/java/com/codenavigator/domain/DomainSqliteStore.java`
- `code-navigator/src/main/java/com/codenavigator/domain/DomainSearchService.java`
- `code-navigator/src/main/java/com/codenavigator/domain/CodeNavigatorExtractor.java`
- `code-navigator/src/main/java/com/codenavigator/domain/DomainToolHandlers.java`
- `code-navigator/src/test/java/com/codenavigator/domain/DomainSqliteStoreTest.java`
- `code-navigator/src/test/java/com/codenavigator/domain/DomainSearchServiceTest.java`
- `code-navigator/src/test/java/com/codenavigator/domain/CodeNavigatorExtractorTest.java`
- `code-navigator/src/test/java/com/codenavigator/domain/DomainToolHandlersTest.java`

### Modified files

- `code-navigator/src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java` — add 6 `dm_*` tool registrations delegating to `DomainToolHandlers`
- `code-navigator/src/main/java/com/codenavigator/cli/ServeCommand.java` — bootstrap domain extraction on startup
- `code-navigator/src/main/java/com/codenavigator/cli/ProjectPaths.java` — already has `domainDb()` and `hasDomainIndex()`, no changes needed
- `code-navigator/src/main/java/com/codenavigator/briefing/BriefingGenerator.java` — replace `DomainDbReader` with `DomainSqliteStore`
### Deleted files

- `code-navigator/src/main/java/com/codenavigator/briefing/DomainDbReader.java`
- `code-navigator/src/test/java/com/codenavigator/briefing/DomainDbReaderTest.java`
- `domain-navigator/` — entire project directory (after all tests pass)

### Updated config

- `jars/build-all.sh` — remove domain-navigator build step
- `CLAUDE.md` — update project description (two MCPs, not three)

---

## Task 1: Migrate domain model records

**Files:**
- Create: `code-navigator/src/main/java/com/codenavigator/domain/model/BoundedContext.java`
- Create: `code-navigator/src/main/java/com/codenavigator/domain/model/BusinessFlow.java`
- Create: `code-navigator/src/main/java/com/codenavigator/domain/model/BusinessRule.java`
- Create: `code-navigator/src/main/java/com/codenavigator/domain/model/ContextCommunication.java`
- Create: `code-navigator/src/main/java/com/codenavigator/domain/model/DomainEntity.java`
- Create: `code-navigator/src/main/java/com/codenavigator/domain/model/DomainModel.java`
- Create: `code-navigator/src/main/java/com/codenavigator/domain/model/EntityField.java`
- Create: `code-navigator/src/main/java/com/codenavigator/domain/model/FlowStep.java`
- Create: `code-navigator/src/main/java/com/codenavigator/domain/model/GlossaryTerm.java`
- Create: `code-navigator/src/main/java/com/codenavigator/domain/model/Severity.java`

- [ ] **Step 1: Copy model records from domain-navigator, change package**

Copy each file from `domain-navigator/src/main/java/com/domainnavigator/model/` to `code-navigator/src/main/java/com/codenavigator/domain/model/`. Change package declaration from `com.domainnavigator.model` to `com.codenavigator.domain.model`. All record bodies stay identical.

Example for `BoundedContext.java`:
```java
package com.codenavigator.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BoundedContext(
    String name,
    String description,
    String owner,
    List<String> entities,
    @JsonProperty("communicates_with") List<ContextCommunication> communicatesWith
) {}
```

Do the same for all 10 model files. Pure package rename, no logic changes.

- [ ] **Step 2: Verify compilation**

Run: `cd /home/kamil/Documents/Project/My/Mcp/code-navigator && ./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (model records have no dependencies on anything else)

- [ ] **Step 3: Commit**

```bash
git add code-navigator/src/main/java/com/codenavigator/domain/model/
git commit -m "feat: migrate domain model records from domain-navigator to code-navigator"
```

---

## Task 2: Migrate DomainSqliteStore with tests

**Files:**
- Create: `code-navigator/src/main/java/com/codenavigator/domain/DomainSqliteStore.java`
- Create: `code-navigator/src/test/java/com/codenavigator/domain/DomainSqliteStoreTest.java`

- [ ] **Step 1: Copy DomainSqliteStore, change package and imports**

Copy `domain-navigator/src/main/java/com/domainnavigator/store/DomainSqliteStore.java` to `code-navigator/src/main/java/com/codenavigator/domain/DomainSqliteStore.java`.

Changes:
- Package: `com.codenavigator.domain`
- Import: `com.codenavigator.domain.model.*` (instead of `com.domainnavigator.model.*`)
- Everything else stays identical

- [ ] **Step 2: Copy DomainSqliteStoreTest, change package and imports**

Copy `domain-navigator/src/test/java/com/domainnavigator/store/DomainSqliteStoreTest.java` to `code-navigator/src/test/java/com/codenavigator/domain/DomainSqliteStoreTest.java`.

Changes:
- Package: `com.codenavigator.domain`
- Import: `com.codenavigator.domain.*` and `com.codenavigator.domain.model.*`

- [ ] **Step 3: Run tests**

Run: `cd /home/kamil/Documents/Project/My/Mcp/code-navigator && ./gradlew test --tests "com.codenavigator.domain.DomainSqliteStoreTest" 2>&1 | tail -10`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add code-navigator/src/main/java/com/codenavigator/domain/DomainSqliteStore.java
git add code-navigator/src/test/java/com/codenavigator/domain/DomainSqliteStoreTest.java
git commit -m "feat: migrate DomainSqliteStore from domain-navigator to code-navigator"
```

---

## Task 3: Migrate DomainSearchService with tests

**Files:**
- Create: `code-navigator/src/main/java/com/codenavigator/domain/DomainSearchService.java`
- Create: `code-navigator/src/test/java/com/codenavigator/domain/DomainSearchServiceTest.java`

- [ ] **Step 1: Copy DomainSearchService, change package and imports**

Copy `domain-navigator/src/main/java/com/domainnavigator/search/DomainSearchService.java` to `code-navigator/src/main/java/com/codenavigator/domain/DomainSearchService.java`.

Changes:
- Package: `com.codenavigator.domain`
- Import: `com.codenavigator.domain.DomainSqliteStore` (instead of `com.domainnavigator.store.DomainSqliteStore`)

- [ ] **Step 2: Copy test, change package and imports**

Copy `domain-navigator/src/test/java/com/domainnavigator/search/DomainSearchServiceTest.java` to `code-navigator/src/test/java/com/codenavigator/domain/DomainSearchServiceTest.java`.

Changes:
- Package: `com.codenavigator.domain`
- Import: `com.codenavigator.domain.*`

- [ ] **Step 3: Run tests**

Run: `cd /home/kamil/Documents/Project/My/Mcp/code-navigator && ./gradlew test --tests "com.codenavigator.domain.DomainSearchServiceTest" 2>&1 | tail -10`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add code-navigator/src/main/java/com/codenavigator/domain/DomainSearchService.java
git add code-navigator/src/test/java/com/codenavigator/domain/DomainSearchServiceTest.java
git commit -m "feat: migrate DomainSearchService from domain-navigator to code-navigator"
```

---

## Task 4: Migrate CodeNavigatorExtractor with tests

**Files:**
- Create: `code-navigator/src/main/java/com/codenavigator/domain/CodeNavigatorExtractor.java`
- Create: `code-navigator/src/test/java/com/codenavigator/domain/CodeNavigatorExtractorTest.java`

- [ ] **Step 1: Copy CodeNavigatorExtractor, change package and imports**

Copy `domain-navigator/src/main/java/com/domainnavigator/extract/CodeNavigatorExtractor.java` to `code-navigator/src/main/java/com/codenavigator/domain/CodeNavigatorExtractor.java`.

Changes:
- Package: `com.codenavigator.domain`
- Import: `com.codenavigator.domain.model.*` (instead of `com.domainnavigator.model.*`)
- Import: `com.codenavigator.domain.DomainSqliteStore` (instead of `com.domainnavigator.store.DomainSqliteStore`)

- [ ] **Step 2: Copy test, change package and imports**

Copy `domain-navigator/src/test/java/com/domainnavigator/extract/CodeNavigatorExtractorTest.java` to `code-navigator/src/test/java/com/codenavigator/domain/CodeNavigatorExtractorTest.java`.

Changes:
- Package: `com.codenavigator.domain`
- Import: `com.codenavigator.domain.*` and `com.codenavigator.domain.model.*`

- [ ] **Step 3: Run tests**

Run: `cd /home/kamil/Documents/Project/My/Mcp/code-navigator && ./gradlew test --tests "com.codenavigator.domain.CodeNavigatorExtractorTest" 2>&1 | tail -10`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add code-navigator/src/main/java/com/codenavigator/domain/CodeNavigatorExtractor.java
git add code-navigator/src/test/java/com/codenavigator/domain/CodeNavigatorExtractorTest.java
git commit -m "feat: migrate CodeNavigatorExtractor from domain-navigator to code-navigator"
```

---

## Task 5: Create DomainToolHandlers with tests

**Files:**
- Create: `code-navigator/src/main/java/com/codenavigator/domain/DomainToolHandlers.java`
- Create: `code-navigator/src/test/java/com/codenavigator/domain/DomainToolHandlersTest.java`

- [ ] **Step 1: Write DomainToolHandlersTest**

Adapt `domain-navigator/src/test/java/com/domainnavigator/mcp/DomainNavigatorServerTest.java` into a test for `DomainToolHandlers`. The key difference: instead of testing a full MCP server, test the handler methods directly (they return `String`).

Create `code-navigator/src/test/java/com/codenavigator/domain/DomainToolHandlersTest.java`:

```java
package com.codenavigator.domain;

import com.codenavigator.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class DomainToolHandlersTest {

    @TempDir Path tempDir;
    private DomainToolHandlers handlers;

    @BeforeEach
    void setUp() {
        var store = new DomainSqliteStore(tempDir.resolve("domain.db"));
        populateTestData(store);
        handlers = new DomainToolHandlers(store, null);
    }

    @Test
    void handleDmContext_returnsAllContexts() {
        var result = handlers.handleDmContext(Map.of());
        assertThat(result).contains("Bounded Contexts");
        assertThat(result).contains("Order Management");
    }

    @Test
    void handleDmGlossary_filtersByTerm() {
        var result = handlers.handleDmGlossary(Map.of("term", "Order"));
        assertThat(result).contains("Glossary");
        assertThat(result).contains("Order");
    }

    @Test
    void handleDmFlow_returnsFlows() {
        var result = handlers.handleDmFlow(Map.of());
        assertThat(result).contains("Business Flows");
        assertThat(result).contains("Place Order");
    }

    @Test
    void handleDmRules_filtersBySeverity() {
        var result = handlers.handleDmRules(Map.of("severity", "ERROR"));
        assertThat(result).contains("Business Rules");
    }

    @Test
    void handleDmEntity_filtersByType() {
        var result = handlers.handleDmEntity(Map.of("type", "aggregate"));
        assertThat(result).contains("Domain Entities");
        assertThat(result).contains("Order");
    }

    @Test
    void handleDmExplain_searchesDomain() {
        var result = handlers.handleDmExplain(Map.of("question", "What is an order?"));
        assertThat(result).containsAnyOf("Order", "No relevant");
    }

    private void populateTestData(DomainSqliteStore store) {
        store.saveContext(new BoundedContext("Order Management", "Manages orders",
            "Team A", List.of("Order"), List.of()));
        store.saveTerm(new GlossaryTerm("Order", "A customer purchase",
            List.of("Purchase"), "Order Management", List.of("Order"), null));
        store.saveFlow(new BusinessFlow("Place Order", "Customer places order",
            "Order Management", "Customer clicks buy",
            List.of(new FlowStep("Validate cart", "System", null)),
            "Order created"));
        store.saveRule(new BusinessRule("Order requires items", "An order must have items",
            "Order Management", "Order", Severity.ERROR, "items.size() > 0"));
        store.saveEntity(new DomainEntity("Order", "Order Management", "aggregate",
            "Core order aggregate", List.of(new EntityField("id", "UUID", "identifier", null)),
            "com.example.Order"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/kamil/Documents/Project/My/Mcp/code-navigator && ./gradlew test --tests "com.codenavigator.domain.DomainToolHandlersTest" 2>&1 | tail -10`
Expected: FAIL — `DomainToolHandlers` class does not exist yet

- [ ] **Step 3: Write DomainToolHandlers**

Create `code-navigator/src/main/java/com/codenavigator/domain/DomainToolHandlers.java`.

This extracts the 6 `dm_*` handler methods from `DomainNavigatorServer`, simplified to work with a single store (no multi-project, since code-navigator already handles that via `projectPath`):

```java
package com.codenavigator.domain;

import com.codenavigator.domain.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class DomainToolHandlers {

    private final DomainSqliteStore store;
    private final Path codeGraphDb;

    public DomainToolHandlers(DomainSqliteStore store, Path codeGraphDb) {
        this.store = store;
        this.codeGraphDb = codeGraphDb;
    }

    public DomainSqliteStore store() {
        return store;
    }

    /** Re-extract domain data if code graph has been re-indexed since last extraction. */
    void syncIfStale() {
        if (codeGraphDb == null || !Files.exists(codeGraphDb)) return;
        try {
            long lastExtracted = store.getConfigLong("code_graph_modified");
            long currentModified = Files.getLastModifiedTime(codeGraphDb).toMillis();
            if (currentModified > lastExtracted) {
                System.err.println("Code graph changed, re-extracting domain...");
                new CodeNavigatorExtractor().extract(store, codeGraphDb);
            }
        } catch (IOException e) {
            System.err.println("Warning: could not check code-navigator staleness");
        }
    }

    String handleDmContext(Map<String, Object> args) {
        syncIfStale();
        var sb = new StringBuilder();
        sb.append("## Bounded Contexts\n\n");
        for (var ctx : store.getAllContexts()) {
            sb.append("### ").append(ctx.name()).append("\n");
            sb.append(ctx.description()).append("\n");
            if (ctx.owner() != null) sb.append("**Owner:** ").append(ctx.owner()).append("\n");
            if (ctx.entities() != null && !ctx.entities().isEmpty()) {
                sb.append("**Entities:** ").append(String.join(", ", ctx.entities())).append("\n");
            }
            if (ctx.communicatesWith() != null && !ctx.communicatesWith().isEmpty()) {
                sb.append("**Communicates with:**\n");
                for (var comm : ctx.communicatesWith()) {
                    sb.append("  - ").append(comm.context())
                        .append(" (").append(comm.type()).append(")")
                        .append(comm.via() != null ? " via " + comm.via() : "")
                        .append("\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    String handleDmGlossary(Map<String, Object> args) {
        syncIfStale();
        var termFilter = (String) args.get("term");
        var contextFilter = (String) args.get("context");

        var sb = new StringBuilder();
        sb.append("## Glossary\n\n");
        for (var term : store.getAllTerms()) {
            if (termFilter != null && !term.name().equalsIgnoreCase(termFilter)) continue;
            if (contextFilter != null && !contextFilter.equalsIgnoreCase(term.context())) continue;
            sb.append("### ").append(term.name()).append("\n");
            sb.append(term.definition()).append("\n");
            if (term.aliases() != null && !term.aliases().isEmpty()) {
                sb.append("**Aliases:** ").append(String.join(", ", term.aliases())).append("\n");
            }
            if (term.context() != null) sb.append("**Context:** ").append(term.context()).append("\n");
            if (term.relatedEntities() != null && !term.relatedEntities().isEmpty()) {
                sb.append("**Related entities:** ").append(String.join(", ", term.relatedEntities())).append("\n");
            }
            if (term.businessRule() != null) sb.append("**Business rule:** ").append(term.businessRule()).append("\n");
            sb.append("\n");
        }
        return sb.toString();
    }

    String handleDmFlow(Map<String, Object> args) {
        syncIfStale();
        var nameFilter = (String) args.get("name");
        var contextFilter = (String) args.get("context");

        var sb = new StringBuilder();
        sb.append("## Business Flows\n\n");
        for (var flow : store.getAllFlows()) {
            if (nameFilter != null && !flow.name().equalsIgnoreCase(nameFilter)) continue;
            if (contextFilter != null && !contextFilter.equalsIgnoreCase(flow.context())) continue;
            sb.append("### ").append(flow.name()).append("\n");
            sb.append(flow.description()).append("\n");
            if (flow.context() != null) sb.append("**Context:** ").append(flow.context()).append("\n");
            sb.append("**Trigger:** ").append(flow.trigger()).append("\n\n");
            sb.append("**Steps:**\n");
            for (int i = 0; i < flow.steps().size(); i++) {
                var step = flow.steps().get(i);
                sb.append(i + 1).append(". **").append(step.actor()).append("**: ").append(step.action()).append("\n");
                if (step.onFailure() != null) {
                    sb.append("   - On failure: ").append(step.onFailure()).append("\n");
                }
            }
            sb.append("\n**Outcome:** ").append(flow.outcome()).append("\n\n");
        }
        return sb.toString();
    }

    String handleDmRules(Map<String, Object> args) {
        syncIfStale();
        var entityFilter = (String) args.get("entity");
        var contextFilter = (String) args.get("context");
        var severityFilter = args.get("severity") != null ? ((String) args.get("severity")).toUpperCase() : null;

        var sb = new StringBuilder();
        sb.append("## Business Rules\n\n");
        for (var rule : store.getAllRules()) {
            if (entityFilter != null && !entityFilter.equalsIgnoreCase(rule.entity())) continue;
            if (contextFilter != null && !contextFilter.equalsIgnoreCase(rule.context())) continue;
            if (severityFilter != null && !severityFilter.equals(rule.severity().name())) continue;
            sb.append("### ").append(rule.name()).append(" [").append(rule.severity()).append("]\n");
            sb.append(rule.description()).append("\n");
            if (rule.context() != null) sb.append("**Context:** ").append(rule.context()).append("\n");
            if (rule.entity() != null) sb.append("**Entity:** ").append(rule.entity()).append("\n");
            sb.append("**Invariant:** `").append(rule.invariant()).append("`\n\n");
        }
        return sb.toString();
    }

    String handleDmEntity(Map<String, Object> args) {
        syncIfStale();
        var nameFilter = (String) args.get("name");
        var contextFilter = (String) args.get("context");
        var typeFilter = (String) args.get("type");

        var sb = new StringBuilder();
        sb.append("## Domain Entities\n\n");
        for (var entity : store.getAllEntities()) {
            if (nameFilter != null && !entity.name().equalsIgnoreCase(nameFilter)) continue;
            if (contextFilter != null && !contextFilter.equalsIgnoreCase(entity.context())) continue;
            if (typeFilter != null && !typeFilter.equalsIgnoreCase(entity.type())) continue;
            sb.append("### ").append(entity.name()).append(" (").append(entity.type()).append(")\n");
            sb.append(entity.description()).append("\n");
            if (entity.context() != null) sb.append("**Context:** ").append(entity.context()).append("\n");
            if (entity.codeMapping() != null) sb.append("**Code:** `").append(entity.codeMapping()).append("`\n");
            if (entity.fields() != null && !entity.fields().isEmpty()) {
                sb.append("**Fields:**\n");
                for (var field : entity.fields()) {
                    sb.append("  - `").append(field.name()).append("` (").append(field.type()).append("): ")
                        .append(field.description());
                    if (field.transitions() != null) {
                        sb.append(" — transitions: ").append(field.transitions());
                    }
                    sb.append("\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    String handleDmExplain(Map<String, Object> args) {
        syncIfStale();
        var question = (String) args.get("question");
        var search = new DomainSearchService(store);
        var result = search.explain(question);
        if (result.contains("No relevant domain knowledge found")) {
            return "No relevant domain knowledge found for: " + question;
        }
        return result;
    }
}
```

- [ ] **Step 4: Run tests**

Run: `cd /home/kamil/Documents/Project/My/Mcp/code-navigator && ./gradlew test --tests "com.codenavigator.domain.DomainToolHandlersTest" 2>&1 | tail -10`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add code-navigator/src/main/java/com/codenavigator/domain/DomainToolHandlers.java
git add code-navigator/src/test/java/com/codenavigator/domain/DomainToolHandlersTest.java
git commit -m "feat: add DomainToolHandlers for dm_* MCP tools in code-navigator"
```

---

## Task 6: Wire dm_* tools into CodeNavigatorMcpServer

**Files:**
- Modify: `code-navigator/src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java`
- Modify: `code-navigator/src/main/java/com/codenavigator/cli/ServeCommand.java`

- [ ] **Step 1: Add DomainToolHandlers field to CodeNavigatorMcpServer**

In `CodeNavigatorMcpServer.java`, add a new field and update the constructor:

```java
// Add import
import com.codenavigator.domain.DomainToolHandlers;

// Add field
private final DomainToolHandlers domainHandlers;

// Update constructor
public CodeNavigatorMcpServer(GraphStore store, GraphTraversal traversal,
                               SearchService searchService, DomainToolHandlers domainHandlers) {
    this.store = store;
    this.traversal = traversal;
    this.searchService = searchService;
    this.domainHandlers = domainHandlers;
}
```

- [ ] **Step 2: Register 6 dm_* tools in the start() method**

In `start()`, add 6 `.toolCall(...)` blocks after the existing `cg_briefing` tool and before `.build()`. Copy the tool definitions exactly from `DomainNavigatorServer.java` lines 38-91, but delegate to `domainHandlers`:

```java
.toolCall(
    Tool.builder().name("dm_context")
        .description("List bounded contexts — shows domains, their responsibilities, owners, and how they communicate.")
        .inputSchema(jsonSchema(Map.of(), List.of()))
        .build(),
    (exchange, request) -> textResult(domainHandlers.handleDmContext(request.arguments())))
.toolCall(
    Tool.builder().name("dm_glossary")
        .description("Query business terms — definitions, aliases, related entities.")
        .inputSchema(jsonSchema(
            Map.of("term", propString("Term name to look up (optional)"),
                   "context", propString("Filter by bounded context (optional)")),
            List.of()))
        .build(),
    (exchange, request) -> textResult(domainHandlers.handleDmGlossary(request.arguments())))
.toolCall(
    Tool.builder().name("dm_flow")
        .description("Trace business flows — step-by-step processes with actors and failure handling.")
        .inputSchema(jsonSchema(
            Map.of("name", propString("Flow name (optional)"),
                   "context", propString("Filter by bounded context (optional)")),
            List.of()))
        .build(),
    (exchange, request) -> textResult(domainHandlers.handleDmFlow(request.arguments())))
.toolCall(
    Tool.builder().name("dm_rules")
        .description("Query business rules — invariants with severity levels.")
        .inputSchema(jsonSchema(
            Map.of("entity", propString("Filter by entity name (optional)"),
                   "context", propString("Filter by bounded context (optional)"),
                   "severity", propString("Filter by severity: ERROR, WARNING, INFO (optional)")),
            List.of()))
        .build(),
    (exchange, request) -> textResult(domainHandlers.handleDmRules(request.arguments())))
.toolCall(
    Tool.builder().name("dm_entity")
        .description("Describe domain entities — fields, state transitions, code mappings.")
        .inputSchema(jsonSchema(
            Map.of("name", propString("Entity name (optional)"),
                   "context", propString("Filter by bounded context (optional)"),
                   "type", propString("Filter by type: aggregate, entity, value-object, event, command (optional)")),
            List.of()))
        .build(),
    (exchange, request) -> textResult(domainHandlers.handleDmEntity(request.arguments())))
.toolCall(
    Tool.builder().name("dm_explain")
        .description("Answer a business question by searching across all domain knowledge.")
        .inputSchema(jsonSchema(
            Map.of("question", propString("Business question to answer")),
            List.of("question")))
        .build(),
    (exchange, request) -> textResult(domainHandlers.handleDmExplain(request.arguments())))
```

- [ ] **Step 3: Update ServeCommand to bootstrap domain**

Modify `code-navigator/src/main/java/com/codenavigator/cli/ServeCommand.java` to create domain store and auto-extract:

```java
package com.codenavigator.cli;

import com.codenavigator.domain.CodeNavigatorExtractor;
import com.codenavigator.domain.DomainSqliteStore;
import com.codenavigator.domain.DomainToolHandlers;
import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.GraphTraversal;
import com.codenavigator.mcp.CodeNavigatorMcpServer;
import com.codenavigator.search.SearchService;
import picocli.CommandLine.Command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Command(name = "serve", description = "Start MCP server (stdio)")
public class ServeCommand implements Runnable {

    @Override
    public void run() {
        var projectPath = System.getenv("CODE_NAVIGATOR_PROJECT");
        var root = Path.of(projectPath != null ? projectPath : ".");

        if (!ProjectPaths.hasIndex(root)) {
            System.err.println("No index found at " + ProjectPaths.graphDb(root) + ". Run 'init' first.");
            return;
        }

        var store = new GraphStore(ProjectPaths.graphDb(root));
        var traversal = new GraphTraversal(store);
        var search = new SearchService(store, traversal);

        // Bootstrap domain extraction
        var domainDb = ProjectPaths.domainDb(root);
        try {
            Files.createDirectories(domainDb.getParent());
        } catch (IOException e) {
            System.err.println("Warning: cannot create domain directory");
        }
        var domainStore = new DomainSqliteStore(domainDb);
        if (domainStore.isEmpty() && ProjectPaths.hasIndex(root)) {
            System.err.println("Auto-extracting domain knowledge...");
            new CodeNavigatorExtractor().extract(domainStore, ProjectPaths.graphDb(root));
        }
        var domainHandlers = new DomainToolHandlers(domainStore, ProjectPaths.graphDb(root));

        var mcpServer = new CodeNavigatorMcpServer(store, traversal, search, domainHandlers);

        System.err.println("code-navigator MCP server started (stdio) — 22 tools");
        mcpServer.start();
    }
}
```

- [ ] **Step 4: Verify compilation**

Run: `cd /home/kamil/Documents/Project/My/Mcp/code-navigator && ./gradlew compileJava 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Fix CodeNavigatorMcpServerTest if it uses the old constructor**

The existing test at `code-navigator/src/test/java/com/codenavigator/mcp/CodeNavigatorMcpServerTest.java` passes 3 args to the constructor. Update it to pass 4 args by adding a `DomainToolHandlers` with a temp store. Read the test first to determine the exact change needed.

- [ ] **Step 6: Run all tests**

Run: `cd /home/kamil/Documents/Project/My/Mcp/code-navigator && ./gradlew test 2>&1 | tail -20`
Expected: All tests PASS

- [ ] **Step 7: Commit**

```bash
git add code-navigator/src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java
git add code-navigator/src/main/java/com/codenavigator/cli/ServeCommand.java
git add code-navigator/src/test/java/com/codenavigator/mcp/CodeNavigatorMcpServerTest.java
git commit -m "feat: register dm_* tools in CodeNavigatorMcpServer, bootstrap domain on serve"
```

---

## Task 7: Replace DomainDbReader with DomainSqliteStore in BriefingGenerator

**Files:**
- Modify: `code-navigator/src/main/java/com/codenavigator/briefing/BriefingGenerator.java`
- Delete: `code-navigator/src/main/java/com/codenavigator/briefing/DomainDbReader.java`
- Delete: `code-navigator/src/test/java/com/codenavigator/briefing/DomainDbReaderTest.java`
- Modify: `code-navigator/src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java` (handleCgBriefing)

- [ ] **Step 1: Update BriefingGenerator to use DomainSqliteStore**

Replace `DomainDbReader` field with `DomainSqliteStore`. Update the constructor and all methods that use domain data:

```java
// Change import
import com.codenavigator.domain.DomainSqliteStore;
import com.codenavigator.domain.model.*;

// Change field and constructor
private final DomainSqliteStore domainStore;

public BriefingGenerator(GraphStore store, DomainSqliteStore domainStore) {
    this.store = store;
    this.domainStore = domainStore;
}
```

Update `generate()`:
```java
if (domainStore != null) {
    writeFile(outputDir, "domain.md", generateDomain());
    writeFile(outputDir, "flows.md", generateFlows());
    writeFile(outputDir, "rules.md", generateRules());
}
```

Update `generateDomain()` — replace `domainReader.readContexts()` with `domainStore.getAllContexts()`, etc. The domain store returns richer model objects, so adapt the formatting:

```java
private String generateDomain() {
    var sb = new StringBuilder();
    sb.append("# Domain Model\n\n");

    var contexts = domainStore.getAllContexts();
    if (!contexts.isEmpty()) {
        sb.append("## Bounded Contexts\n\n");
        for (var ctx : contexts) {
            sb.append("### ").append(ctx.name()).append("\n");
            if (ctx.description() != null) sb.append(ctx.description()).append("\n");
            if (!ctx.entities().isEmpty()) sb.append("Entities: ").append(String.join(", ", ctx.entities())).append("\n");
            if (ctx.communicatesWith() != null) {
                for (var comm : ctx.communicatesWith()) {
                    sb.append("  -> ").append(comm.context()).append(" (").append(comm.type()).append(", ").append(comm.via()).append(")\n");
                }
            }
            sb.append("\n");
        }
    }

    var entities = domainStore.getAllEntities();
    if (!entities.isEmpty()) {
        var byContext = entities.stream().collect(java.util.stream.Collectors.groupingBy(
            e -> e.context() != null ? e.context() : "unknown", LinkedHashMap::new, java.util.stream.Collectors.toList()));
        sb.append("## Entities\n\n");
        for (var entry : byContext.entrySet()) {
            var byType = entry.getValue().stream().collect(java.util.stream.Collectors.groupingBy(
                e -> e.type() != null ? e.type() : "other"));
            for (var typeEntry : byType.entrySet()) {
                String names = typeEntry.getValue().stream().map(DomainEntity::name).collect(java.util.stream.Collectors.joining(", "));
                sb.append("- ").append(entry.getKey()).append(" ").append(typeEntry.getKey()).append("s: ").append(names).append("\n");
            }
        }
        sb.append("\n");
    }

    var terms = domainStore.getAllTerms();
    if (!terms.isEmpty()) {
        sb.append("## Glossary\n");
        for (var term : terms) {
            sb.append("- **").append(term.name()).append("**: ").append(term.definition() != null ? term.definition() : "").append("\n");
        }
    }

    return sb.toString();
}
```

Update `generateFlows()`:
```java
private String generateFlows() {
    var flows = domainStore.getAllFlows();
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
```

Update `generateRules()`:
```java
private String generateRules() {
    var rules = domainStore.getAllRules();
    if (rules.isEmpty()) return null;

    var sb = new StringBuilder();
    sb.append("# Business Rules\n\n");

    var bySeverity = rules.stream().collect(java.util.stream.Collectors.groupingBy(
        r -> r.severity().name(), LinkedHashMap::new, java.util.stream.Collectors.toList()));

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
```

- [ ] **Step 2: Update handleCgBriefing in CodeNavigatorMcpServer**

Replace the `DomainDbReader` usage in `handleCgBriefing` with the already-available `domainHandlers.store()`:

```java
String handleCgBriefing(Map<String, Object> args) {
    String projectPath = args.containsKey("projectPath") ? (String) args.get("projectPath") : null;
    String outputName = args.containsKey("output") ? (String) args.get("output") : ".ai-briefing";

    var root = projectPath != null ? java.nio.file.Path.of(projectPath)
        : java.nio.file.Path.of(System.getenv("CODE_NAVIGATOR_PROJECT") != null
            ? System.getenv("CODE_NAVIGATOR_PROJECT") : ".");
    var outputDir = root.resolve(outputName);

    try {
        var generator = new BriefingGenerator(store, domainHandlers.store());
        generator.generate(outputDir);
        return "Briefing generated at " + outputDir.toAbsolutePath();
    } catch (Exception e) {
        return "Briefing generation failed: " + e.getMessage();
    }
}
```

Remove the `DomainDbReader` import from `CodeNavigatorMcpServer.java`.

- [ ] **Step 3: Delete DomainDbReader and its test**

Delete:
- `code-navigator/src/main/java/com/codenavigator/briefing/DomainDbReader.java`
- `code-navigator/src/test/java/com/codenavigator/briefing/DomainDbReaderTest.java`

- [ ] **Step 4: Update BriefingGeneratorTest if it references DomainDbReader**

Read `code-navigator/src/test/java/com/codenavigator/briefing/BriefingGeneratorTest.java` and update it to pass a `DomainSqliteStore` instead of `DomainDbReader`. The test likely creates a `DomainDbReader` — replace with creating a `DomainSqliteStore` on a temp DB and populating it.

- [ ] **Step 5: Run all tests**

Run: `cd /home/kamil/Documents/Project/My/Mcp/code-navigator && ./gradlew test 2>&1 | tail -20`
Expected: All tests PASS

- [ ] **Step 6: Commit**

```bash
git add code-navigator/src/main/java/com/codenavigator/briefing/BriefingGenerator.java
git add code-navigator/src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java
git rm code-navigator/src/main/java/com/codenavigator/briefing/DomainDbReader.java
git rm code-navigator/src/test/java/com/codenavigator/briefing/DomainDbReaderTest.java
git commit -m "refactor: replace DomainDbReader with DomainSqliteStore in BriefingGenerator"
```

---

## Task 8: Update build config, runner scripts, and documentation

**Files:**
- Modify: `jars/build-all.sh`
- Delete: `jars/run-domain-navigator.sh`
- Delete: `jars/domain-navigator.jar`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Remove domain-navigator from build-all.sh**

In `jars/build-all.sh`, remove the domain-navigator build block (lines that build and copy domain-navigator.jar).

- [ ] **Step 2: Delete domain-navigator runner script and JAR**

```bash
rm jars/run-domain-navigator.sh jars/domain-navigator.jar
```

- [ ] **Step 3: Check for any .mcp.json or settings referencing domain-navigator**

Search for any config files that reference `domain-navigator` as a separate server and update them. The `code-navigator/.mcp.json` already serves via `code-navigator.jar serve` which will now include domain tools — no change needed there.

- [ ] **Step 4: Update CLAUDE.md**

Update project structure section:
- Remove domain-navigator from the project list
- Update code-navigator description to mention it now includes domain tools (22 tools total: 16 `cg_*` + 6 `dm_*`)
- Remove domain-navigator from MCP Servers Available section
- Remove mention of `DOMAIN_NAVIGATOR_PROJECT` env var

- [ ] **Step 5: Commit**

```bash
git rm jars/run-domain-navigator.sh jars/domain-navigator.jar
git add jars/build-all.sh CLAUDE.md
git commit -m "chore: update build script, remove domain-navigator artifacts, update docs"
```

---

## Task 9: Full integration test and cleanup

**Files:**
- Verify: all code-navigator tests pass
- Delete: `domain-navigator/` directory

- [ ] **Step 1: Run full test suite**

Run: `cd /home/kamil/Documents/Project/My/Mcp/code-navigator && ./gradlew test 2>&1 | tail -30`
Expected: All tests PASS

- [ ] **Step 2: Build shadow JAR**

Run: `cd /home/kamil/Documents/Project/My/Mcp/code-navigator && ./gradlew shadowJar 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, produces `build/libs/code-navigator-0.1.0.jar`

- [ ] **Step 3: Verify JAR contains domain classes**

Run: `jar tf /home/kamil/Documents/Project/My/Mcp/code-navigator/build/libs/code-navigator-0.1.0.jar | grep "domain/" | head -15`
Expected: Lists `com/codenavigator/domain/` class files

- [ ] **Step 4: Delete domain-navigator project directory**

```bash
rm -rf /home/kamil/Documents/Project/My/Mcp/domain-navigator
```

- [ ] **Step 5: Run build-all.sh to verify**

Run: `cd /home/kamil/Documents/Project/My/Mcp && ./jars/build-all.sh 2>&1`
Expected: Builds code-navigator and agent-memory only, no errors

- [ ] **Step 6: Final commit**

```bash
git rm -r domain-navigator/
git add .
git commit -m "chore: remove domain-navigator project (merged into code-navigator)"
```
