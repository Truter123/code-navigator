# Agent Memory — Design Spec

**Date:** 2026-04-01
**Status:** Approved

## Overview

A general-purpose agent memory engine delivered as an MCP server with a web dashboard. Any AI agent (Claude Code, Gemini CLI, etc.) can store, recall, search, and share persistent memories across sessions. A "brain" system detects loops, goal drift, and contradictions. A full-featured Angular dashboard provides monitoring, exploration, and management.

Inspired by Octopoda, but built as a Java 21 MCP server fitting the existing monorepo pattern.

## Architecture

**Single-process monolith:** One shadow JAR runs both the MCP stdio transport (for agent tool calls) and a Javalin HTTP server (for the Angular dashboard + REST API). Both share the same `MemoryStore` backed by a global SQLite database at `~/.agent-memory/memory.db`.

- **MCP stdio** — main thread, handles tool calls from CLI agents
- **Javalin HTTP** — background daemon threads on port 7070 (configurable), serves Angular SPA + REST API
- **SQLite WAL mode** — allows concurrent reads from dashboard while MCP writes
- **Logging** — all Javalin/Jetty logging redirected to stderr. No library may write to stdout, as it would corrupt the MCP JSON-RPC stream over stdio.

### Startup Sequence

1. Initialize SQLite database (create tables if needed, set PRAGMAs)
2. Create shared `MemoryStore` instance
3. Start Javalin HTTP server on a daemon thread (non-blocking)
4. Start MCP stdio transport on the main thread
5. Block main thread with `Thread.currentThread().join()`

### Why this approach

- Fits the monorepo's one-JAR-per-server pattern (code-navigator, domain-navigator)
- No SQLite concurrency issues (single process owns the database)
- One command to start everything: `java -jar agent-memory-0.1.0.jar serve`

## Project Structure

```
agent-memory/
├── build.gradle
├── src/main/java/com/agentmemory/
│   ├── AgentMemoryApplication.java     # picocli entry point
│   ├── cli/
│   │   └── ServeCommand.java           # starts MCP + HTTP
│   ├── mcp/
│   │   └── AgentMemoryMcpServer.java   # 12 MCP tools
│   ├── store/
│   │   ├── MemoryStore.java            # SQLite DAO for memories
│   │   └── GraphStore.java             # knowledge graph relationships
│   ├── brain/
│   │   ├── LoopDetector.java           # repeated pattern detection
│   │   ├── DriftDetector.java          # goal drift detection
│   │   └── ContradictionDetector.java  # conflicting memories
│   ├── model/
│   │   ├── Memory.java                 # core memory entity
│   │   ├── MemoryVersion.java          # versioned snapshots
│   │   ├── MemoryLink.java             # graph edges
│   │   └── Goal.java                   # agent goals
│   └── api/
│       └── DashboardApi.java           # Javalin REST routes
├── src/main/resources/
│   └── static/                         # Angular build output
└── angular/                            # Angular 19 source
    ├── angular.json
    ├── package.json
    └── src/app/
        ├── pages/
        │   ├── overview/
        │   ├── agents/
        │   ├── memory-explorer/
        │   ├── shared-memory/
        │   ├── knowledge-graph/
        │   ├── performance/
        │   ├── analytics/
        │   ├── audit-trail/
        │   ├── recovery/
        │   ├── anomalies/
        │   └── settings/
        ├── components/
        └── services/
```

## Data Model

Single SQLite database at `~/.agent-memory/memory.db`. PRAGMA: `journal_mode=WAL`, `foreign_keys=ON`.

### memories

| Column | Type | Description |
|--------|------|-------------|
| id | TEXT PK | UUID |
| key | TEXT NOT NULL | Namespaced key (e.g. `customer:acme:status`) |
| value | TEXT NOT NULL | Memory content |
| agent | TEXT NOT NULL | Who wrote it (`claude-code`, `gemini`) |
| project | TEXT | Project path (nullable for global) |
| shared | INTEGER DEFAULT 0 | 1 = visible to all agents |
| importance | REAL DEFAULT 0.5 | 0.0-1.0 ranking weight |
| tags_text | TEXT DEFAULT '' | Denormalized concatenation of tags (kept in sync for FTS5) |
| deleted_at | TEXT | ISO 8601, nullable. Non-null = soft-deleted. |
| created_at | TEXT NOT NULL | ISO 8601 |
| updated_at | TEXT NOT NULL | ISO 8601 |

Unique constraint: `(key, agent, project)`. Same key can exist for the same agent in different projects.

### memory_tags

| Column | Type | Description |
|--------|------|-------------|
| memory_id | TEXT FK | References memories(id) CASCADE |
| tag | TEXT NOT NULL | Tag value |

Primary key: `(memory_id, tag)`.

### memory_versions

