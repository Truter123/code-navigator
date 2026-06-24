# Tree-sitter on Chicory (pure-Java WASM) — Feasibility Spike Result

- **Date:** 2026-06-23
- **Branch:** `feat/universal-parsing-tree-sitter`
- **Decision gate for:** universal (multi-language) parsing in code-navigator via
  tree-sitter grammars running on a pure-Java WASM runtime.
- **Stack under test:** Chicory `runtime` 1.5.1 + `wasm` 1.5.1; wasi-emscripten-host
  (`weh`) `bindings-chicory-emscripten-jvm` 0.6.0 (and its transitive
  `emscripten-runtime`, `wasm-wasi-preview1`, `host` modules).
- **Artifacts under test:** `src/main/resources/treesitter/tree-sitter.wasm`
  (web-tree-sitter CORE v0.22.6, 188 635 bytes) and
  `src/main/resources/treesitter/tree-sitter-python.wasm`
  (grammar from tree-sitter-wasms@0.1.13, 476 105 bytes).
- **Throwaway demonstrator:**
  `src/test/java/com/codenavigator/indexer/treesitter/TreeSitterSpikeTest.java`
  (3 tests, all green — they PASS by *asserting the blocker* so the negative result
  is a deterministic, committed record rather than a red build).

---

## VERDICT: **FAIL** (for the current approach / artifacts)

These web-tree-sitter WASM artifacts **cannot** be instantiated and run on Chicory
1.5.1 via weh 0.6.0. The blocker is **fundamental, not a tuning knob**: the shipped
modules are emscripten **relocatable (MAIN_MODULE / SIDE_MODULE) dynamic-linking**
artifacts that require an emscripten runtime *linker* — shared imported memory and
indirect function table, linker-assigned base globals, a Global Offset Table (GOT),
and runtime data/function relocations. **Neither weh 0.6.0 nor Chicory 1.5.1
implements that linker.** weh targets the *standalone / reactor* emscripten profile,
where a module owns its own memory and needs only host syscall/`env` functions.

A negative result here is a valid, expected outcome of the spike: it tells us the
"drop the published web-tree-sitter wasm onto Chicory" plan does not work as-is, and
exactly why, so we can choose a different path before sinking more effort.

---

## GATE 0a — Did the core instantiate? **No.**

Empirically (`gate0a_coreFailsToInstantiate`): the very first link error is

```
com.dylibso.chicory.wasm.UnlinkableException:
  unknown import, could not find host function for import number: 0 named env.abort
```

The weh installer **does** boot and supply **93 host functions** (46 WASI
preview1 + 47 emscripten `env`), all as *functions*. But:

1. **Naming skew (older emscripten).** This core was built with an older emscripten
   than weh 0.6.0 targets. The core imports `env.abort`; weh exports `env._abort_js`
   and `env.exit`. The core imports `env.emscripten_memcpy_js`; **weh has no such
   function at all**. So even the satisfiable *function* imports don't line up by
   name.

2. **The real blocker — dynamic-linking structural imports weh cannot provide**
   (`gate0a_dynamicLinkingGap`). The core (`tree-sitter.wasm`) is a *relocatable*
   module: it has a `dylink.0` custom section and **imports**, rather than defines,
   its memory/table/bases:

   | core import | kind | who normally provides it |
   |---|---|---|
   | `env.memory` | memory | the emscripten MAIN module / JS loader (shared heap) |
   | `env.__indirect_function_table` | table | the linker (shared call table) |
   | `env.__stack_pointer` | global | linker |
   | `env.__memory_base` | global | linker (assigned load address) |
   | `env.__table_base` | global | linker (assigned table offset) |
   | `GOT.mem.__heap_base` | global | the GOT, populated by the linker |
   | `env.tree_sitter_parse_callback` | func | web-tree-sitter JS glue |
   | `env.tree_sitter_log_callback` | func | web-tree-sitter JS glue |

   weh provides **0 memories, 0 tables, 0 globals, no GOT, and no dlopen** (verified
   by enumerating every host function it installs, and by class-scanning
   `emscripten-runtime-jvm` / `bindings-chicory-emscripten-jvm` / `host-jvm` and the
   Chicory `runtime` jar for any `GOT` / `relocat*` / `Linker` / `dlopen` /
   `loadDynamic` / `SideModule` class — none exist).

**Imports weh had to satisfy beyond defaults:** none were satisfiable — the spike
never got past linking, so the question of "extra imports to wire up" is moot. The
gap is the entire dynamic-linking substrate, not a handful of missing `env` shims.

---

## GATE 0b — Did it parse Python? **No — unreachable.**

`gate0b_parsePython_blocked` confirms the grammar exports the expected language
constructor (`tree_sitter_python`, alongside `tree_sitter_python_external_scanner_*`
and `__wasm_apply_data_relocs`), but the grammar **cannot be loaded by any mechanism
available on this stack:**

- **Dynamic side-module load (the web-tree-sitter mechanism): NOT SUPPORTED.**
  weh/Chicory have no `dlopen` / `loadDynamicLibrary` / GOT / relocation engine.
  The grammar carries a `dylink` custom section and imports the **core's** allocator
  (`env.malloc`, `env.free`, `env.realloc`, `env.calloc`, `env.memcpy`) plus its
  external-scanner functions via the GOT
  (`GOT.func.tree_sitter_python_external_scanner_scan`, …). It is meant to be linked
  *against the already-loaded core*, sharing its memory and table.

