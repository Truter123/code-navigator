# MCP Servers

Monorepo for custom Model Context Protocol (MCP) servers used across projects.

## Projects

### code-graph

Java-based MCP server that indexes and analyzes Java codebases. Builds a graph of code structure (controllers, commands, handlers, aggregates, events, projections) and exposes it via MCP tools for navigation, impact analysis, and context building.

**Tools:** `cg_chain`, `cg_impact`, `cg_context`, `cg_search`, `cg_overview`, `cg_map`, `cg_callers`, `cg_callees`, `cg_node`, `cg_status`, `cg_files`

### domain-mcp

Java-based MCP server that exposes structured business domain knowledge (bounded contexts, entities, glossary, business flows, rules). Extracts domain concepts from code-graph and stores them in SQLite for querying.

**Tools:** `dm_context`, `dm_glossary`, `dm_flow`, `dm_rules`, `dm_entity`, `dm_explain`

## Building

```bash
# Build all JARs
./jars/build-all.sh

# Build individually
cd code-graph && ./gradlew shadowJar
cd domain-mcp && ./gradlew shadowJar
```

Built JARs are copied to `jars/` for distribution.

## Running

```bash
# Via wrapper scripts
./jars/run-code-graph.sh serve
./jars/run-domain-mcp.sh serve

# Directly
java -jar jars/code-graph.jar serve
java -jar jars/domain-mcp.jar serve
```

## Structure

```
.
├── code-graph/       # Code graph MCP server (Java/Gradle)
├── domain-mcp/       # Domain knowledge MCP server (Java/Gradle)
├── jars/             # Built JARs and runner scripts
│   ├── build-all.sh
│   ├── run-code-graph.sh
│   └── run-domain-mcp.sh
└── .claude/          # Claude Code configuration
    ├── skills/       # 16 development workflow skills
    ├── commands/     # Slash commands (dashboard, research, topic, setup-code-graph)
    ├── hooks/        # Git hooks and session hooks
    └── scripts/      # Helper scripts
```

## Requirements

- Java 21+
- Gradle (wrapper included)
