# CLAUDE.md

## Project Overview

**code-navigator** — an MCP server that indexes a codebase into a graph so an agent can research it
without reading files. Java 21 / Gradle, shadow JAR.

The whole point is token cost. Reading `nlp`'s source to answer "who calls this method" costs tens of
thousands of tokens; the graph answers it in about 170. Every design decision below follows from
that, and anything that does not reduce tokens does not belong here.

## Build Commands

```bash
./jars/build-all.sh        # or: ./gradlew shadowJar
./gradlew test
```

## Project Structure

- `src/`, `build.gradle` — the server. Indexes Java, TypeScript and Groovy into a SQLite graph:
  classes, methods, and the edges between them. Nine MCP tools, all `cg_*`.
- `jars/` — built JAR, runner script, and the `auto-setup.sh` SessionStart hook.

Data lives in `navigators/code/` inside each indexed project (gitignored).

## The nine tools

| Tool | Answers |
| --- | --- |
| `cg_map` | What is this project? Tier, counts, node types, root aggregates/controllers |
| `cg_search` | Where is X? Symbols by name/text; `files=true` for paths |
| `cg_node` | What is X? Type, `file:line`, signature, direct edges. Takes `Class#method` |
| `cg_related` | What connects to X? `direction=in\|out\|both`, `depth`, `granularity` |
| `cg_guard` | Is X safe to change? Blast radius, DDD callouts, test-only fallout counted |
| `cg_context` | Where do I start? Task description → the files that matter |
| `cg_health` | `kind=hotspots\|dead\|packages\|coupling` |
| `cg_deps` | Declared libraries and how much of each is used |
| `cg_export` | `json\|mermaid\|plantuml` |

There were twenty. Consolidating to nine cut standing context from ~767 to ~277 tokens of tool
descriptions, and removed a real failure mode: `cg_chain` and `cg_overview` were byte-identical
traversals with different titles, so an agent had to guess which door to use.

**There is no domain tier.** No business rules, glossary, bounded contexts, ADRs or troubleshooting
entries — that was tried and removed. This tool scans code. A project's prose documentation stays
prose.

## Token discipline

The rules that keep responses small. Breaking one is a regression even if tests pass.

- **Every list-producing tool takes `limit` and honours it.** `cg_search` once advertised `limit` and
  ignored it, returning 3,755 tokens for a query the caller answered from the first five rows.
- **Summarise, don't enumerate.** `cg_map` prints one line per root with a reach count, not a
  per-type breakdown under each; `cg_guard` names DDD-significant nodes and counts the rest.
  Measured: 1,780 → 576 and 135 lines → 22.
- **Truncation cuts whole lines and says what was dropped**, with the parameter that would narrow the
  query. A blind `substring` severed the last entry and read exactly like a complete answer.
- **Nothing writes files.** Generated markdown was the single largest context cost in `nlp` —
  256 KB duplicating a graph that answers the same questions in hundreds of tokens.
- **Short names plus `file:line`.** The path already carries the package; repeating the fqn spends
  tokens on nothing. Ids stay fully qualified only where they must be typed back in.

Current cost on `nlp` (8,504 nodes): eight representative answers total ~2,644 tokens, none over
1,500. `java -jar jars/code-navigator.jar benchmark <project>` measures against reading raw source.

## One server, many projects

Every tool takes an optional `projectPath`. Without it, calls answer for `CODE_NAVIGATOR_PROJECT`
(set per-project in `.mcp.json`); with it, for any other indexed root, cached as a `ProjectScope`.

A `projectPath` with no index is an **error**, never a fall-back to the default project — falling
back made a typo indistinguishable from an empty project. Handlers open with
`var scope = resolveScope(args)` and must not read a store field; there isn't one. New tools go
through `guarded(() -> handler(args))` so the error surfaces as tool text, not a protocol fault.

## Method-level graph

`METHOD` nodes are addressed `<classFqn>#<name>(<simpleParamTypes>)` — build and parse ids only
through `MethodIds`. Three edges: `DECLARES_METHOD` (class→method), `CALLS` (method→method),
`OVERRIDES` (method→supertype method).

- **`DECLARES_METHOD` costs no depth and is crossed in either direction.** A class and its methods
  are one place, so `depth: 1` from a class still reaches the classes one call away. `GraphTraversal`
  uses 0-1 BFS; a plain FIFO queue settles nodes at the wrong depth.
- **Results are filtered, never the traversal.** Asking about a class gets a class-level answer,
  asking about a method gets a method-level one (`applyGranularity`); the walk always passes through
  methods.

Records, commands, queries, events and enums get no METHOD nodes — their shape is already in the
`methods` side-table, and a node per record component would multiply the graph for field reads.

**Only confident CALLS edges are emitted.** A receiver resolves through `this`, a field, a parameter,
a local, a static import, or a static call on an indexed type; anything else is dropped and counted.
Where several same-arity overloads fit, all are linked — the receiver and name are certain and only
the signature is not, so linking each over-approximates which runs, while dropping would answer
"nobody calls this". Indexing prints the bound rate; on `nlp` it is 95%, the rest Spring Data methods
inherited from `JpaRepository`. `CODE_NAVIGATOR_DEBUG_CALLS=1` lists the top unresolved shapes.

## Indexing

`indexFull` **clears edges and methods first**. Edge ids are random UUIDs and method rows
autoincrement, so neither dedupes on insert: without this a second `init` doubled every edge
(25,823 → 52,088 on nlp) and every traversal reported each neighbour twice.

Wrap bulk writes in `store.batch(...)`. Saves autocommit otherwise, which under WAL is one fsync per
row — unbatched git co-change mining alone took a full nlp index from 34 seconds to over 15 minutes.

`GitHistoryAnalyzer` skips commits touching more than 50 files. Pair count is quadratic, so one
1,000-file merge writes ~500,000 rows of files that were *merged* together, not changed together.
Without the cap, 18 such commits produced 1.6M of 1.7M rows and a 964 MB database.

## Symbol resolution

A bare name is ranked by connectivity, and the chosen node is named in the response with the
runners-up. An exact id short-circuits **only when qualified**: the TypeScript indexer stores bare
simple names as ids, so `Andon` is the *id* of an Angular model and the *name* of a backend
aggregate — preferring the id answered every aggregate question from the edge-less frontend model.

## Key Dependencies

- MCP SDK 1.1.0, JavaParser 3.26.4, SQLite JDBC 3.47.2.0, Picocli 4.7.6, Jackson 2.18.2, JGit 7.2
