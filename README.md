# MCP Servers

Monorepo for custom Model Context Protocol (MCP) servers used across projects.

## Projects

### code-navigator

Java-based MCP server that indexes and analyzes Java codebases. Builds a graph of code structure (controllers, commands, handlers, aggregates, events, projections) and exposes it via MCP tools for navigation, impact analysis, and context building. Also includes domain knowledge tools for bounded contexts, entities, glossary, business flows, and rules.

**Tools:** `cg_chain`, `cg_impact`, `cg_context`, `cg_search`, `cg_overview`, `cg_map`, `cg_callers`, `cg_callees`, `cg_node`, `cg_status`, `cg_files`, `cg_hotspots`, `cg_dead`, `cg_packages`, `cg_export`, `cg_briefing`, `dm_context`, `dm_glossary`, `dm_flow`, `dm_rules`, `dm_entity`, `dm_explain`

### agent-memory

General-purpose persistent memory engine for AI agents with knowledge graph, brain system (loop/drift/contradiction detection), and Angular 19 dashboard. 12 MCP tools prefixed `mem_*`. Dashboard at `http://localhost:7070`.

## Building

```bash
# Build all JARs
./jars/build-all.sh

# Build individually
cd code-navigator && ./gradlew shadowJar
cd agent-memory && ./gradlew shadowJar
```

Built JARs are copied to `jars/` for distribution.

## Running

```bash
# Via wrapper scripts
./jars/run-code-navigator.sh serve

# Directly
java -jar jars/code-navigator.jar serve
```

## Structure

```
.
├── code-navigator/       # Code navigator + domain MCP server (Java/Gradle)
├── agent-memory/         # Agent memory MCP server (Java/Gradle)
├── jars/                 # Built JARs and runner scripts
│   ├── build-all.sh
│   └── run-code-navigator.sh
└── .claude/              # Claude Code configuration
    ├── skills/           # Development workflow skills
    ├── commands/         # Slash commands
    ├── hooks/            # Git hooks and session hooks
    └── scripts/          # Helper scripts
```

## Requirements

- Java 21+
- Gradle (wrapper included)
