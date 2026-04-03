# CLAUDE.md

## Project Overview

Custom MCP server: **code-navigator**. Java 21 / Gradle project that produces a shadow JAR.

## Build Commands

```bash
# Build
./jars/build-all.sh

# Or directly
cd code-navigator && ./gradlew shadowJar
```

## Project Structure

- `code-navigator/` — Code navigator MCP server. Indexes Java codebases into a SQLite-backed graph (nodes: controllers, commands, handlers, aggregates, events, projections; edges: calls, handles, emits, etc.). 22 MCP tools: 16 `cg_*` tools for navigation, impact analysis, dead code detection, hotspot analysis, package dependencies, export, and compact index generation + 6 `dm_*` tools for domain knowledge (bounded contexts, entities, glossary, flows, rules).
- `jars/` — Pre-built JAR, runner script, and auto-setup hook.

## MCP Server

- **code-navigator** — tools prefixed `cg_*` and `dm_*`, auto-configured via `jars/auto-setup.sh` SessionStart hook

## Key Dependencies

- MCP SDK 1.1.0 (`io.modelcontextprotocol.sdk`)
- JavaParser 3.26.4
- SQLite JDBC 3.47.2.0
- Picocli 4.7.6 (CLI)
- Jackson 2.18.2 (JSON)

## Conventions

- Shadow Gradle plugin for fat JAR
- Version: `0.1.0`
- Stores data in `navigators/` inside indexed projects (both code graph and domain knowledge)
