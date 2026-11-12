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

- `src/`, `build.gradle` — the server. Indexes Java, TypeScript, Groovy, Dart, Python, SQL and shell into a
  SQLite graph: classes, methods, and the edges between them. Python, SQL and shell plug in through
  `LanguageIndexer`; Java, TS, Groovy and Dart are still wired by hand in `ProjectIndexer`. Nine MCP tools, all `cg_*`.
- `jars/` — built JAR, runner script, and the `auto-setup.sh` SessionStart hook.

Data lives in `navigators/code/` inside each indexed project (gitignored).

## The nine tools

| Tool | Answers |
| --- | --- |
| `cg_map` | What is this project? Tier, counts, node types, root aggregates/controllers |
| `cg_search` | Where is X? Symbols by name/text; `files=true` for paths |
| `cg_node` | What is X? Type, `file:line`, signature, direct edges. Takes `Class#method` |
| `cg_related` | What connects to X? `direction=in\|out\|both` over every edge type, `depth`, `granularity` |
| `cg_guard` | Is X safe to change? What depends on X: blast radius, DDD callouts, test-only fallout counted. `diff=true\|<ref>` answers for the changed symbols |
| `cg_context` | Where do I start? Task description → the ranked files that matter, at most `limit` |
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
- **Summarise, don't enumerate.** `cg_map` lists bare root names and states the reach they share
  once instead of a count per root, counts the test roots it leaves out and prints no paths;
  `cg_guard` names DDD-significant nodes, counts the rest and the test-only share, and ends with the
  `cg_related` call that lists the type rows (methods left out; a reply holds only as many as the cap allows). Measured: 1,780 → 576 and 135 lines → 22.
- **Truncation cuts whole lines and says what was dropped**, with the parameter that would narrow the
  query. A blind `substring` severed the last entry and read exactly like a complete answer.
  Every answer is capped at 6,000 characters (about 1,500 tokens): a list tool whose `limit` would pass
  it is re-run with the largest `limit` that fits and says so, and a cut note names only that tool's
  own parameters.
- **Nothing writes files.** Generated markdown was the single largest context cost in `nlp` —
  256 KB duplicating a graph that answers the same questions in hundreds of tokens.
- **Short names plus `file:line`.** The path already carries the package; repeating the fqn spends
  tokens on nothing. Ids stay fully qualified only where they must be typed back in.
- **`cg_context` ranks, it does not expand.** Personalised PageRank from the keyword seeds
  (`PersonalizedPageRank`: undirected, `sqrt(count)` edge weights, damping 0.85, 20 iterations) fills
  at most `limit` files. The earlier version added each seed's whole connected component: 1,087 nodes
  across 95 files for one query on this repo. `ContextReport` renders the reply for the tool and for
  the benchmark, so the benchmark measures what an agent gets.

Current cost on a copy of `nlp-be` (21,368 nodes, 81,485 edges, measured 2026-11-12): the six-call
research ladder (`cg_map`, `cg_context` "cancel a sales order", then `cg_node`, `cg_related`,
`cg_guard` on `SalesOrder`, `cg_health hotspots`) is 9,641 bytes, about 2,400 tokens at ~4 bytes
each; its largest answer, `cg_context`, is 1,971 bytes, and no answer can pass the 6,000-character cap.
The October jar gave 9,691 bytes for the same calls on a graph with 62,425 edges: `cg_node` and
`cg_related` grew with the edges (1,637 → 1,961 and 1,402 → 1,797), `cg_context` and `cg_guard`
shrank (2,356 → 1,971 and 2,258 → 1,898). The nine tool definitions are 4,595 bytes of JSON (4,405
before `cg_guard` got `diff` and `limit`).

`java -jar jars/code-navigator.jar benchmark <project>` measures against reading raw source, and
both tool scenarios measure the reply an agent really gets: `symbol-impact` is the `cg_guard` reply
for the busiest non-library symbol, `semantic-context` the `cg_context` reply (`--query` sets the
task; `n/a` when the query has no keyword seed). On that copy: `symbol-impact` 377,588 -> 61 tokens
(`cg_guard` on `CommandBus#dispatch`, 825 dependents in 202 files), `semantic-context` 328,487 -> 456,
`compact-export` -93.4% (the full export is larger than the source). **Do not quote the two
percentages as a saving.** They compare a capped reply with reading every related file whole, so
they are near 100% by construction; they show that the reply stays small, not what a session saves.
`symbol-impact` used to print one line per node of the both-direction walk through a formatter no
tool has, 228,230 "navigator" tokens for the same question.

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
- **On a directional query a class entered from its own method is context only and expands
  nothing.** Without this, `direction=in` on `SalesOrder#cancel` walked method → class → all 55
  sibling methods and the class's own `INJECTS`/`USES_SERVICE` edges, and reported them as callers
  (19 rows for 3 real callers). A class that is the start node, or reached over a directed edge,
  expands as before.
