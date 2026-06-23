# Universal Parsing Foundation — Tree-sitter via WASM (Phase 1)

**Date:** 2026-06-23
**Project:** `mcp/code-navigator`
**Goal:** Make code-navigator language-agnostic. Index any tree-sitter-supported
language into the existing SQLite code graph with **zero native dependencies**,
added **additively** alongside the existing Java (JavaParser) path.

---

## Context: the roadmap this belongs to

"Make code-navigator generic" decomposes into three sequential sub-projects, each
with its own spec → plan → implementation cycle:

| Phase | Theme | Depends on |
|-------|-------|-----------|
| **1 — Universal parsing foundation** (this spec) | Many languages via one parser | — |
| 2 — Framework-agnostic classification | Useful on any repo shape (auto entry-points, generic heuristics) | Phase 1 |
| 3 — New capabilities | `cg_similar` ("does this exist?"), `cg_onboard`, `cg_path` | Graph + embeddings (1/2 optional) |

`cg_similar` and `cg_path` (Phase 3) could ship independently on today's graph;
they are *not* in this spec. This spec is **Phase 1 only**.

This phase **supersedes** the hand-written `TypeScriptIndexer` and the parked
Groovy regex indexer (the 2026-06-22 "Full Repo Coverage" design): once
tree-sitter parses TypeScript and Groovy, those regex paths are retired rather
than extended.

---

## Problem

Today code-navigator parses two ways:

- **Java** — deep, via JavaParser AST (`NodeExtractor`, `MethodExtractor`,
  `EdgeExtractor`). This is where all DDD/Spring/CQRS classification lives.
- **TypeScript** — shallow, via hand-written regex (`TypeScriptIndexer`).
- **Groovy** — planned as more hand-written regex.

Every additional language means more bespoke Java/regex. Competing knowledge-graph
MCP servers get breadth from **one** parser (tree-sitter, 30–64 languages). To be
"generic," code-navigator needs the same: a single extraction layer where adding a
language is *data*, not code.

The constraint (from the requester): **no native binaries** — the tool is built on
Linux and run on Windows from a single shadow JAR, so per-platform `.so/.dll/.dylib`
shipping is unacceptable.

---

## Approach & key decisions

**Strategy: tree-sitter grammars (`.wasm`) executed on a pure-Java WASM runtime.**
This is the only option that delivers real AST parsing for many languages *and*
keeps a single dependency-free JAR.

Stack (all pieces verified to exist and target our JDK):

- **Chicory** — pure-Java WASM runtime, zero native deps / no JNI, JDK 17+
  (we are on Java 21).
- **`wasi-emscripten-host`** (v0.6.0, targets Chicory 1.5.1) — provides Emscripten
  host imports **and** WASI Preview 1. This is the bridge that lets the
  Emscripten-compiled `web-tree-sitter.wasm` run on Chicory.
- **web-tree-sitter** — tree-sitter core + per-language grammars, distributed as
  `.wasm`.

**Decisions and rejected alternatives:**

- **Keep Java on JavaParser; do NOT migrate it to tree-sitter in Phase 1.**
  JavaParser gives type-aware-ish AST and underpins the DDD/Spring/CQRS tiers.
  Tree-sitter gives *syntax only*. Re-deriving the Java intelligence on tree-sitter
  is large and risky and buys nothing now. Java is additive-untouched.
- **Tree-sitter native (java-tree-sitter, JNI)** — fastest/most proven, but ships
  native binaries per OS+arch. Rejected on the no-native-deps constraint. Retained
  as the **fallback** if the spike fails.
- **Config regex packs only** — pure-JVM but shallow (no reliable edges) and
  hand-authored. Rejected as the primary strategy; the *registry* idea survives as
  the language-enable config.
- **JetBrains `jsitter`** — JVM tree-sitter API using `sun.misc.Unsafe`; noted as a
  secondary fallback.

**Novelty risk:** the exact chain (tree-sitter → wasi-emscripten-host → Chicory) is
**not publicly documented**. Each layer is real and maintained; gluing them is new.
Therefore this phase is **spike-gated** (below).

---

## Gate-zero: feasibility spike (FIRST task, throwaway)

Nothing else is built until this passes. Build a minimal harness that:

