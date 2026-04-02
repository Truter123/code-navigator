# Agent Memory WebSocket Real-Time Events

## Summary

Add WebSocket support to the agent-memory dashboard so all pages receive live event streams instead of relying on static REST loads. Single WS channel with server-side topic filtering, extensible envelope protocol designed for future bidirectional commands, and configurable throttle for high-throughput scenarios.

Also fixes: agent name field mismatch bug (`"agent"` -> `"name"` in `DashboardApi.computeAgentMetrics`).

## Decisions

- **Transport**: Javalin-native WebSocket (no new dependencies)
- **Channel model**: Single endpoint `/ws/events`, server-side filtering via subscription messages
- **Direction**: Read-only now, envelope reserves `command`/`response` types for future bidirectional
- **Throttle**: Configurable via settings table, default off (0ms)
- **Initial load**: REST stays for full state; WS is purely incremental updates after load

## Backend

### EventBus (`com.agentmemory.ws.EventBus`)

- `CopyOnWriteArrayList<Subscriber>` where `Subscriber` = `WsContext` + `Set<String> topics`
- `subscribe(ctx, topics)` — register or update a subscriber's topic set
- `unsubscribe(ctx, topics)` — remove topics from a subscriber
- `remove(ctx)` — clean up on disconnect
- `publish(topic, data)` — iterate subscribers, skip if topic not in set, serialize with Jackson, send
- Thread-safe: MCP tool handlers call `publish` from their threads

### Throttle

- Reads `ws.throttle.ms` from settings table on startup (default `0` = disabled)
- When > 0: events buffer into `ConcurrentLinkedQueue` per subscriber, `ScheduledExecutorService` flushes at configured interval as a `batch` message
- When 0: immediate send

### WebSocketHandler (`com.agentmemory.ws.WebSocketHandler`)

Registered in `ServeCommand`:

```java
app.ws("/ws/events", ws -> {
    ws.onConnect(ctx -> bus.subscribe(ctx, defaultTopics()));
    ws.onMessage(ctx -> {
        WsCommand cmd = mapper.readValue(ctx.message(), WsCommand.class);
        if ("subscribe".equals(cmd.type())) bus.subscribe(ctx, Set.copyOf(cmd.topics()));
        if ("unsubscribe".equals(cmd.type())) bus.unsubscribe(ctx, Set.copyOf(cmd.topics()));
    });
    ws.onClose(ctx -> bus.remove(ctx));
    ws.onError(ctx -> bus.remove(ctx));
});
```

Default subscription: all topics (configurable via `ws.default.topics` setting).

### Message Envelope

Server-to-client:
```json
{"type": "event", "topic": "audit", "ts": "2026-04-02T08:52:31Z", "data": {...}}
```

Client-to-server:
```json
{"type": "subscribe", "topics": ["audit", "anomaly"]}
{"type": "unsubscribe", "topics": ["memory"]}
```

Reserved for future:
```json
{"type": "command", "id": "cmd-1", "action": "delete_memory", "data": {...}}
{"type": "response", "id": "cmd-1", "status": "ok", "data": {...}}
```

Batch (when throttle > 0):
```json
{"type": "batch", "events": [{"type": "event", ...}, {"type": "event", ...}]}
```

Rules:
- Unknown `type` values silently ignored (forward compatibility)
- `id` field on commands/responses for future request-response correlation
- `ts` is always ISO-8601 UTC, set server-side
- `data` shapes match existing REST response shapes

### Topics

| Topic     | Fired when                           | Payload                              |
|-----------|--------------------------------------|--------------------------------------|
| `audit`   | Any MCP tool completes               | `AuditEntry`                         |
| `memory`  | Store, update, delete                | `{action, key, agent, project}`      |
| `anomaly` | Brain detects loop/drift/contradiction | `Anomaly` object                   |
| `agent`   | New agent seen, agent goes stale     | `{name, status, event}`             |
| `goal`    | Goals registered/abandoned           | `{agent, goals, action}`            |
| `graph`   | Link created                         | `{source, target, relation}`        |