| Column | Type | Description |
|--------|------|-------------|
| id | TEXT PK | UUID |
| memory_id | TEXT FK | References memories(id) CASCADE |
| value | TEXT NOT NULL | Snapshot of value at this version |
| version | INTEGER NOT NULL | Incrementing version number |
| created_at | TEXT NOT NULL | ISO 8601 |

### memory_links

| Column | Type | Description |
|--------|------|-------------|
| id | TEXT PK | UUID |
| source_id | TEXT FK | References memories(id) CASCADE |
| target_id | TEXT FK | References memories(id) CASCADE |
| relation | TEXT NOT NULL | `related_to`, `contradicts`, `depends_on`, `caused_by` |
| created_at | TEXT NOT NULL | ISO 8601 |

Unique constraint: `(source_id, target_id, relation)`.

### goals

| Column | Type | Description |
|--------|------|-------------|
| id | TEXT PK | UUID |
| agent | TEXT NOT NULL | Agent name |
| project | TEXT | Project path |
| description | TEXT NOT NULL | Goal description |
| status | TEXT DEFAULT 'active' | `active`, `completed`, `abandoned` |
| created_at | TEXT NOT NULL | ISO 8601 |
| updated_at | TEXT NOT NULL | ISO 8601 |

### audit_log

| Column | Type | Description |
|--------|------|-------------|
| id | TEXT PK | UUID |
| agent | TEXT NOT NULL | Agent name |
| operation | TEXT NOT NULL | `store`, `recall`, `delete`, `search`, `link`, `share` |
| memory_key | TEXT | Affected key |
| details | TEXT | JSON blob with operation specifics |
| latency_ms | REAL | Operation duration |
| created_at | TEXT NOT NULL | ISO 8601 |

### memory_fts (FTS5 virtual table)

Content table linked to `memories`. Indexed columns: `key`, `value`, `tags_text`. The `tags_text` column on `memories` is a denormalized space-separated concatenation of tags, kept in sync on every insert/update to `memory_tags`.

## MCP Tools

12 tools, all prefixed `mem_`. Every call is logged to `audit_log` with latency.

### Core Memory

- **`mem_store`** (key, value, tags[], agent, project?, importance?, shared?) — Upsert memory. If key exists for this agent, creates a new version and updates the value. Triggers contradiction check against related memories.
- **`mem_recall`** (key, agent?) — Retrieve memory by key. If agent is omitted, returns all agents' versions of that key.
- **`mem_search`** (query, agent?, project?, tags[]?, limit?) — FTS5 keyword search with optional filters. Returns ranked results.
- **`mem_delete`** (key, agent) — Soft-deletes by setting `deleted_at` timestamp. Excluded from `mem_list`, `mem_search`, `mem_recall` by default. Audit trail preserved.
- **`mem_list`** (agent?, project?, tags[]?, shared?, limit?, offset?) — List memories with filters. Paginated.

### Versioning

- **`mem_history`** (key, agent) — Returns all versions of a memory with timestamps.

### Shared Memory

- **`mem_share`** (key, agent) — Marks a memory as shared (visible to all agents).
- **`mem_shared`** (project?, tags[]?, limit?) — List all shared memories, optionally filtered.

### Intelligence

- **`mem_goals`** (agent, goals[], project?) — Register current goals/tasks. Replaces previous active goals for that agent.
- **`mem_check`** (agent, project?) — Runs brain analysis. Returns detected loops, drift, and contradictions.

### Knowledge Graph

- **`mem_link`** (source_key, target_key, relation, agent) — Create a relationship between two memories.
- **`mem_graph`** (key, depth?, agent?) — Traverse the knowledge graph from a memory. Returns connected memories up to specified depth (default 2).

## Brain System

The brain runs on-demand via `mem_check` and reactively on `mem_store`.

### Loop Detection

Scans `audit_log` for repeated patterns within a configurable time window (default: 30 minutes). Detects:
- Same key stored more than N times (default: 3) in the window
- Recall-store-recall cycles on the same key
- Identical search queries repeated

### Goal Drift Detection

Compares recent memory operations (keys, tags, content keywords) against registered goals. Flags when:
- More than 70% of recent operations have no keyword overlap with active goals
- New memories are being created in domains unrelated to stated goals

### Contradiction Detection

On `mem_store`, compares new value against existing memories with overlapping keys/tags. Uses keyword heuristics:
- Opposing sentiment indicators (e.g. "at-risk" vs "healthy", "failing" vs "passing")
- Numeric value changes beyond a threshold
- When detected, automatically creates a `contradicts` link between memories

## REST API

Javalin on port 7070 (configurable via `--port`). All endpoints under `/api/`.

### Agents
- `GET /api/agents` — List known agents with live metrics (computed from audit_log)
- `GET /api/agents/:name/metrics` — Detailed metrics: latency, read/write counts, uptime, errors

