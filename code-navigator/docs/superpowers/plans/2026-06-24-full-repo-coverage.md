# Full Repo Coverage (TypeScript + Groovy + Briefing) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Index TypeScript constructs beyond service/component/interface, plus Groovy scripts, and surface them in the briefing — regex-based, additive, Java path untouched.

**Architecture:** Add 8 node types; extend `TypeScriptIndexer` with ordered detection; add a `GroovyIndexer`; wire a Groovy phase into `ProjectIndexer`; extend `BriefingGenerator` with FE/script sections.

**Tech Stack:** Java 21, Gradle (Groovy DSL, shadow plugin), JUnit 5 + AssertJ, SQLite graph store, regex (`java.util.regex`).

**Spec:** `docs/superpowers/specs/2026-06-24-full-repo-coverage-design.md`

**Paths:** repo git-root = `/home/kamil/Documents/Project/My/tools/mcp`; gradle project = `<git-root>/code-navigator` (run `./gradlew` there). Java package root `com.codenavigator`. Branch: `feat/full-repo-coverage`. **Git safety:** never `git add -A`/`git add .` — stage only files each task touches, by path; the working tree has pre-existing unrelated changes that must stay unstaged.

---

## File structure

| File | Change |
|------|--------|
| `graph/NodeType.java` | +8 enum values |
| `indexer/TypeScriptIndexer.java` | ordered detection (rules 1–11) |
| `indexer/GroovyIndexer.java` | new |
| `indexer/ProjectIndexer.java` | `*.module.ts` exclusion; `findGroovyFiles` + Groovy phase + tracking |
| `briefing/BriefingGenerator.java` | models/components/services/other FE sections |
| `*Test.java` | tests per task |

---

## Task 1: NodeType additions

**Files:** Modify `code-navigator/src/main/java/com/codenavigator/graph/NodeType.java`; Test `code-navigator/src/test/java/com/codenavigator/graph/NodeTypeEdgeTypeTest.java`

- [ ] **Step 1: Failing test** — add to `NodeTypeEdgeTypeTest`:
```java
@Test
void includes_extended_frontend_and_script_types() {
    for (String n : new String[]{"FE_CLASS","FE_ENUM","FE_PIPE","FE_GUARD",
            "FE_INTERCEPTOR","FE_VALIDATOR","FE_CONSTANT","GROOVY_SCRIPT"}) {
        org.assertj.core.api.Assertions.assertThat(NodeType.valueOf(n)).isNotNull();
    }
}
```
- [ ] **Step 2: Run, expect FAIL** — `cd code-navigator && ./gradlew test --tests 'com.codenavigator.graph.NodeTypeEdgeTypeTest'` → `No enum constant ... FE_CLASS`.
- [ ] **Step 3: Implement** — in `NodeType.java`, before `// External`:
```java
    // Frontend (extended) + scripts
    FE_CLASS, FE_ENUM, FE_PIPE, FE_GUARD, FE_INTERCEPTOR, FE_VALIDATOR, FE_CONSTANT,
    GROOVY_SCRIPT,
```
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** — `git add code-navigator/src/main/java/com/codenavigator/graph/NodeType.java code-navigator/src/test/java/com/codenavigator/graph/NodeTypeEdgeTypeTest.java && git commit -m "feat(graph): add extended FE + GROOVY_SCRIPT node types"`

---

## Task 2: TypeScriptIndexer — decorated-class rules (1–5)

Adds `FE_PIPE`, `FE_INTERCEPTOR`, `FE_GUARD`, and broadens `@Injectable`→`FE_SERVICE`. Reuse the existing `buildSnippet`/`lineAt`/`Node` conventions.

**Files:** Modify `TypeScriptIndexer.java`; Test `TypeScriptIndexerTest.java`