### Integration Points

- `AgentMemoryMcpServer`: after each tool handler's audit log write, call `eventBus.publish("audit", entry)` + topic-specific publish
- `BrainEngine.check()` / `onStore()`: publish `"anomaly"` when anomalies detected
- `MemoryStore`: publish `"agent"` on first-seen agent

### Settings

Two new keys in existing `settings` table:

| Key                 | Default | Description                                      |
|---------------------|---------|--------------------------------------------------|
| `ws.throttle.ms`    | `0`     | Batch flush interval. 0 = immediate              |
| `ws.default.topics` | `*`     | Default subscriptions for new connections. * = all |

## Frontend

### WebSocketService (`ws.service.ts`)

- Opens single `WebSocket` to `ws://<host>:7070/ws/events` on app init
- `events$` Observable (Subject) emitting parsed `WsMessage` objects
- `subscribe(topics)` / `unsubscribe(topics)` sends `WsCommand` over socket
- Auto-reconnect with exponential backoff (1s, 2s, 4s, max 30s)
- `connected$` Observable for connection status
- Unwraps `batch` messages transparently — downstream always sees individual events

### Per-Page Subscriptions

| Page             | Topics                     | Behavior                                          |
|------------------|----------------------------|---------------------------------------------------|
| Overview         | `audit`, `memory`, `anomaly` | Increment counters, prepend recent activity      |
| Agents           | `agent`, `audit`           | Update status badges, live latency/counts          |
| Memory Explorer  | `memory`                   | Flash new/updated rows, remove deleted             |
| Shared Memory    | `memory`                   | Same, filtered to shared                           |
| Audit Trail      | `audit`                    | Prepend new entries                                |
| Knowledge Graph  | `graph`                    | Add nodes/edges to Cytoscape live                  |
| Anomalies        | `anomaly`                  | Add new anomaly cards                              |
| Recovery         | `agent`                    | Update agent status cards                          |
| Analytics        | `audit`                    | Update bar charts incrementally                    |
| Performance      | `audit`                    | Prepend to operations, update summary              |
| Settings         | —                          | No subscription                                    |

### Data Flow

1. Page loads -> REST call for full state
2. WebSocketService connects (or is already connected)
3. Component subscribes to relevant topics via `events$.pipe(filter(...))`
4. Incoming events update component state incrementally
5. On component destroy, unsubscribe from topics

### Connection Status Indicator

Small dot in sidebar/header:
- Green = connected
- Red/pulsing = disconnected, reconnecting

## Bug Fix

`DashboardApi.java` line 157: change `metrics.put("agent", agent)` to `metrics.put("name", agent)` — fixes empty agent name on Agents page and cascading zero-metrics issue.

## Files to Create

- `agent-memory/src/main/java/com/agentmemory/ws/EventBus.java`
- `agent-memory/src/main/java/com/agentmemory/ws/WebSocketHandler.java`
- `agent-memory/src/main/java/com/agentmemory/ws/WsMessage.java` (record)
- `agent-memory/src/main/java/com/agentmemory/ws/WsCommand.java` (record)
- `agent-memory/angular/src/app/services/ws.service.ts`

## Files to Modify

- `agent-memory/src/main/java/com/agentmemory/cli/ServeCommand.java` — register WS handler, pass EventBus
- `agent-memory/src/main/java/com/agentmemory/server/AgentMemoryMcpServer.java` — inject EventBus, publish events after tool calls
- `agent-memory/src/main/java/com/agentmemory/brain/BrainEngine.java` — inject EventBus, publish anomalies
- `agent-memory/src/main/java/com/agentmemory/api/DashboardApi.java` — bug fix (agent -> name)
- `agent-memory/angular/src/app/app.component.ts` or layout — connection indicator
- All 10 page components that need live updates