### Memories
- `GET /api/memories` — List/filter (params: agent, project, tags, shared, q, limit, offset)
- `GET /api/memories/:id` — Single memory with tags
- `GET /api/memories/:id/versions` — Version history
- `POST /api/memories` — Create/update from dashboard
- `DELETE /api/memories/:id` — Delete from dashboard

### Knowledge Graph
- `GET /api/graph` — Full graph (nodes + edges), filterable by agent/project
- `GET /api/graph/:memoryId` — Subgraph from a starting node with depth param

### Goals
- `GET /api/goals` — List goals, filterable by agent/status

### Anomalies
- `GET /api/anomalies` — Detected loops, drift, contradictions

### Audit
- `GET /api/audit` — Paginated audit log, filterable by agent/operation/time range

### Performance
- `GET /api/performance/timeseries` — Latency/throughput over time (params: metric, interval, from, to)
- `GET /api/performance/summary` — Aggregate stats

### Settings
- `GET /api/settings` — Current configuration
- `PUT /api/settings` — Update detection thresholds, etc. Persisted to a `settings` table in SQLite. Port changes require restart.

## Angular Dashboard

Angular 19, standalone components, dark theme with Tailwind CSS, orange accent color.

### Layout
- Fixed sidebar: logo, navigation groups (Monitoring, Operations, Management), agent selector
- Main content area with breadcrumbs

### Pages

| Page | Purpose |
|------|---------|
| Overview | Summary cards (total memories, active agents, anomaly count), recent activity feed |
| Agents | Agent table with live metrics (latency, reads/writes, uptime, errors). Click to drill down. |
| Memory Explorer | Searchable/filterable memory list with tags (left). Version history panel (right). Agent dropdown + search bar + tag filter (top). |
| Shared Memory | Memory explorer filtered to shared memories, grouped by originating agent |
| Knowledge Graph | Interactive graph visualization (d3-force or cytoscape.js). Nodes = memories, edges = relations. Click to inspect. |
| Performance | Time-series charts (ngx-charts) for write/read latency, throughput. Summary cards. |
| Analytics | Memory growth over time, most active agents, most accessed memories, tag distribution |
| Audit Trail | Paginated operation log with filters (agent, operation type, time range). Sortable. |
| Recovery | Crashed/disconnected agents, last known state, export/import memory snapshots |
| Anomalies | Flagged loops, drift, contradictions with severity, description, affected memories |
| Settings | Detection thresholds, dashboard port, database path, agent management |

## Startup & Integration

```bash
# Start everything
java -jar agent-memory-0.1.0.jar serve

# Custom port
java -jar agent-memory-0.1.0.jar serve --port 8080
```

### MCP Client Configuration

```json
{
  "mcpServers": {
    "agent-memory": {
      "command": "java",
      "args": ["-jar", "/path/to/agent-memory-0.1.0.jar", "serve"]
    }
  }
}
```

### Agent Identification

Agents self-identify via the `agent` parameter in tool calls. No authentication — this is a local tool. Any agent can read any other agent's memories. Shared memories are explicitly flagged.

### Crash Recovery

Uses a staleness heuristic: an agent whose last `audit_log` operation was within the last 10 minutes but hasn't called any tool in the last 5 minutes is flagged as "potentially crashed" in the Recovery page. This is a best-effort heuristic — the server has no persistent connection to agents (they communicate via MCP stdio tool calls). All memory data is always preserved in SQLite regardless.

## Build

Gradle with Shadow plugin. Angular built as a pre-step:

1. `buildAngular` task runs `npm install && ng build` in `angular/`
2. Output copied to `src/main/resources/static/`
3. `shadowJar` bundles everything into `agent-memory-0.1.0.jar`
4. `jars/build-all.sh` updated to include agent-memory

### Dependencies

- `io.modelcontextprotocol.sdk:mcp:1.1.0`
- `org.xerial:sqlite-jdbc:3.47.2.0`
- `com.fasterxml.jackson.core:jackson-databind:2.18.2`
- `info.picocli:picocli:4.7.6`
- `io.javalin:javalin:6.x` (embedded HTTP server)
- Angular 19, Tailwind CSS, ngx-charts, cytoscape.js (frontend)

### Build Requirements

- Java 21 (runtime + build)
- Node.js 20+ (build-time only, for Angular compilation)
- `build-all.sh` checks for Node.js availability before building agent-memory

## Testing

- **Store/DAO layer:** JUnit 5 + AssertJ tests against an in-memory SQLite database
- **MCP tools:** Integration tests that start the MCP server and invoke tools programmatically
- **Brain detectors:** Unit tests with prepared audit_log data to verify loop, drift, and contradiction detection
- **REST API:** Javalin test utilities with HTTP client assertions
- **Angular:** Default Angular CLI test setup (Karma/Jasmine)