1. Adds `chicory` + `wasi-emscripten-host` as dependencies.
2. Loads `web-tree-sitter.wasm` (core) and `tree-sitter-python.wasm` (one grammar)
   from JAR resources via Chicory + the Emscripten host environment.
3. Parses a small Python source string and walks the resulting syntax tree.
4. Asserts expected node kinds appear (e.g. `function_definition`, `class_definition`).
5. Measures parse throughput (files/sec, ms for a ~500-line file).

**Decision gate:**
- **Pass** (parses correctly, throughput acceptable for offline indexing) → proceed
  to the full architecture below.
- **Fail / too slow / blocked on emscripten imports** → fall back to
  `java-tree-sitter` (JNI, native binaries bundled per platform) or `jsitter`,
  and revise this spec's "no native deps" guarantee. The rest of the architecture
  (LanguageIndexer, query packs, NodeType changes, ProjectIndexer wiring) is
  **parser-agnostic** and survives either way.

The spike code is discarded; only the dependency choice and the runtime-wrapper
design graduate.

---

## Architecture (post-gate)

```
ProjectIndexer
  ├─ Java phase        → JavaParser (UNCHANGED)
  ├─ TreeSitter phase  → TreeSitterIndexer (NEW)
  │     ├─ TreeSitterRuntime (Chicory + wasi-emscripten-host)
  │     ├─ LanguageRegistry (ext → grammar + query pack)
  │     └─ per-language query packs (.scm resources)
  └─ edges → resolveNode (shared name-based resolution)
```

### Components

1. **`TreeSitterRuntime`** (`indexer/treesitter/TreeSitterRuntime.java`)
   - Wraps Chicory + wasi-emscripten-host.
   - Loads core `web-tree-sitter.wasm` once; loads grammar `.wasm` files on demand
     from classpath resources (`/treesitter/<lang>.wasm`).
   - `parse(String source, Language lang) → SyntaxTree` (a thin Java view over the
     tree-sitter tree: node kind, byte range, children, named-child iteration).
   - **Single-threaded per instance** (runtime limitation). Indexing parallelism, if
     any, uses an instance pool — one runtime per worker thread.

2. **`LanguageIndexer`** interface (`indexer/LanguageIndexer.java`)
   - Generalizes today's `TypeScriptIndexer`:
     `List<Node> indexFile(Path file)` and
     `List<Edge> extractEdges(Path file, List<Node> fileNodes, GraphStore store)`.
   - Implemented once by **`TreeSitterIndexer`**, parameterized by a language's
     query pack — not one class per language.

3. **Query packs** (`src/main/resources/treesitter/<lang>/`)
   - `<lang>.wasm` — the grammar.
   - `nodes.scm` — tree-sitter query capturing declarations
     (`@class`, `@interface`, `@enum`, `@function`, `@struct`, `@constant`, …)
     with their name capture.
   - `edges.scm` — query capturing call sites and imports.
   - `classify.json` — small map: capture name → `NodeType`, plus name-suffix
     hints (e.g. `*Controller` → `CONTROLLER`) mirroring
     `NodeExtractor.detectGeneric`.
   - **Adding a language = add this folder + register an extension. No logic recompile.**

4. **`LanguageRegistry`** (`indexer/treesitter/LanguageRegistry.java`)
   - Maps file extension → language id. Built-in defaults for the starter set;
     overridable / extendable via `.code-navigator.json` (the config-driven pick).
   - `.code-navigator.json` (optional, project root):
     ```json
     { "languages": { "enabled": ["python","go","javascript","typescript","rust","csharp"],
                       "extensions": { ".kt": "kotlin" } } }
     ```

### Node & edge mapping

- **`NodeType` additions** (`graph/NodeType.java`): language-neutral kinds
  `FUNCTION`, `MODULE`, `STRUCT`, `TYPE_ALIAS`, `CONSTANT`.
  Existing `CLASS`/`INTERFACE`/`ENUM`/`SERVICE`/`CONTROLLER`/`REPOSITORY` are reused
  where the construct or name-suffix heuristic applies.
- **Node id / qualifiedName:** mirror current conventions — qualifiedName from
  module/package path + symbol name; id = qualifiedName (cross-file unique). For
  languages without packages, qualifiedName = relative-path-derived module + symbol.
