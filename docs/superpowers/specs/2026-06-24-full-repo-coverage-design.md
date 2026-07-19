# Full Repo Coverage — TypeScript + Groovy + Briefing (no Kotlin)

**Date:** 2026-06-24 (supersedes the handwritten 2026-06-22 draft)
**Project:** `mcp/code-navigator`
**Goal:** Index ~100% of a frontend/CI repo (e.g. ABZ) — TypeScript constructs
beyond components/services/interfaces, plus Groovy CI/CD scripts — and surface
them in the generated briefing. Additive patches only; **no structural refactor**;
the Java/JavaParser path is untouched.

> Context: the alternative "make it generic via tree-sitter" direction was explored
> and **paused** — the published web-tree-sitter WASM artifacts require an
> emscripten dynamic linker that Chicory/wasi-emscripten-host does not provide
> (see `docs/superpowers/specs/2026-06-23-tree-sitter-spike-result.md` on branch
> `feat/universal-parsing-tree-sitter`). This spec is the pragmatic,
> regex-based path instead.

---

## Problem

`TypeScriptIndexer` today detects only three constructs (regex):
`@Injectable`+`HttpClient` → `FE_SERVICE`, `@Component` → `FE_COMPONENT`,
`export interface` → `FE_MODEL`. Everything else in a typical Angular repo is
invisible. Current coverage gaps:

| File type | Example | Current nodes |
|-----------|---------|---------------|
| `export class` (no decorator) | `Beneficiary`, `CommissionData` | 0 |
| `export enum` | `BeneficiaryType`, `OfferType` | 0 |
| `export const` (module-level) | `pep-info-message.const.ts` | 0 |
| `*.validator.ts` | `split.validator.ts` | 0 |
| `@Pipe` class | — | 0 |
| `@Injectable` Guard / Interceptor | `salescloud.guard.ts`, `app-http-interceptor.ts` | 0 |
| `.groovy` scripts | `CICD/` | 0 |

Additionally the briefing's `models.md`/`components.md` are empty or near-empty in
practice despite FE nodes existing, and there is no place for validators,
constants, or scripts.

---

## Section 1 — New node types

Add to `src/main/java/com/codenavigator/graph/NodeType.java` (current enum ends with
`FE_SERVICE, FE_COMPONENT, FE_MODEL,` then `// External` / `LIBRARY`). Insert a new
group before `// External`:

```java
    // Frontend (extended) + scripts
    FE_CLASS, FE_ENUM, FE_PIPE, FE_GUARD, FE_INTERCEPTOR, FE_VALIDATOR, FE_CONSTANT,
    GROOVY_SCRIPT,
```

All new types are valid on every tier (GENERIC/CRUD/DDD).

`NodeTypeEdgeTypeTest` gets assertions that each new value exists.

---

## Section 2 — TypeScriptIndexer ordered detection

File: `src/main/java/com/codenavigator/indexer/TypeScriptIndexer.java`.

Detection is **file-content scanned**; order matters (first match wins for a given
class). The list:

| # | Trigger | Node type | Status |
|---|---------|-----------|--------|
| 1 | `@Pipe` decorator on class | `FE_PIPE` | new |
| 2 | `@Injectable` **and** (`HttpInterceptor`/`Interceptor` in class name **or** `implements .*Interceptor`) | `FE_INTERCEPTOR` | new |
| 3 | `@Injectable` **and** (`CanActivate`/`Guard` in class name **or** `implements .*Guard`) | `FE_GUARD` | new |
| 4 | `@Injectable` **and** `HttpClient` in file body | `FE_SERVICE` | existing |
| 5 | `@Injectable` (any remaining) | `FE_SERVICE` | **behaviour change** |
| 6 | `@Component` | `FE_COMPONENT` | existing |
| 7 | `export interface` | `FE_MODEL` | existing |
| 8 | `export class` with **no Angular decorator in the file** | `FE_CLASS` | new |
| 9 | `export enum` | `FE_ENUM` | new |
| 10 | `export const` at module level (not inside a class body) | `FE_CONSTANT` | new |
| 11 | `ValidatorFn`/`AsyncValidatorFn` in body **OR** filename `*.validator.ts` | `FE_VALIDATOR` | new |

**Exclusivity rules:**
- Rules **1–7 are exclusive per class**: a class matched by a decorator (1–6) or an
  exported interface (7) is classified once; an `@Component` class is **not** also
  `FE_CLASS`.
- Rules **8–11 are non-exclusive**: one file may yield several nodes (e.g. a file
  exporting a class, an enum, and three consts → 1 `FE_CLASS` + 1 `FE_ENUM` +
  3 `FE_CONSTANT`).
- Rule 8 fires only when the file contains **no** Angular decorator
  (`@Component`/`@Injectable`/`@Pipe`/`@Directive`/`@NgModule`).
- Rule 10 yields **one `FE_CONSTANT` per exported const**.
- Rule 11 filename match is the fallback for function-style validators with no class.

**⚠ Behaviour change (rule 5):** today an `@Injectable` service is only detected
when the file also contains `HttpClient`. Rule 5 makes *every* remaining
`@Injectable` (after pipe/guard/interceptor) a `FE_SERVICE`. This is intended (the
2026-06-22 design); document it in the test names.