- [ ] **Step 1: Failing tests** (write a temp `.ts` via `@TempDir`, call `indexFile`):
  - `@Pipe()\nexport class MoneyPipe {}` → one `FE_PIPE` named `MoneyPipe`.
  - `@Injectable()\nexport class AppHttpInterceptor implements HttpInterceptor {}` → `FE_INTERCEPTOR`.
  - `@Injectable()\nexport class SalescloudGuard implements CanActivate {}` → `FE_GUARD`.
  - **Rule-5 behaviour-change test** named e.g. `injectable_without_httpclient_is_now_service`: `@Injectable()\nexport class FooService {}` (no `HttpClient`) → `FE_SERVICE`.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** — add patterns + ordered checks. A class with a decorator is classified once, in order Pipe → Interceptor → Guard → Service(HttpClient) → Service(remaining). Guard/Interceptor detection keys off class-name substring (`Guard`/`CanActivate`, `Interceptor`/`HttpInterceptor`) or `implements .*Guard`/`.*Interceptor`. Keep the existing component/model detection intact (Task 3 handles ordering w.r.t. undecorated classes).
- [ ] **Step 4: Run, expect PASS** (`./gradlew test --tests 'com.codenavigator.indexer.TypeScriptIndexerTest'`).
- [ ] **Step 5: Commit** files by path.

---

## Task 3: TypeScriptIndexer — undecorated rules (8–11) + exclusivity

Adds `FE_CLASS`, `FE_ENUM`, `FE_CONSTANT`, `FE_VALIDATOR`; enforces "no Angular decorator in file → FE_CLASS" and non-exclusive multi-node files.

**Files:** Modify `TypeScriptIndexer.java`; Test `TypeScriptIndexerTest.java`

- [ ] **Step 1: Failing tests:**
  - `export class Beneficiary { name: string; }` (no decorator) → `FE_CLASS`.
  - `export enum BeneficiaryType { PERSON, ORGANIZATION }` → `FE_ENUM`.
  - `export const PEP = 'x'; export const FOO = 1;` → two `FE_CONSTANT`.
  - `export const phoneValidator: ValidatorFn = ...` → `FE_VALIDATOR`; and a file named `split.validator.ts` with no `ValidatorFn` → `FE_VALIDATOR` (filename fallback).
  - **Exclusivity:** `@Component({selector:'a'})\nexport class AComponent {}` → `FE_COMPONENT` only, NOT `FE_CLASS`.
  - **Multi-node:** a file with one undecorated class + one enum + two consts → 4 nodes.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** — `export class` → `FE_CLASS` only when the file contains no `@Component`/`@Injectable`/`@Pipe`/`@Directive`/`@NgModule`. `export enum` → `FE_ENUM`. Each module-level `export const` → its own `FE_CONSTANT` (skip consts inside a class body). `ValidatorFn`/`AsyncValidatorFn` in body OR filename `*.validator.ts` → `FE_VALIDATOR`. Rules 9–11 run independently of the decorator check (non-exclusive).
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit.**

---

## Task 4: GroovyIndexer

**Files:** Create `indexer/GroovyIndexer.java`; Test `indexer/GroovyIndexerTest.java`

- [ ] **Step 1: Failing test:**
  - `class Pipeline { }` in `Foo.groovy` → one `GROOVY_SCRIPT` named `Pipeline`.
  - a `Jenkinsfile.groovy` with no class → one `GROOVY_SCRIPT` named `Jenkinsfile`.
- [ ] **Step 2: Run, expect FAIL** (class not defined).
- [ ] **Step 3: Implement** — `List<Node> indexFile(Path)` mirroring `TypeScriptIndexer`: regex `(?:abstract\s+)?class\s+(\w+)` → `GROOVY_SCRIPT` per class; if none, one node with symbol = filename minus `.groovy`. Absolute `filePath`, snippet first ~10 lines.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit.**

---

## Task 5: ProjectIndexer wiring

**Files:** Modify `indexer/ProjectIndexer.java`; Test `indexer/ProjectIndexerIntegrationTest.java`

