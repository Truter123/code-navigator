# Universal Parsing Foundation (Tree-sitter via WASM) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove tree-sitter grammars can run on the pure-Java Chicory WASM runtime, then index a first language (Python) into the existing code graph — additively, leaving the Java/JavaParser path untouched.

**Architecture:** A spike first proves `web-tree-sitter` WASM → `wasi-emscripten-host` → Chicory can parse source on JDK 21 (hard decision gate). Only then do we build a production `TreeSitterRuntime` wrapper, a `LanguageIndexer` abstraction, and an additive Python phase in `ProjectIndexer`.

**Tech Stack:** Java 21, Gradle (Groovy DSL, shadow plugin), JUnit 5 + AssertJ, Chicory `com.dylibso.chicory:runtime:1.5.1`, `at.released.weh:bindings-chicory-emscripten-jvm:0.6.0`, web-tree-sitter `.wasm` grammars, SQLite graph store.

**Spec:** `docs/superpowers/specs/2026-06-23-universal-parsing-tree-sitter-design.md`

---

## Scope of THIS plan

This plan delivers **Phase 1a**: the feasibility spike (the decision gate) plus the
minimal end-to-end vertical slice — **Python files producing nodes in the graph**.

**Deferred to follow-on plans (Phase 1b), because their exact shape depends on what
the spike learns** (e.g. whether grammar side-modules need static or dynamic linking):
- Additional starter languages (Go, JS, TS, Rust, C#) — mechanical repetition once Python works (grammar `.wasm` + query pack).
- Edge extraction (calls/imports) via tree-sitter queries + `resolveNode`.
- `.code-navigator.json` `LanguageRegistry` config + extension overrides.
- Retiring the regex `TypeScriptIndexer`.

**Hard rule:** if the gate in Task 3 fails, **stop**. Do not implement Tasks 4–9.
Return to the spec and switch to the `java-tree-sitter` (JNI) or `jsitter` fallback.

---

## File structure

| File | Responsibility |
|------|----------------|
| `code-navigator/build.gradle` | add Chicory + wasi-emscripten-host deps (modify) |
| `code-navigator/src/main/resources/treesitter/tree-sitter.wasm` | web-tree-sitter core module (new asset) |
| `code-navigator/src/main/resources/treesitter/tree-sitter-python.wasm` | Python grammar (new asset) |
| `code-navigator/src/test/java/.../treesitter/TreeSitterSpikeTest.java` | spike gate — throwaway, deleted after Task 4 |
| `code-navigator/src/main/java/.../indexer/treesitter/TreeSitterRuntime.java` | load core+grammar, `parse()` → tree handle (new) |
| `code-navigator/src/main/java/.../indexer/treesitter/SyntaxNode.java` | Java view over a tree-sitter node (kind, byte range, children) (new) |
| `code-navigator/src/main/java/.../graph/NodeType.java` | add `FUNCTION, MODULE, STRUCT, TYPE_ALIAS, CONSTANT` (modify) |
| `code-navigator/src/main/java/.../indexer/LanguageIndexer.java` | interface: `indexFile(Path) → List<Node>` (new) |
| `code-navigator/src/main/java/.../indexer/treesitter/PythonTreeSitterIndexer.java` | Python decls → Nodes (new) |
| `code-navigator/src/main/java/.../indexer/ProjectIndexer.java` | additive Python phase in full + incremental (modify) |

All paths below are relative to the repo root (the directory containing `code-navigator/`). Java package root: `com.codenavigator`.

---

## Task 1: Add dependencies and obtain WASM assets

**Files:**
- Modify: `code-navigator/build.gradle` (dependencies block)
- Create: `code-navigator/src/main/resources/treesitter/tree-sitter.wasm`
- Create: `code-navigator/src/main/resources/treesitter/tree-sitter-python.wasm`

- [ ] **Step 1: Add the runtime dependencies**

In `code-navigator/build.gradle`, inside the existing `dependencies { ... }` block, add after the picocli line:

```groovy
    implementation 'com.dylibso.chicory:runtime:1.5.1'
    implementation 'at.released.weh:bindings-chicory-emscripten-jvm:0.6.0'
```

- [ ] **Step 2: Obtain the WASM assets**

The core and grammar `.wasm` ship with the `web-tree-sitter` npm package and the grammar's npm package. From any scratch dir with Node available:

```bash
npm pack web-tree-sitter && tar -xf web-tree-sitter-*.tgz
# core module:
cp package/tree-sitter.wasm <repo>/code-navigator/src/main/resources/treesitter/tree-sitter.wasm

# Python grammar — prebuilt wasm via the tree-sitter CLI (needs emscripten or docker):
npx tree-sitter@latest build --wasm $(npm root)/tree-sitter-python
# produces tree-sitter-python.wasm in CWD:
cp tree-sitter-python.wasm <repo>/code-navigator/src/main/resources/treesitter/
```

If `tree-sitter build --wasm` is unavailable, download a prebuilt `tree-sitter-python.wasm` from the grammar's GitHub releases. Record the exact source URL/version in a sibling `treesitter/SOURCES.md`.

- [ ] **Step 3: Verify the build resolves the new deps**

Run: `cd code-navigator && ./gradlew dependencies --configuration runtimeClasspath | grep -E 'chicory|weh'`
Expected: both `com.dylibso.chicory:runtime:1.5.1` and `at.released.weh:bindings-chicory-emscripten-jvm:0.6.0` appear, resolved.

- [ ] **Step 4: Commit**

```bash
git add code-navigator/build.gradle code-navigator/src/main/resources/treesitter/
git commit -m "build: add Chicory + wasi-emscripten-host deps and tree-sitter wasm assets"
```

---

## Task 2: Spike 0a — instantiate the tree-sitter core module on Chicory

Goal: prove the Emscripten host imports are satisfiable and `tree-sitter.wasm` loads and initializes under Chicory. No parsing yet.

**Files:**
- Create (throwaway): `code-navigator/src/test/java/com/codenavigator/indexer/treesitter/TreeSitterSpikeTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.codenavigator.indexer.treesitter;

import at.released.weh.bindings.chicory.ChicoryEmscriptenHostInstaller;
import at.released.weh.host.EmbedderHostBuilder;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class TreeSitterSpikeTest {

    /** Loads + initializes the core module; asserts a known tree-sitter export exists. */
    @Test
    void core_module_instantiates_under_chicory() throws Exception {
        var hostBuilder = new EmbedderHostBuilder();
        hostBuilder.fileSystem().setUnrestricted(true);

        try (var host = hostBuilder.build()) {
            var installer = new ChicoryEmscriptenHostInstaller.Builder().setHost(host).build();
            var functions = new ArrayList<>(installer.setupWasiPreview1HostFunctions());
            var finalizer = installer.setupEmscriptenFunctions();
            functions.addAll(finalizer.getEmscriptenFunctions());

            var imports = ImportValues.builder().withFunctions(functions).build();

            var wasm = TreeSitterSpikeTest.class.getResourceAsStream("/treesitter/tree-sitter.wasm");
            var instance = Instance.builder(wasm)
                    .withImportValues(imports)
                    .withInitialize(true)
                    .withStart(false)
                    .build();

            var runtime = finalizer.finalize(instance);
            runtime.initMainThread();

            // ts_parser_new is the canonical entry point; emscripten exports it prefixed with '_'.
            assertThat(instance.export("_ts_parser_new")).isNotNull();
        }
    }
}
```

- [ ] **Step 2: Run it — expect compile/runtime failure first**

Run: `cd code-navigator && ./gradlew test --tests 'com.codenavigator.indexer.treesitter.TreeSitterSpikeTest'`
Expected initially: failure — either the export name differs or an Emscripten import is unsatisfied. **This is the spike's discovery work.**

- [ ] **Step 3: Resolve instantiation**

Adjust until the module instantiates. Known failure modes and fixes:
- *Unsatisfied import* (e.g. `env.emscripten_*`, `wasi_snapshot_preview1.*`): confirm both `setupWasiPreview1HostFunctions()` and `setupEmscriptenFunctions()` outputs are in the `ImportValues`. If a specific symbol is still missing, it is the gate's first real risk — record it.
- *Export name mismatch*: list exports via `instance.exports()` (or the Chicory `Module` API) and find the real `ts_parser_new` symbol (may be `_ts_parser_new` or `ts_parser_new`). Update the assertion.
- *`Instance.builder(InputStream)` overload*: if only `File`/`byte[]` overloads exist in 1.5.1, read the resource to a temp file or `byte[]` first.

- [ ] **Step 4: Run until green**

Run: `cd code-navigator && ./gradlew test --tests 'com.codenavigator.indexer.treesitter.TreeSitterSpikeTest'`
Expected: PASS — core module loads, the parser-constructor export is present.

- [ ] **Step 5: Commit**

```bash
git add code-navigator/src/test/java/com/codenavigator/indexer/treesitter/TreeSitterSpikeTest.java
git commit -m "spike: tree-sitter core module instantiates on Chicory (gate 0a)"
```

---

## Task 3: Spike 0b — load the Python grammar, parse a file, measure throughput (DECISION GATE)

Goal: the make-or-break test. Load `tree-sitter-python.wasm`, set it as the parser's language, parse Python source, read the root node's kind. This exercises **grammar loading** (the grammars are Emscripten *side modules* — dynamic linking under Chicory is the central unknown).

**Files:**
- Modify (throwaway): `TreeSitterSpikeTest.java`

- [ ] **Step 1: Add the failing parse test**

```java
    @Test
    void parses_python_and_reads_root_node() throws Exception {
        String source = "def greet(name):\n    return 'hi ' + name\n\nclass Greeter:\n    pass\n";

        try (var rt = SpikeRuntime.boot()) {           // helper built in Step 3
            String rootKind = rt.parseRootKind("python", source);
            assertThat(rootKind).isEqualTo("module");   // tree-sitter-python root node type

            long start = System.nanoTime();
            for (int i = 0; i < 50; i++) rt.parseRootKind("python", source);
            long perParseMicros = (System.nanoTime() - start) / 50 / 1_000;
            System.out.println("[spike] avg parse micros for tiny file = " + perParseMicros);
            // Not asserted — recorded for the gate decision below.
        }
    }
```

- [ ] **Step 2: Run it — expect failure**

Run: `cd code-navigator && ./gradlew test --tests 'com.codenavigator.indexer.treesitter.TreeSitterSpikeTest.parses_python_and_reads_root_node'`
Expected: FAIL — `SpikeRuntime` does not exist yet.

- [ ] **Step 3: Implement the spike runtime helper**

Build a small `SpikeRuntime` (inner class or sibling) that wires the tree-sitter C API through the Chicory `Instance`. The authoritative reference for the call sequence and memory layout is web-tree-sitter's `lib/binding_web` (`binding.js`). The sequence to implement:

1. Instantiate the core module (reuse Task 2's working bootstrap).
2. Load the grammar: read `/treesitter/tree-sitter-python.wasm` bytes, hand them to the Emscripten dynamic loader. **Determine the supported mechanism:**
   - If `wasi-emscripten-host` exposes `dlopen`/`loadDynamicLibrary` → use it to register the side module and obtain the `tree_sitter_python` language pointer (exported function returning a `TSLanguage*`).
   - If side-module dynamic linking is **not** supported → fall back to a **statically linked** single wasm: rebuild with `emcc` linking the Python grammar into the core (`tree-sitter build --wasm` per-grammar already yields a self-contained module exporting `tree_sitter_python`; load it as a *second Instance* and copy the language pointer across, or build one combined module). Record which path worked.
3. Marshal source into WASM linear memory: `int len = bytes.length; int ptr = (int) instance.export("_malloc").apply(len)[0]; instance.memory().write(ptr, bytes);`
4. `int parser = (int) instance.export("_ts_parser_new").apply()[0];`
5. `instance.export("_ts_parser_set_language").apply(parser, languagePtr);`
6. `int tree = (int) instance.export("_ts_parser_parse_string").apply(parser, 0, ptr, len)[0];`
7. Root node: `TSNode` is returned **by value** (a struct); Emscripten lowers struct returns to a hidden out-pointer arg — allocate scratch, call `_ts_tree_root_node`, then read the struct, then `_ts_node_type(...)` returns a `const char*` to read as a NUL-terminated UTF-8 string from memory.
8. Free: `_free(ptr)`, `_ts_tree_delete(tree)`, `_ts_parser_delete(parser)`.

Confirm exact `Memory` method names (`write`, `readBytes`, `readInt`) against the Chicory 1.5.1 javadoc; adjust if they differ.

- [ ] **Step 4: Run until green**

Run: `cd code-navigator && ./gradlew test --tests 'com.codenavigator.indexer.treesitter.TreeSitterSpikeTest.parses_python_and_reads_root_node'`
Expected: PASS — prints `[spike] avg parse micros ...` and asserts root kind `module`.

- [ ] **Step 5: DECISION GATE — record the verdict**

Write findings to `docs/superpowers/specs/2026-06-23-tree-sitter-spike-result.md`:
- Did it parse? Which grammar-loading mechanism worked (dynamic side-module vs static link)?
- Measured per-parse latency.
- Verdict: **PASS** → proceed to Task 4. **FAIL/too slow** → STOP; switch the spec to the `java-tree-sitter` (JNI) / `jsitter` fallback and rewrite Tasks 4+ against that substrate.

```bash
git add docs/superpowers/specs/2026-06-23-tree-sitter-spike-result.md code-navigator/src/test/java/com/codenavigator/indexer/treesitter/TreeSitterSpikeTest.java
git commit -m "spike: parse Python on Chicory + record decision-gate verdict (gate 0b)"
```

---

> **The tasks below run ONLY if Task 3's gate is PASS.** They productionize what the spike proved. Where a step depends on the exact API surface confirmed in Task 3, it says so.

## Task 4: TreeSitterRuntime production wrapper

**Files:**
- Create: `code-navigator/src/main/java/com/codenavigator/indexer/treesitter/TreeSitterRuntime.java`
- Test: `code-navigator/src/test/java/com/codenavigator/indexer/treesitter/TreeSitterRuntimeTest.java`
- Delete: `TreeSitterSpikeTest.java` (its knowledge now lives in `TreeSitterRuntime`)

- [ ] **Step 1: Write the failing test**

```java
package com.codenavigator.indexer.treesitter;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TreeSitterRuntimeTest {

    @Test
    void parses_python_to_walkable_tree() {
        try (var rt = TreeSitterRuntime.create()) {
            SyntaxNode root = rt.parse("python", "def f():\n    pass\n");
            assertThat(root.kind()).isEqualTo("module");
            assertThat(root.namedChildren()).anyMatch(c -> c.kind().equals("function_definition"));
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd code-navigator && ./gradlew test --tests 'com.codenavigator.indexer.treesitter.TreeSitterRuntimeTest'`
Expected: FAIL — `TreeSitterRuntime`/`SyntaxNode` not defined.

- [ ] **Step 3: Implement `TreeSitterRuntime`**

Port the working spike sequence into a real class: `static TreeSitterRuntime create()` boots the Chicory instance + loads grammars lazily by language id (grammar resource path `"/treesitter/tree-sitter-" + lang + ".wasm"`); `SyntaxNode parse(String lang, String source)` returns the root; `implements AutoCloseable` to free WASM resources. Cache language pointers per id. Keep the instance single-threaded (one runtime per thread; document it).

- [ ] **Step 4: Run to verify it passes**

Run: `cd code-navigator && ./gradlew test --tests 'com.codenavigator.indexer.treesitter.TreeSitterRuntimeTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git rm code-navigator/src/test/java/com/codenavigator/indexer/treesitter/TreeSitterSpikeTest.java
git add code-navigator/src/main/java/com/codenavigator/indexer/treesitter/TreeSitterRuntime.java code-navigator/src/test/java/com/codenavigator/indexer/treesitter/TreeSitterRuntimeTest.java
git commit -m "feat(treesitter): production TreeSitterRuntime wrapper (parse -> SyntaxNode)"
```

---

## Task 5: SyntaxNode tree view

**Files:**
- Create: `code-navigator/src/main/java/com/codenavigator/indexer/treesitter/SyntaxNode.java`
- Test: `code-navigator/src/test/java/com/codenavigator/indexer/treesitter/SyntaxNodeTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.codenavigator.indexer.treesitter;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SyntaxNodeTest {
    @Test
    void exposes_kind_range_and_named_children() {
        try (var rt = TreeSitterRuntime.create()) {
            SyntaxNode root = rt.parse("python", "x = 1\n");
            assertThat(root.kind()).isEqualTo("module");
            assertThat(root.startByte()).isEqualTo(0);
            assertThat(root.endByte()).isGreaterThan(0);
            assertThat(root.namedChildren()).isNotEmpty();
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd code-navigator && ./gradlew test --tests 'com.codenavigator.indexer.treesitter.SyntaxNodeTest'`
Expected: FAIL — methods missing.

- [ ] **Step 3: Implement `SyntaxNode`**

A read-only view holding the runtime handle + the node struct bytes. Expose: `String kind()` (`_ts_node_type`), `int startByte()`/`int endByte()` (`_ts_node_start_byte`/`_ts_node_end_byte`), `List<SyntaxNode> namedChildren()` (`_ts_node_named_child_count` + `_ts_node_named_child`), and `String text(String source)` (substring by byte range, decoded UTF-8). Keep struct marshalling encapsulated here so callers never touch WASM memory.

- [ ] **Step 4: Run to verify it passes**

Run: `cd code-navigator && ./gradlew test --tests 'com.codenavigator.indexer.treesitter.SyntaxNodeTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add code-navigator/src/main/java/com/codenavigator/indexer/treesitter/SyntaxNode.java code-navigator/src/test/java/com/codenavigator/indexer/treesitter/SyntaxNodeTest.java
git commit -m "feat(treesitter): SyntaxNode view (kind/range/named-children/text)"
```

---

## Task 6: NodeType additions

**Files:**
- Modify: `code-navigator/src/main/java/com/codenavigator/graph/NodeType.java`
- Test: `code-navigator/src/test/java/com/codenavigator/graph/NodeTypeEdgeTypeTest.java`

- [ ] **Step 1: Add a failing assertion**

In `NodeTypeEdgeTypeTest`, add:

```java
    @Test
    void includes_language_neutral_node_types() {
        assertThat(NodeType.valueOf("FUNCTION")).isNotNull();
        assertThat(NodeType.valueOf("MODULE")).isNotNull();
        assertThat(NodeType.valueOf("STRUCT")).isNotNull();
        assertThat(NodeType.valueOf("TYPE_ALIAS")).isNotNull();
        assertThat(NodeType.valueOf("CONSTANT")).isNotNull();
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd code-navigator && ./gradlew test --tests 'com.codenavigator.graph.NodeTypeEdgeTypeTest'`
Expected: FAIL — `IllegalArgumentException: No enum constant ... FUNCTION`.

- [ ] **Step 3: Add the enum values**

In `NodeType.java`, add a new line before `// External`:

```java
    // Language-neutral (tree-sitter)
    FUNCTION, MODULE, STRUCT, TYPE_ALIAS, CONSTANT,
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd code-navigator && ./gradlew test --tests 'com.codenavigator.graph.NodeTypeEdgeTypeTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add code-navigator/src/main/java/com/codenavigator/graph/NodeType.java code-navigator/src/test/java/com/codenavigator/graph/NodeTypeEdgeTypeTest.java
git commit -m "feat(graph): add language-neutral NodeTypes (FUNCTION/MODULE/STRUCT/TYPE_ALIAS/CONSTANT)"
```

---

## Task 7: LanguageIndexer interface + PythonTreeSitterIndexer (nodes only)

**Files:**
- Create: `code-navigator/src/main/java/com/codenavigator/indexer/LanguageIndexer.java`
- Create: `code-navigator/src/main/java/com/codenavigator/indexer/treesitter/PythonTreeSitterIndexer.java`
- Test: `code-navigator/src/test/java/com/codenavigator/indexer/treesitter/PythonTreeSitterIndexerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.codenavigator.indexer.treesitter;

import com.codenavigator.graph.Node;
import com.codenavigator.graph.NodeType;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PythonTreeSitterIndexerTest {

    @Test
    void extracts_functions_and_classes(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path f = dir.resolve("sample.py");
        Files.writeString(f, "def greet(name):\n    return name\n\nclass Greeter:\n    def hi(self):\n        return 1\n");

        try (var rt = TreeSitterRuntime.create()) {
            List<Node> nodes = new PythonTreeSitterIndexer(rt).indexFile(f);

            assertThat(nodes).anyMatch(n -> n.type() == NodeType.FUNCTION && n.name().equals("greet"));
            assertThat(nodes).anyMatch(n -> n.type() == NodeType.CLASS && n.name().equals("Greeter"));
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd code-navigator && ./gradlew test --tests 'com.codenavigator.indexer.treesitter.PythonTreeSitterIndexerTest'`
Expected: FAIL — types not defined.

- [ ] **Step 3: Define the interface**

`LanguageIndexer.java`:

```java
package com.codenavigator.indexer;

import com.codenavigator.graph.Node;
import java.nio.file.Path;
import java.util.List;

/** Extracts graph nodes from a single source file of one language. */
public interface LanguageIndexer {
    /** True if this indexer handles the given file (by extension). */
    boolean handles(Path file);
    /** Extract nodes; never throws on parse errors — returns what it found. */
    List<Node> indexFile(Path file);
}
```

- [ ] **Step 4: Implement `PythonTreeSitterIndexer`**

Walk the `SyntaxNode` tree from `rt.parse("python", source)`. For each `function_definition` → `FUNCTION` node (name = the `identifier`/`name` child text); for each `class_definition` → `CLASS` node. Build `Node` with the same shape the existing indexers use (see `TypeScriptIndexer.indexFile`): id/name/qualifiedName from file-relative module + symbol, `filePath` = `file.toString()` (absolute, matching current TS behavior), `lineNumber` derived from the node's start byte, `codeSnippet` = first ~10 lines of the node's `text(source)`, `lastModified` from the file. `handles(Path)` returns true for `.py`.

- [ ] **Step 5: Run to verify it passes**

Run: `cd code-navigator && ./gradlew test --tests 'com.codenavigator.indexer.treesitter.PythonTreeSitterIndexerTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add code-navigator/src/main/java/com/codenavigator/indexer/LanguageIndexer.java code-navigator/src/main/java/com/codenavigator/indexer/treesitter/PythonTreeSitterIndexer.java code-navigator/src/test/java/com/codenavigator/indexer/treesitter/PythonTreeSitterIndexerTest.java
git commit -m "feat(treesitter): LanguageIndexer interface + Python node extraction"
```

---

## Task 8: Wire an additive Python phase into ProjectIndexer

**Files:**
- Modify: `code-navigator/src/main/java/com/codenavigator/indexer/ProjectIndexer.java`
- Test: `code-navigator/src/test/java/com/codenavigator/indexer/ProjectIndexerIntegrationTest.java`

- [ ] **Step 1: Write the failing integration test**

Add to `ProjectIndexerIntegrationTest` (follow the existing test's setup for `GraphStore` + temp project):

```java
    @Test
    void indexes_python_files_additively(@org.junit.jupiter.api.io.TempDir Path proj) throws Exception {
        Files.writeString(proj.resolve("util.py"), "def helper():\n    return 1\n");
        // a Java file too, to prove the Java path is unaffected:
        Files.createDirectories(proj.resolve("src"));
        Files.writeString(proj.resolve("src/Foo.java"), "package a; public class Foo {}\n");

        try (var store = GraphStore.openInMemory()) {     // match existing test's store factory
            new ProjectIndexer(store).indexFull(proj);
            var names = store.getAllNodes().stream().map(Node::name).toList();
            assertThat(names).contains("helper");          // Python node present
            assertThat(names).contains("Foo");             // Java node still present
        }
    }
```

(If the existing test opens the store differently, mirror that exactly.)

- [ ] **Step 2: Run to verify it fails**

Run: `cd code-navigator && ./gradlew test --tests 'com.codenavigator.indexer.ProjectIndexerIntegrationTest.indexes_python_files_additively'`
Expected: FAIL — no `helper` node (Python not indexed yet).

- [ ] **Step 3: Add the Python phase**

In `ProjectIndexer`: add a `findPythonFiles(projectPath)` (filter `.py`, reuse `isExcluded`), and after the TS phase in `indexFull`, add a phase that creates one `TreeSitterRuntime`, runs `PythonTreeSitterIndexer.indexFile` per file, and for each node calls `store.saveNode(node)` + `embedNode(node)` (exactly like the TS phase at lines ~99-105). Add the python files to `trackFiles(...)`. In `indexIncremental`, add `reindexChangedPythonFiles(...)` mirroring `reindexChangedTsFiles`. Wrap per-file parsing in try/catch so one bad file never aborts the run (matches existing "skip unparseable" behavior).

- [ ] **Step 4: Run to verify it passes**

Run: `cd code-navigator && ./gradlew test --tests 'com.codenavigator.indexer.ProjectIndexerIntegrationTest'`
Expected: PASS — both `helper` and `Foo` present.

- [ ] **Step 5: Run the FULL suite (Java-regression guard)**

Run: `cd code-navigator && ./gradlew test`
Expected: PASS — all pre-existing tests still green (proves the Java path is untouched).

- [ ] **Step 6: Commit**

```bash
git add code-navigator/src/main/java/com/codenavigator/indexer/ProjectIndexer.java code-navigator/src/test/java/com/codenavigator/indexer/ProjectIndexerIntegrationTest.java
git commit -m "feat(indexer): additive Python tree-sitter phase in ProjectIndexer"
```

---

## Task 9: Verify the shadow JAR bundles and runs the grammars

**Files:** none (verification only)

- [ ] **Step 1: Build the shadow JAR**

Run: `cd code-navigator && ./gradlew shadowJar`
Expected: BUILD SUCCESSFUL; `build/libs/code-navigator.jar` produced.

- [ ] **Step 2: Confirm the wasm assets are inside the jar**

Run: `unzip -l code-navigator/build/libs/code-navigator.jar | grep treesitter`
Expected: lists `treesitter/tree-sitter.wasm` and `treesitter/tree-sitter-python.wasm`.

- [ ] **Step 3: Index a real mixed repo from the packaged jar**

```bash
mkdir -p /tmp/mixed/src && printf 'def hi():\n    return 1\n' > /tmp/mixed/app.py && printf 'package a; public class A {}\n' > /tmp/mixed/src/A.java
java -jar code-navigator/build/libs/code-navigator.jar init /tmp/mixed
java -jar code-navigator/build/libs/code-navigator.jar status /tmp/mixed
```

Expected: `status` reports nodes > 0 including the Python function (proves grammars load from the packaged jar, not just from `src/test/resources`).

- [ ] **Step 4: Commit (docs note only, if any)**

If you updated `mcp/CLAUDE.md` or a README to mention multi-language support, commit it; otherwise nothing to commit here.

---

## Self-review notes

- **Spec coverage:** spike+gate (spec "Gate-zero") = Tasks 2–3; `TreeSitterRuntime` (spec component 1) = Task 4; `SyntaxNode` = Task 5; NodeType additions (spec "Node & edge mapping") = Task 6; `LanguageIndexer` (spec component 2) + node extraction = Task 7; ProjectIndexer integration + Java-untouched guarantee = Task 8; JAR bundling (spec "JAR bundling") = Task 9. **Deferred by design:** query packs/`.scm` (spec component 3), `LanguageRegistry`/config (component 4), edges (spec "Edges"), languages beyond Python — all in the follow-on Phase 1b plan, gated on the spike outcome.
- **No placeholders:** the only deliberately open items are inside the spike (Task 3, Step 3), where the grammar-loading mechanism is the unknown the spike exists to resolve — the step enumerates the two concrete mechanisms and how to choose.
- **Type consistency:** `TreeSitterRuntime.create()`, `parse(String,String)→SyntaxNode`, `SyntaxNode.kind()/startByte()/endByte()/namedChildren()/text(String)`, `LanguageIndexer.handles(Path)/indexFile(Path)`, `PythonTreeSitterIndexer(TreeSitterRuntime)` are used identically across Tasks 4–8.
