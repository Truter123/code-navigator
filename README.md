# MCP Servers

Custom Model Context Protocol (MCP) server for code navigation and domain knowledge.

## code-navigator

Java-based MCP server that indexes and analyzes Java codebases. Builds a graph of code structure (controllers, commands, handlers, aggregates, events, projections) and exposes it via MCP tools for navigation, impact analysis, and context building. Also includes domain knowledge tools for bounded contexts, entities, glossary, business flows, and rules.

**Code-graph tools:** `cg_chain`, `cg_impact`, `cg_context`, `cg_search`, `cg_overview`, `cg_map`, `cg_callers`, `cg_callees`, `cg_node`, `cg_status`, `cg_files`, `cg_hotspots`, `cg_dead`, `cg_packages`, `cg_export`, `cg_briefing`, `cg_guard`, `cg_coupling`, `cg_deps`

**Domain-knowledge tools:** `dm_context`, `dm_glossary`, `dm_flow`, `dm_rules`, `dm_entity`, `dm_explain`

Recent additions:
- `cg_guard` — pre-edit guard report: blast radius + touched bounded contexts + matching domain rules for a symbol.
- `cg_coupling` — files that historically change together with a symbol/file (git co-change mining); `cg_impact` is also annotated with co-change counts as a ranking signal.
- `cg_deps` — declared build dependencies with a usage count per library (via `USES_LIBRARY` edges); `cg_callees`/`cg_impact` now surface `LIBRARY` nodes.

## Building

```bash
./jars/build-all.sh
```

## Running

```bash
./jars/run-code-navigator.sh serve
```

## Token-savings benchmark

The `benchmark` CLI subcommand measures the navigator's compact output against the
naive baseline of reading every raw source file, across three scenarios
(whole-project briefing, symbol-impact blast radius, compact JSON export). Token
counts use a `chars/4` heuristic.

```bash
java -jar jars/code-navigator.jar benchmark /path/to/indexed-project
```

Measured on the local workspaces (2026-06-06):

| Project | whole-project | symbol-impact | compact-export |
|---|---:|---:|---:|
| My (monorepo root) | 2,313,811 → 37,424 (**98.4%**) | 107,412 → 9,742 (90.9%) | 2,313,811 → 285,956 (87.6%) |
| apps/woa | 260,488 → 7,162 (**97.3%**) | 28,162 → 1,074 (96.2%) | 260,488 → 21,757 (91.6%) |
| apps/woa/woa-be | 71,186 → 5,987 (91.6%) | 22,382 → 830 (96.3%) | 71,186 → 13,657 (80.8%) |
| apps/next-level | 1,746,760 → 27,156 (**98.4%**) | 140,381 → 11,245 (92.0%) | 1,746,760 → 243,376 (86.1%) |
| prototypes/pro-profit | 31,809 → 748 (**97.6%**) | 9,117 → 463 (94.9%) | 31,809 → 4,170 (86.9%) |

Cells are `baseline → navigator (savings %)`. Briefing vs reading every file is
consistently ~91–98% fewer tokens.

## Auto-Setup

A `SessionStart` hook (`jars/auto-setup.sh`) automatically:
- Adds code-navigator to `.mcp.json` for Java projects
- Adds Angular MCP for Angular frontends
- Auto-indexes on first use

## Structure

```
.
├── src/                  # Code navigator + domain MCP server source (Java)
├── build.gradle          # Gradle build (repo root is the Gradle project)
├── docs/                 # Plans and design specs
├── jars/                 # Built JAR, runner script, auto-setup
│   ├── build-all.sh
│   ├── run-code-navigator.sh
│   └── auto-setup.sh
└── .claude/              # Claude Code configuration
```

## Requirements

- Java 21+
- Gradle (wrapper included)