- **Results are filtered, never the traversal.** Asking about a class gets a class-level answer,
  asking about a method gets a method-level one (`applyGranularity`); the walk always passes through
  methods.

Records, commands, queries, events and enums get no METHOD nodes — their shape is already in the
`methods` side-table, and a node per record component would multiply the graph for field reads.

Constructors are METHOD nodes `<classFqn>#<init>(<params>)` (`MethodIds.INIT`), for the same types
that get METHOD nodes. An instance initializer block belongs to every declared constructor, or to
one implicit `<init>()` when the class declares none; a class with neither gets no `<init>` node,
and `new X()` on it leaves only the class-level `CALLS_METHOD` edge. `<init>` nodes are never
dead-code candidates and never linked by `OVERRIDES`. Static initializer blocks are not walked.

**Only confident CALLS edges are emitted.** A receiver resolves through `this`, a field, a parameter,
a local, a static import, a static call on an indexed type, `new X(...)`, a method reference
(`Foo::bar`, `this::bar`), or a chain (`a.b().c()`) link by link while each inner call's declared
return type resolves to one indexed type (a parameterised or JDK return type ends the chain);
anything else is dropped and counted.
Where several same-arity overloads fit, all are linked — the receiver and name are certain and only
the signature is not, so linking each over-approximates which runs, while dropping would answer
"nobody calls this". Indexing prints the bound rate; on `nlp-be` it is 97% (34,516 of 35,700), the
rest mostly Spring Data methods inherited from `JpaRepository`. `CODE_NAVIGATOR_DEBUG_CALLS=1` lists
the top unresolved shapes.

A simple type name resolves in one place, `JavaTypeResolver`, for call edges and class-level edges
alike, in Java's own order: a member type in scope (declared in the enclosing type, inherited from
its supertypes, then each enclosing level outward), an explicit single-type import, the same
package, then a unique simple name, a unique wildcard-import hit, and the unique `.java` candidate
when the remaining matches are other languages. An import of a type that is not indexed resolves to
nothing: it shadows a same-package type, and binding to that type was a wrong edge
(`ThumbnailGenerator` imports a library `Thumbnails` and was linked to a nested project class).
A dotted name (`Outer.Inner`, or fully qualified) resolves its first segment and appends the rest.
`Andon` and `SalesOrder` exist as a Java aggregate and an Angular model in `nlp`; a plain
unique-simple-name rule dropped every call on them and the graph answered "no production caller" for
`Andon#resolve` and `SalesOrder#cancel`. Two Java candidates with no import still resolve to none.
Inherited member types are followed through indexed Java supertypes only, read from their source
files; a supertype from a library is not.

**A directional walk follows every edge type in its direction.** Into X, every edge means "the
source depends on X": a call, but also `EXTENDS`, `LOADS_AGGREGATE`, `APPLIES_EVENT`,
`PROJECTS_EVENT`. With call edges only, a domain event had no dependents at all, because its
handlers reach it through the event edges. `cg_guard` counts exactly that incoming walk. It used
the undirected one, which also took what X calls and every sibling method of each class on the way:
`cg_guard GraphStore` said 1,356 nodes on this repo and now says 647. A count taken straight from
the database agrees (132 of 132 for `SalesOrder` at depth 1 on `nlp-be`).

## Indexing

`indexFull` **clears edges and methods first**. Edge ids are random UUIDs and method rows
autoincrement, so neither dedupes on insert: without this a second `init` doubled every edge
(25,823 → 52,088 on nlp) and every traversal reported each neighbour twice. It clears every node
too, inside the same transaction: leftover LIBRARY nodes took part in name resolution, so a second
`init` bound different edges than the first and `USES_LIBRARY` fell to 0, and a node of an id the
indexer no longer produces would stay for ever. The edge rebuild of a `sync` still clears LIBRARY and
METHOD nodes only.

`project_config` holds `index.format`. An index without the current value is rebuilt in full by its
first `sync` (`Sync: full edge rebuild (index format changed).`); the server still answers from it
until then. Raise the value whenever a change alters node ids.

**`sync` has a fast path, and equality with `init` is its contract.** When every changed file is a
`.java` file whose declared shape is unchanged (package, imports, type ids and node types,
supertypes, type parameters, method ids and signatures with return types; one hash per file in
`file_shapes`), only those files are re-extracted and their nodes are updated in place — never
`deleteNode`, which drops the edges other files have into the node. Anything else (new or deleted
file, non-Java change, changed shape, changed build file, more than 30% of the Java files, an index
with no stored shape) runs the full edge rebuild, which is always correct. `sync` prints which path
ran and why; `CODE_NAVIGATOR_FULL_SYNC=1` forces the rebuild. On the `nlp-be` copy a one-file body
edit went from 24–36 s to 0.7–1.6 s. `FastSyncEqualsFullIndexTest` compares the two graphs; a new
edit kind goes there first.

