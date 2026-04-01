# CLAUDE.md

## Project Overview

Monorepo for three custom MCP servers: **code-navigator**, **domain-navigator**, and **agent-memory**. All are Java 21 / Gradle projects that produce shadow JARs.

## Build Commands

```bash
# Build all
./jars/build-all.sh

# Build individually
cd code-navigator && ./gradlew shadowJar
cd domain-navigator && ./gradlew shadowJar
cd agent-memory && ./gradlew shadowJar
```

## Project Structure

- `code-navigator/` — Code navigator MCP server. Indexes Java codebases into a SQLite-backed graph (nodes: controllers, commands, handlers, aggregates, events, projections; edges: calls, handles, emits, etc.). 11 MCP tools for navigation and impact analysis.
- `domain-navigator/` — Domain knowledge MCP server. Extracts and serves business domain concepts (bounded contexts, entities, glossary, flows, rules) from code-navigator data. 6 MCP tools.
- `agent-memory/` — Agent memory MCP server. General-purpose persistent memory engine for AI agents with knowledge graph, brain system (loop/drift/contradiction detection), and Angular 19 dashboard. 12 MCP tools prefixed `mem_*`. Dashboard at `http://localhost:7070`.
- `jars/` — Pre-built JARs and runner scripts for all servers.

## MCP Servers Available

- **code-navigator** — defined in `code-navigator/.mcp.json`, tools prefixed `cg_*`
- **domain-navigator** — configured externally, tools prefixed `dm_*`
- **agent-memory** — configured externally, tools prefixed `mem_*`
- **my-mcp** — configured externally, provides `activity_today`, `kb_list`, `activity_repos`

## Key Dependencies

- MCP SDK 1.1.0 (`io.modelcontextprotocol.sdk`)
- JavaParser 3.26.4 (code-navigator)
- SQLite JDBC 3.47.2.0
- Picocli 4.7.6 (CLI)
- Jackson 2.18.2 (JSON)
- Javalin 7.0.1 (agent-memory HTTP server)

## Conventions

- All projects use the Shadow Gradle plugin to produce fat JARs
- Version for all: `0.1.0`
- code-navigator and domain-navigator store data in `navigators/` inside indexed projects
- agent-memory stores data globally at `~/.agent-memory/memory.db`
- domain-navigator uses `DOMAIN_NAVIGATOR_PROJECT` env var to specify which projects to serve