- [ ] **Step 1: Failing integration test** — temp project with `pipeline.groovy` (script) + a Java file; after `indexFull`, assert a `GROOVY_SCRIPT` node exists and the Java node still exists.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement:**
  - Extend `findTsFiles` exclusion to also drop `*.module.ts` (keep `.spec.ts`/`.d.ts`).
  - Add `findGroovyFiles(projectPath)` (`.groovy`, `!isExcluded`).
  - Construct a `GroovyIndexer`; after the TS phase in `indexFull`, run it per groovy file, `store.saveNode` + `embedNode` each node (mirror the TS phase at ~lines 99–105). Add an incremental `reindexChangedGroovyFiles` mirroring `reindexChangedTsFiles`. Add groovy files to `trackFiles`.
- [ ] **Step 4: Run, expect PASS;** then **full suite** `./gradlew test` (Java-regression guard).
- [ ] **Step 5: Commit.**

---

## Task 6: BriefingGenerator — FE + script sections

**Files:** Modify `briefing/BriefingGenerator.java`; Test `briefing/BriefingGeneratorTest.java`

- [ ] **Step 1: Failing tests** — seed a `GraphStore` with FE nodes (snippets containing fields/selectors) and assert:
  - `models.md` contains `## Beneficiary (FE_CLASS)` with `name: string`; an `FE_ENUM` shows `values: PERSON, ORGANIZATION`.
  - `components.md` lists every `FE_COMPONENT` with its `selector`.
  - `services.md` has `FE_SERVICE`/`FE_PIPE`/`FE_GUARD`/`FE_INTERCEPTOR` sections after Java services.
  - `other.md` has `FE_VALIDATOR`/`FE_CONSTANT`/`GROOVY_SCRIPT` sections.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement:**
  - `generateModels()`: append FE section — fields via `(\w+)\s*[?!]?\s*:\s*([\w<>\[\]|, ]+)` on snippet (FE_CLASS/FE_MODEL), enum members via `(\w+)\s*[,=\n]` (FE_ENUM).
  - `generateComponents()`: emit every FE_COMPONENT, parse `selector` from `@Component({...})` in snippet, then list `USES_SERVICE` targets (keep existing controller lookup).
  - `generateServices()`: after Java services, append name/file listings for FE_SERVICE/FE_PIPE/FE_GUARD/FE_INTERCEPTOR.
  - Add `generateOther()` + `writeFile(outputDir, "other.md", generateOther())` in `generate()`: sections for FE_VALIDATOR/FE_CONSTANT/GROOVY_SCRIPT (name + file), omitted when empty.
- [ ] **Step 4: Run, expect PASS;** then full suite.
- [ ] **Step 5: Commit.**

---

## Task 7: Build + verify

- [ ] **Step 1:** `cd code-navigator && ./gradlew shadowJar` → BUILD SUCCESSFUL.
- [ ] **Step 2:** index a temp mixed repo:
```bash
mkdir -p /tmp/cov/cicd && printf "export enum E { A, B }\nexport const C = 1;\n" > /tmp/cov/x.ts && printf "node { echo 'hi' }\n" > /tmp/cov/cicd/Jenkinsfile.groovy
java -jar code-navigator/build/libs/code-navigator.jar init /tmp/cov
java -jar code-navigator/build/libs/code-navigator.jar status /tmp/cov
java -jar code-navigator/build/libs/code-navigator.jar briefing /tmp/cov
```
Expected: `status` nodes include `FE_ENUM`, `FE_CONSTANT`, `GROOVY_SCRIPT`; briefing `other.md` lists the constant + script.

---

## Self-review notes
- **Spec coverage:** §1→T1, §2 rules 1–5→T2, §2 rules 8–11 + exclusivity→T3, §3→T4+T5, §4→T6, rebuild→T7.
- **Type consistency:** all tasks use `indexFile(Path)→List<Node>`, the existing `Node` ctor, and `NodeType` values from T1.
- **No placeholders:** regexes and enum values are concrete; only `<repo>`/temp paths are templated.
