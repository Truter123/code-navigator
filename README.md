# code-navigator

An MCP server that indexes a codebase into a graph so an AI agent can research it **without reading
files** — the point is token cost, not documentation.

Reading `nlp`'s source to answer "who calls this method" costs tens of thousands of tokens. The graph
answers it in about 170.

## Tools

Nine, all `cg_*`:

| Tool | Answers |
| --- | --- |
| `cg_map` | What is this project? Tier, counts, node types, root aggregates/controllers |
| `cg_search` | Where is X? Symbols by name or text; `files=true` for paths |
| `cg_node` | What is X? Type, `file:line`, signature, direct edges. Takes `Class#method` |
| `cg_related` | What connects to X? `direction=in\|out\|both`, `depth`, `granularity` |
| `cg_guard` | Is X safe to change? Blast radius, DDD callouts, test-only fallout counted |
| `cg_context` | Where do I start? Task description → the files that matter |
| `cg_health` | `kind=hotspots\|dead\|packages\|coupling` |
| `cg_deps` | Declared libraries and how much of each is used |
| `cg_export` | `json\|mermaid\|plantuml` |

Every tool takes an optional `projectPath`, so one server answers for any indexed repo.

## What it indexes

Java, TypeScript and Groovy into a SQLite graph of classes **and methods**. Method nodes are
addressed `Class#method(ParamTypes)` and carry `CALLS` and `OVERRIDES` edges, so "who calls this
method" is a graph query rather than a search. On `nlp`: 8,504 nodes, 25,823 edges, 95% of calls into
project types bound to a specific method, indexed in ~34 seconds.

It does **not** hold business rules, glossary terms, ADRs or troubleshooting notes. That was built
and removed — this tool scans code.

## Token cost

Eight representative answers on `nlp`, measured through the MCP protocol:

| Tool | Tokens |
|---|---:|
| `cg_deps` | 20 |
| `cg_health` | 123 |
| `cg_related` | 174 |
| `cg_search` | 200 |
| `cg_guard` | 236 |
| `cg_node` | 459 |
| `cg_map` | 576 |
| `cg_context` | 856 |
| **total** | **~2,644** |

Plus ~277 tokens of standing tool descriptions. The same eight answers cost ~10,323 tokens before the
limits and summarisation went in.

```bash
java -jar jars/code-navigator.jar benchmark /path/to/indexed-project
```

measures navigator output against reading the raw source it replaces.

## Building and running

```bash
./jars/build-all.sh
java -jar jars/code-navigator.jar init /path/to/project     # ~34s on a 2,165-file repo
./jars/run-code-navigator.sh serve
```

`jars/auto-setup.sh` is a `SessionStart` hook that adds the server to a project's `.mcp.json` and
auto-indexes on first use.

## Requirements

Java 21+, Gradle (wrapper included).
