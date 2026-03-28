# CLAUDE.md

## Project Overview

Monorepo for two custom MCP servers: **code-graph** and **domain-mcp**. Both are Java 21 / Gradle projects that produce shadow JARs.

## Build Commands

```bash
# Build all
./jars/build-all.sh

# Build individually
cd code-graph && ./gradlew shadowJar
cd domain-mcp && ./gradlew shadowJar
```

## Project Structure

- `code-graph/` — Code graph MCP server. Indexes Java codebases into a SQLite-backed graph (nodes: controllers, commands, handlers, aggregates, events, projections; edges: calls, handles, emits, etc.). 11 MCP tools for navigation and impact analysis.
- `domain-mcp/` — Domain knowledge MCP server. Extracts and serves business domain concepts (bounded contexts, entities, glossary, flows, rules) from code-graph data. 6 MCP tools.
- `jars/` — Pre-built JARs and runner scripts for both servers.

## MCP Servers Available

- **code-graph** — defined in `code-graph/.mcp.json`, tools prefixed `cg_*`
- **domain-mcp** — configured externally, tools prefixed `dm_*`
- **my-mcp** — configured externally, provides `activity_today`, `kb_list`, `activity_repos`

## Key Dependencies

- MCP SDK 1.1.0 (`io.modelcontextprotocol.sdk`)
- JavaParser 3.26.4 (code-graph)
- SQLite JDBC 3.47.2.0
- Picocli 4.7.6 (CLI)
- Jackson 2.18.2 (JSON)

## Conventions

- Both projects use the Shadow Gradle plugin to produce fat JARs
- Version for both: `0.1.0`
- code-graph stores its graph in `.code-graph/` inside indexed projects
- domain-mcp stores its data in `.domain/domain.db` inside target projects
- domain-mcp uses `DOMAIN_PROJECTS` env var to specify which projects to serve