**A full index and a full-rebuild `sync` are one transaction each.** The rebuild clears every edge
before it re-emits them, and the session hook kills a run after 15 minutes: a run killed in between
used to leave an index that answered "nobody calls this" for everything. Now it rolls back to the
graph that was there (checked with `kill -9` on the `nlp-be` copy, at no cost in time).

The declared dependencies are part of what `sync` compares. A build-file-only edit used to trigger
no work, so `cg_deps` stayed stale; and only `init` stored the list, so after one dependency edit
every later `sync` reported `build file changed` and the fast path stayed off until the next `init`.

`<root>/.claude/worktrees` is never walked, and rows an older jar stored for it are pruned. Each git
worktree there is a full copy of the project with the same node ids: the `nlp` root held 76 of them,
207,898 of its 213,717 indexed files, and neither `init` nor `sync` finished inside the hook's 15
minutes (after the fix: 70 s, and the database went from 3.8 GB with its WAL to 129 MB). A worktree
is indexed as its own root when a session opens it.

A nested Java type has the id `<package>.<Outer>.<Inner>` at every depth, built only by
`JavaTypeResolver.typeId`; its `name` is the simple name, and its package is its outer type's
package (`PackageNames`). The id used to be `<package>.<Inner>`: two files in one package with a
same-named nested type shared one node (14 such ids on `nlp-be`, among them the `Chain` of the
command bus and of the query bus), and calls bound to whichever file came last. Files are still
indexed in sorted path order, so two top-level types with one fully qualified name give the same
graph on every run.

SQL is whole-history: a table's node is what the migrations say *today* (`V2` before `V10`,
`DROP` removes, `RENAME TO` renames in place), so any changed `.sql` file rebuilds every SQL node
(`SqlIndexer.rebuildAllOnChange`) and re-runs the edge pass. SQL node ids are stable
(`sql:<table>`), so a Java class's `REFERENCES_TABLE` edge survives an SQL-only sync. SQL nodes
must be stored before the Java and Python edge passes: they only emit `REFERENCES_TABLE` to a
table that already exists, never create one.

`GitHistoryAnalyzer` skips commits touching more than 50 files. Pair count is quadratic, so one
1,000-file merge writes ~500,000 rows of files that were *merged* together, not changed together.
Without the cap, 18 such commits produced 1.6M of 1.7M rows and a 964 MB database.

## Staleness

`init` and `sync` store the git HEAD sha (`index.head`) and the sync time in `project_config`.
`cg_map` always prints `- **Index:** current`, or what is behind (`2 commit(s) behind HEAD`,
`dirty flag set`). Every other tool prints nothing when current and one first line when stale:
`Index stale: 2 commit(s) behind, run sync.` A current index costs zero characters. The check reads
HEAD through JGit and the `.dirty` flag file; no file is hashed per call. Unknown is not stale: no
git repo, an index made by an older jar, or a failed check prints nothing.

## Diff-aware guard

`cg_guard diff=true` covers uncommitted changes against HEAD; `diff=<ref>` compares the ref with the
working tree, so committed and uncommitted work since the ref both count. `DiffSymbols` maps changed
lines to symbols. Java: the working file is parsed and each hunk mapped to a method id; ids are
name-based, so a stale line number in the index does not matter, and a method in the index but gone
from the file counts as changed. Other languages: the file's type nodes. The reply has `### Changed`,
`### Direct callers`, the blast radius and `### Tests reached`. No rename detection.

## Dead code and dependencies

`cg_health kind=dead` never lists an entry point or a constructor. Entry points (`static
main(String[])`, a picocli `@Command` checked against its import) are decided from the AST at index
time and stored in `entry_points`; the 10-line node snippet does not always hold the annotation. `new X(...)` and a static call on a type emit a class-level `CALLS_METHOD` edge even
when no method edge binds. `cg_deps` counts a library as used when one of its types is imported,
annotations included (`Dependency.owns`). Before this the report held 22 HIGH false alarms on this
repo and called picocli unused.

## Symbol resolution

A dotted symbol that is not an exact id matches an id that ends with it at a segment boundary, so
`Outer.Inner` and `Outer.Inner#method` find a nested type.

A bare name is ranked by connectivity, and the chosen node is named in the response with the
runners-up. An exact id short-circuits **only when qualified**: the TypeScript indexer stores bare
simple names as ids, so `Andon` is the *id* of an Angular model and the *name* of a backend
aggregate — preferring the id answered every aggregate question from the edge-less frontend model.

## Key Dependencies

- MCP SDK 1.1.0, JavaParser 3.26.4, SQLite JDBC 3.47.2.0, Picocli 4.7.6, Jackson 2.18.2, JGit 7.2
