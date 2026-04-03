# MCP Servers

Custom Model Context Protocol (MCP) server for code navigation and domain knowledge.

## code-navigator

Java-based MCP server that indexes and analyzes Java codebases. Builds a graph of code structure (controllers, commands, handlers, aggregates, events, projections) and exposes it via MCP tools for navigation, impact analysis, and context building. Also includes domain knowledge tools for bounded contexts, entities, glossary, business flows, and rules.

**Tools:** `cg_chain`, `cg_impact`, `cg_context`, `cg_search`, `cg_overview`, `cg_map`, `cg_callers`, `cg_callees`, `cg_node`, `cg_status`, `cg_files`, `cg_hotspots`, `cg_dead`, `cg_packages`, `cg_export`, `cg_briefing`, `dm_context`, `dm_glossary`, `dm_flow`, `dm_rules`, `dm_entity`, `dm_explain`

## Building

```bash
./jars/build-all.sh
```

## Running

```bash
./jars/run-code-navigator.sh serve
```

## Auto-Setup

A `SessionStart` hook (`jars/auto-setup.sh`) automatically:
- Adds code-navigator to `.mcp.json` for Java projects
- Adds Angular MCP for Angular frontends
- Auto-indexes on first use

## Structure

```
.
├── code-navigator/       # Code navigator + domain MCP server (Java/Gradle)
├── jars/                 # Built JAR, runner script, auto-setup
│   ├── build-all.sh
│   ├── run-code-navigator.sh
│   └── auto-setup.sh
└── .claude/              # Claude Code configuration
```

## Requirements

- Java 21+
- Gradle (wrapper included)
