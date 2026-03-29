# MCP Servers

Monorepo for custom Model Context Protocol (MCP) servers used across projects.

## Projects

### code-navigator

Java-based MCP server that indexes and analyzes Java codebases. Builds a graph of code structure (controllers, commands, handlers, aggregates, events, projections) and exposes it via MCP tools for navigation, impact analysis, and context building.

**Tools:** `cg_chain`, `cg_impact`, `cg_context`, `cg_search`, `cg_overview`, `cg_map`, `cg_callers`, `cg_callees`, `cg_node`, `cg_status`, `cg_files`

### domain-navigator

Java-based MCP server that exposes structured business domain knowledge (bounded contexts, entities, glossary, business flows, rules). Extracts domain concepts from code-navigator and stores them in SQLite for querying.

**Tools:** `dm_context`, `dm_glossary`, `dm_flow`, `dm_rules`, `dm_entity`, `dm_explain`

## Building

```bash
# Build all JARs
./jars/build-all.sh

# Build individually
cd code-navigator && ./gradlew shadowJar
cd domain-navigator && ./gradlew shadowJar
```

Built JARs are copied to `jars/` for distribution.

## Running

```bash
# Via wrapper scripts
./jars/run-code-navigator.sh serve
./jars/run-domain-navigator.sh serve

# Directly
java -jar jars/code-navigator.jar serve
java -jar jars/domain-navigator.jar serve
```

## Structure

```
.
├── code-navigator/       # Code navigator MCP server (Java/Gradle)
├── domain-navigator/       # Domain knowledge MCP server (Java/Gradle)
├── jars/             # Built JARs and runner scripts
│   ├── build-all.sh
│   ├── run-code-navigator.sh
│   └── run-domain-navigator.sh
└── .claude/          # Claude Code configuration
    ├── skills/       # 16 development workflow skills
    ├── commands/     # Slash commands (dashboard, research, topic, setup-code-navigator)
    ├── hooks/        # Git hooks and session hooks
    └── scripts/      # Helper scripts
```

## Requirements

- Java 21+
- Gradle (wrapper included)