- **Second-instance bridging: DEAD END.** Because the grammar resolves `malloc`/
  `free`/`memcpy` and its scanner indices through the **core's** shared heap and
  indirect function table, it must run *inside the core's linked address space*. You
  cannot give it its own isolated `Memory` and expect the language pointer it returns
  (a `TSLanguage*` into that shared heap) to be meaningful to a separately-instanced
  core. Bridging two independent Chicory instances would require reimplementing the
  shared-memory + shared-table + base-offset + relocation contract — i.e. writing the
  emscripten linker anyway.

- **Data-exchange ABI mismatch (a second, independent blocker).** Even if linked,
  this is the **web-tree-sitter custom build**: the C API is exposed through `_wasm`
  wrapper exports (`ts_tree_root_node_wasm`, `ts_node_symbol_wasm`,
  `ts_node_type` is *not* an export, etc.) that marshal `TSNode`-by-value through a
  JS-side **TRANSFER_BUFFER** and the `env.tree_sitter_parse_callback` import. The
  marshalling reference in the task brief (`_ts_node_type` → `const char*`, hidden
  out-pointer for struct returns) describes the *raw C ABI of a standalone build*,
  which **these artifacts do not expose**. Driving them from Java would also mean
  reimplementing web-tree-sitter's JS `binding.js` marshalling layer.

**Measured average parse latency:** N/A — no parse executed (gate not reached).

---

## Core/grammar version alignment

- The pre-spike choice of core **0.22.6** (vs latest 0.26.x, which throws in
  emscripten `getDylinkMetadata`) addresses a *dylink-metadata* mismatch in the JS
  loader. It does **not** help here: the JVM path fails earlier and for a different
  reason — there is no linker at all, and the `env.abort` vs `env._abort_js` naming
  skew shows the core also predates weh's emscripten naming. No version pairing of
  *these emscripten-dynamic artifacts* makes them link on weh/Chicory, because the
  missing piece (the dynamic linker) is absent regardless of version.

---

## Why this is structural, not a config miss

- Chicory 1.5.1 is a **single-module** WASM runtime; emscripten MAIN/SIDE dynamic
  linking + `dlopen` is an **emscripten JS-glue feature**, not a wasm-runtime
  feature. ([Emscripten Dynamic Linking](https://emscripten.org/docs/compiling/Dynamic-Linking.html))
- tree-sitter's WASM packaging is **inherently dynamically-loadable** — one `.wasm`
  per grammar, linked at runtime against the core. Even the modern
  `tree-sitter build --wasm` (v0.26.1+, wasi-sdk based, no emscripten) still emits
  *dynamically-loadable* libraries, not a single statically-linked core+grammar
  blob. ([tree-sitter build docs](https://tree-sitter.github.io/tree-sitter/cli/build.html),
  [web-tree-sitter README](https://github.com/tree-sitter/tree-sitter/blob/master/lib/binding_web/README.md))

---

## Options if we want to proceed (not part of this spike; for the planning gate)

Ranked by effort/risk:

1. **Statically-linked single WASM (core + ONE grammar) compiled for WASI-standalone.**
   Build tree-sitter's C lib *together with* `tree-sitter-python.c` (+ its external
   scanner) into one `reactor`/standalone wasm via wasi-sdk, exporting the raw C ABI
   (`ts_parser_new`, `ts_parser_set_language`, `ts_parser_parse_string`,
   `ts_tree_root_node`, `ts_node_type`, …). One self-contained module → no linker, no
   GOT, owns its own memory; weh supplies only WASI/`env` syscalls. Cost: a custom
   build per language (N grammars → N wasms or one fat wasm), and writing the raw-ABI
   marshalling in Java (struct-by-value out-pointers, `_malloc`/memory read-write).
   **This is the most likely "make Chicory work" path** and would warrant its own
   spike before committing.

2. **Implement the emscripten dynamic linker on Chicory.** Host-owned shared `Memory`
   + indirect `Table`, assign `__memory_base`/`__table_base`, populate the GOT, run
   `__wasm_apply_data_relocs`, plus the web-tree-sitter TRANSFER_BUFFER marshalling.
   High effort, effectively re-deriving emscripten's loader; not recommended.

3. **Drop the pure-Java constraint:** native tree-sitter via JNI/JNA/FFM (Java 21
   Panama) over the C library. Fast and battle-tested, but reintroduces native
   binaries per platform — the thing the WASM approach was meant to avoid.

4. **JVM-native parsers per language** (e.g. existing Java/JS/Python ANTLR or
   hand-rolled parsers). Avoids WASM entirely; loses tree-sitter's uniformity.

---

## Reproduce

```bash
cd code-navigator
./gradlew test --tests 'com.codenavigator.indexer.treesitter.TreeSitterSpikeTest'
# 3 tests, all PASS — each asserts a facet of the blocker.
# gate0a_coreFailsToInstantiate  -> UnlinkableException on env.abort
# gate0a_dynamicLinkingGap       -> proves the imported-memory/table/GOT gap
# gate0b_parsePython_blocked     -> grammar exports tree_sitter_python but can't link
```