**Still excluded (intentionally, 0 nodes):** `*.spec.ts`, `*.d.ts`,
`*-routing.module.ts`, `*.module.ts` (routing / Angular-module glue, no
navigational value). These exclusions are applied in `ProjectIndexer.findTsFiles`
(extend the existing `.spec.ts`/`.d.ts` filter to also drop `*.module.ts`).

**Implementation notes:** keep the existing regex/`buildSnippet`/`lineAt` style.
Node id/name = class (or const/symbol) name; `qualifiedName` = same; `filePath` =
absolute path (matches current TS behaviour); snippet = first ~10 lines from the
match. Reuse the existing `Node` shape used by the current three detectors.

---

## Section 3 — New GroovyIndexer

New file: `src/main/java/com/codenavigator/indexer/GroovyIndexer.java`, modeled on
`TypeScriptIndexer` (same `List<Node> indexFile(Path)` shape, different parsing).

**Detection:**
- Named class: `(?:abstract\s+)?class\s+(\w+)` → `GROOVY_SCRIPT` node, symbol = class name.
- Script file with no class declaration → one `GROOVY_SCRIPT` node, symbol = filename
  without extension.

**Scope:** scans all `.groovy` files; excludes `node_modules/`, `build/`, `.gradle/`
(reuse `ProjectIndexer.isExcluded`). **No edges** (too few files; no cross-file
relationships).

**Integration:** `ProjectIndexer` runs `GroovyIndexer` as a new phase **after** the
Java and TypeScript phases, in both `indexFull` and `indexIncremental`, and adds the
`.groovy` files to `trackFiles` (absolute-path tracking, like TS). Add
`findGroovyFiles(projectPath)` alongside `findTsFiles`.

---

## Section 4 — Briefing generator fixes

File: `src/main/java/com/codenavigator/briefing/BriefingGenerator.java`.

### `models.md` — add FE field extraction
`generateModels()` is currently Java-only (RECORD/COMMAND/QUERY via method-records).
Add a frontend section: for `FE_CLASS` and `FE_MODEL` nodes, extract fields from the
node's `codeSnippet` with `(\w+)\s*[?!]?\s*:\s*([\w<>\[\]|, ]+)`; for `FE_ENUM`,
extract members with `(\w+)\s*[,=\n]`. Output:

```
## Beneficiary (FE_CLASS)
   type: BeneficiaryType
   allocation: number
   name: string

## BeneficiaryType (FE_ENUM)
   values: PERSON, ORGANIZATION
```

### `components.md` — populate
`generateComponents()` currently emits a component only when it has a `USES_SERVICE`
edge (so it's empty in practice). Change to: emit **every** `FE_COMPONENT`, add its
`selector` parsed from the `@Component({ ... })` metadata in the snippet, then list
connected `FE_SERVICE`s (via `USES_SERVICE`, keeping the existing controller lookup).

### `services.md` — append FE types
`generateServices()` currently lists Java `SERVICE` nodes only. After that section,
append name/file listings for `FE_SERVICE`, `FE_PIPE`, `FE_GUARD`, `FE_INTERCEPTOR`
(these TS nodes have no extracted methods, so list by name + file).

### `other.md` — new file
New briefing file for the remaining types, written by `generate()` alongside the
others:

```
## FE_VALIDATOR
   split.validator.ts — SplitValidator

## FE_CONSTANT
   pep-info-message.const.ts — PEP_INFO_MESSAGE

## GROOVY_SCRIPT
   CICD/Jenkinsfile.groovy
```

(Each section omitted when it has no nodes, matching `writeFile`'s null/empty skip.)

---

## Out of scope

Kotlin source, JavaScript/JSX, a `.code-navigator.json` config, cross-file Groovy
edges, Angular module/routing nodes. (Multi-language via tree-sitter is paused — see
the context note above.)

---

## Testing

- `TypeScriptIndexerTest`: one case per new rule (1–3, 8–11), plus ordering cases
  (a guard file is `FE_GUARD` not `FE_SERVICE`; a decorated class is not `FE_CLASS`)
  and a multi-node file (class + enum + 2 consts → 4 nodes). Name the rule-5 test to
  flag the behaviour change.
- `GroovyIndexerTest` (new): named-class file → symbol = class name; script-only file
  → symbol = filename.
- `BriefingGeneratorTest`: `models.md` FE fields/enum values; `components.md` selector
  + services; `services.md` FE sections; `other.md` validator/constant/script sections.
- `NodeTypeEdgeTypeTest`: the 8 new enum values.
- A `ProjectIndexer` integration case: a temp project with a `.groovy` file produces a
  `GROOVY_SCRIPT` node and an all-Java project's node/edge counts are unchanged
  (regression guard).

---

## Rebuild & re-index

```bash
cd code-navigator && ./gradlew shadowJar
java -jar build/libs/code-navigator.jar init <repo>
java -jar build/libs/code-navigator.jar briefing <repo>
```

**Expected:** files that previously produced 0 nodes (export-class/enum/const/
validator/guard/interceptor/pipe/groovy) now produce nodes; `models.md`,
`components.md`, and `other.md` are populated.