- **Edges:** `edges.scm` yields (callerSymbol, calleeName) and (file, importTarget).
  Resolution reuses the **existing** `EdgeExtractor.resolveNode` strategy
  (same-module FQN, then unique simple-name match, skip if ambiguous) so
  Java↔other-language edges link by simple name for free. Imports also feed an
  internal import graph and `USES_LIBRARY` for declared external deps.

### ProjectIndexer integration

- **`indexFull`:** after the Java phase, add a tree-sitter phase — discover files
  whose extension is in the registry, parse + extract nodes, then a second pass for
  edges. Each node still flows through `embedNode(...)` (semantic search) and
  `trackFiles(...)`.
- **`indexIncremental`:** extend `reindexChangedTsFiles` into a generic
  `reindexChangedTreeSitterFiles` keyed off the registry; same checksum/timestamp
  change detection as today.
- **File scanning:** replace `findTsFiles` with a registry-driven
  `findTreeSitterFiles`; keep existing exclusions (`build`, `.gradle`,
  `node_modules`) plus per-language test/decl exclusions in the query pack metadata.
- **Path handling:** match current TS behavior (absolute path) for non-Java files to
  avoid the relativization split already present in the codebase.

### Starter languages (Phase 1 set)

Python, Go, JavaScript, TypeScript, Rust, C#. (TypeScript via tree-sitter replaces
the regex `TypeScriptIndexer`.) Each ships a full query pack. Groovy and others
become trivial follow-ups (grammar + query pack only).

### JAR bundling

Grammar `.wasm` files (~hundreds of KB each) ship as classpath resources under
`src/main/resources/treesitter/`. Total added size for 6 grammars + core is on the
order of a few MB — modest versus the ~50 MB Kotlin compiler dependency previously
rejected. The shadow plugin already packages resources.

---

## Out of scope (Phase 1)

- Framework-agnostic classification heuristics / auto entry-point detection (Phase 2).
- `cg_similar`, `cg_onboard`, `cg_path` (Phase 3).
- Migrating the Java path off JavaParser.
- Deep, type-resolved edges (tree-sitter is syntax-only — name-heuristic edges match
  the *current* Java fidelity bar and are accepted).
- Incremental *re-parse* using tree-sitter's edit API (we re-parse changed files
  whole, as today).

---

## Risks & mitigations

| Risk | Mitigation |
|------|-----------|
| Novel tree-sitter→Chicory integration may not work | **Gate-zero spike** before any build-out; native-JNI / jsitter fallback; architecture is parser-agnostic |
| WASM interpreter slower than native | Indexing is offline; incremental re-parses only changed files; pool runtimes |
| Emscripten import surface incomplete in wasi-emscripten-host | Surfaces in the spike; fallback path defined |
| JAR size growth from grammars | Few MB total; far under the rejected 50 MB; bundle only enabled grammars |
| Syntax-only edges miss type-resolved calls | Accepted — equals current Java name-heuristic fidelity; documented |
| Single-threaded runtime | Instance pool keyed per worker thread |

---

## Testing strategy

- **Spike harness** (throwaway): asserts a Python parse + throughput number.
- **`TreeSitterRuntimeTest`:** parse a known snippet per starter language, assert
  expected node kinds.
- **`TreeSitterIndexerTest`:** per-language fixtures → assert extracted nodes
  (types, names, line numbers) and edges (calls/imports resolved).
- **`LanguageRegistryTest`:** extension mapping + `.code-navigator.json` override.
- **`ProjectIndexerIntegrationTest`:** a mixed Java + Python + Go sample repo →
  assert cross-language node graph and that the Java path is unchanged.
- **`NodeTypeEdgeTypeTest`:** covers the new enum values.
- **Jar-resource test:** grammars load from the packaged shadow JAR (not just from
  `src/test/resources`).

---

## Rebuild & verify

```bash
# Build the shadow JAR
cd code-navigator && ./gradlew shadowJar
# (or ./jars/build-all.sh)

# Re-index a multi-language repo and confirm non-Java nodes appear
java -jar build/libs/code-navigator.jar init <path-to-mixed-repo>
java -jar build/libs/code-navigator.jar status <path-to-mixed-repo>
```

**Expected:** `cg_status` shows nodes for Python/Go/Rust/etc. files; `cg_callers`/
`cg_callees` resolve at least same-name cross-file edges; the Java node/edge counts
on an all-Java repo are **identical** to pre-change (regression guard).
