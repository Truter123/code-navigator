# Agent Memory WebSocket Real-Time Events — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add WebSocket real-time event streaming to the agent-memory dashboard so all pages update live.

**Note:** The agent name bug (`"agent"` -> `"name"` in `DashboardApi.computeAgentMetrics`) has already been fixed. Task 1 adds a regression test.

**Architecture:** Single Javalin-native WS endpoint (`/ws/events`) backed by an in-process `EventBus`. MCP tool handlers and brain engine publish events after operations. Angular `WebSocketService` receives events and updates page components incrementally after initial REST load.

**Tech Stack:** Java 21, Javalin 7.0.1 (built-in WS), Jackson 2.18.2, Angular 19, RxJS, Cytoscape.js

---

### Task 1: Regression Test — Agent Name Field

**Files:**
- Test: `agent-memory/src/test/java/com/agentmemory/api/DashboardApiTest.java`

The bug fix (`"agent"` -> `"name"`) is already applied. This task adds a regression test.

- [ ] **Step 1: Write regression test**

Add to `DashboardApiTest.java`:

```java
@Test
void listAgents_returnsNameField() {
    store.logAudit("test-agent", "store", "k1", null, 5.0);

    JavalinTest.test(app, (server, client) -> {
        var response = client.get("/api/agents");
        assertThat(response.code()).isEqualTo(200);
        var body = response.body().string();
        assertThat(body).contains("\"name\"");
        assertThat(body).contains("test-agent");
    });
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `cd agent-memory && ./gradlew test --tests "com.agentmemory.api.DashboardApiTest.listAgents_returnsNameField" -q`
Expected: PASS (fix already applied)

- [ ] **Step 3: Commit**

```bash
git add agent-memory/src/test/java/com/agentmemory/api/DashboardApiTest.java
git commit -m "test(agent-memory): add regression test for agent name field in agents API"
```

---

### Task 2: WS Message Records

**Files:**
- Create: `agent-memory/src/main/java/com/agentmemory/ws/WsMessage.java`
- Create: `agent-memory/src/main/java/com/agentmemory/ws/WsCommand.java`

- [ ] **Step 1: Create WsMessage record**

```java
package com.agentmemory.ws;

import java.time.Instant;

public record WsMessage(String type, String topic, Object data, String ts) {

    public static WsMessage event(String topic, Object data) {
        return new WsMessage("event", topic, data, Instant.now().toString());
    }
}
```

- [ ] **Step 2: Create WsCommand record**

```java
package com.agentmemory.ws;

import java.util.List;

public record WsCommand(String type, List<String> topics) {}
```

- [ ] **Step 3: Compile check**

Run: `cd agent-memory && ./gradlew compileJava -q 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add agent-memory/src/main/java/com/agentmemory/ws/
git commit -m "feat(agent-memory): add WsMessage and WsCommand records"
```

---

### Task 3: EventBus

**Files:**
- Create: `agent-memory/src/main/java/com/agentmemory/ws/EventBus.java`
- Create: `agent-memory/src/test/java/com/agentmemory/ws/EventBusTest.java`

- [ ] **Step 1: Write EventBus tests**

```java
package com.agentmemory.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class EventBusTest {

    EventBus bus;
    CopyOnWriteArrayList<String> sent;
    EventBus.Subscriber fakeSub;

    @BeforeEach
    void setUp() {
        bus = new EventBus(new ObjectMapper());
        sent = new CopyOnWriteArrayList<>();
        fakeSub = new EventBus.Subscriber("test-1", sent::add, Set.of("audit", "memory"));
    }

    @Test
    void publish_sendsToMatchingTopic() {
        bus.addSubscriber(fakeSub);
        bus.publish("audit", Map.of("op", "store"));

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).contains("\"topic\":\"audit\"");
    }

    @Test
    void publish_skipsNonMatchingTopic() {
        bus.addSubscriber(fakeSub);
        bus.publish("anomaly", Map.of("type", "loop"));

        assertThat(sent).isEmpty();
    }

    @Test
    void subscribe_updateTopics() {
        bus.addSubscriber(fakeSub);
        bus.updateTopics("test-1", Set.of("anomaly"));
        bus.publish("anomaly", Map.of("type", "drift"));

        assertThat(sent).hasSize(1);
    }

    @Test
    void unsubscribe_removesTopics() {
        bus.addSubscriber(fakeSub);
        bus.removeTopics("test-1", Set.of("audit"));
        bus.publish("audit", Map.of("op", "store"));

        assertThat(sent).isEmpty();
    }

    @Test
    void remove_cleansUpSubscriber() {
        bus.addSubscriber(fakeSub);
        bus.removeSubscriber("test-1");
        bus.publish("audit", Map.of("op", "store"));

        assertThat(sent).isEmpty();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd agent-memory && ./gradlew test --tests "com.agentmemory.ws.EventBusTest" -q 2>&1 | tail -5`
Expected: FAIL — class not found

- [ ] **Step 3: Implement EventBus**

```java
package com.agentmemory.ws;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class EventBus {

    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final ObjectMapper mapper;
    private final long throttleMs;
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<String>> buffers = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    public EventBus(ObjectMapper mapper) {
        this(mapper, 0);
    }

    public EventBus(ObjectMapper mapper, long throttleMs) {
        this.mapper = mapper;
        this.throttleMs = throttleMs;
        if (throttleMs > 0) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ws-throttle");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(this::flush, throttleMs, throttleMs, TimeUnit.MILLISECONDS);
        }
    }

    public void addSubscriber(Subscriber subscriber) {
        subscribers.add(subscriber);
    }

    public void removeSubscriber(String id) {
        subscribers.removeIf(s -> s.id.equals(id));
    }

    public void updateTopics(String id, Set<String> topics) {
        for (Subscriber s : subscribers) {
            if (s.id.equals(id)) {
                s.topics = new HashSet<>(topics);
                return;
            }
        }
    }

    public void removeTopics(String id, Set<String> topics) {
        for (Subscriber s : subscribers) {
            if (s.id.equals(id)) {
                s.topics.removeAll(topics);
                return;
            }
        }
    }

    public void publish(String topic, Object data) {
        WsMessage msg = WsMessage.event(topic, data);
        String json;
        try {
            json = mapper.writeValueAsString(msg);
        } catch (Exception e) {
            return;
        }
        for (Subscriber s : subscribers) {
            if (s.topics.contains(topic) || s.topics.contains("*")) {
                if (throttleMs > 0) {
                    buffers.computeIfAbsent(s.id, k -> new ConcurrentLinkedQueue<>()).add(json);
                } else {
                    try {
                        s.sender.accept(json);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private void flush() {
        for (Subscriber s : subscribers) {
            ConcurrentLinkedQueue<String> queue = buffers.get(s.id);
            if (queue == null || queue.isEmpty()) continue;
            List<String> events = new ArrayList<>();
            String item;
            while ((item = queue.poll()) != null) events.add(item);
            if (events.isEmpty()) continue;
            try {
                String batch = "{\"type\":\"batch\",\"events\":[" + String.join(",", events) + "]}";
                s.sender.accept(batch);
            } catch (Exception ignored) {
            }
        }
    }

    public static class Subscriber {
        final String id;
        final Consumer<String> sender;
        volatile Set<String> topics;

        public Subscriber(String id, Consumer<String> sender, Set<String> topics) {
            this.id = id;
            this.sender = sender;
            this.topics = new HashSet<>(topics);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd agent-memory && ./gradlew test --tests "com.agentmemory.ws.EventBusTest" -q`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add agent-memory/src/main/java/com/agentmemory/ws/EventBus.java agent-memory/src/test/java/com/agentmemory/ws/EventBusTest.java
git commit -m "feat(agent-memory): add EventBus with topic-filtered pub/sub"
```

---

### Task 4: WebSocketHandler

**Files:**
- Create: `agent-memory/src/main/java/com/agentmemory/ws/WebSocketHandler.java`

- [ ] **Step 1: Implement WebSocketHandler**

```java
package com.agentmemory.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;

import java.util.HashSet;
import java.util.Set;

public class WebSocketHandler {

    private final EventBus eventBus;
    private final ObjectMapper mapper;
    private final Set<String> defaultTopics;

    public WebSocketHandler(EventBus eventBus, ObjectMapper mapper, Set<String> defaultTopics) {
        this.eventBus = eventBus;
        this.mapper = mapper;
        this.defaultTopics = defaultTopics;
    }

    public void register(Javalin app) {
        app.ws("/ws/events", ws -> {
            ws.onConnect(ctx -> {
                String id = ctx.sessionId();
                var subscriber = new EventBus.Subscriber(
                        id,
                        ctx::send,
                        new HashSet<>(defaultTopics)
                );
                eventBus.addSubscriber(subscriber);
            });

            ws.onMessage(ctx -> {
                try {
                    WsCommand cmd = mapper.readValue(ctx.message(), WsCommand.class);
                    String id = ctx.sessionId();
                    if ("subscribe".equals(cmd.type()) && cmd.topics() != null) {
                        eventBus.updateTopics(id, Set.copyOf(cmd.topics()));
                    } else if ("unsubscribe".equals(cmd.type()) && cmd.topics() != null) {
                        eventBus.removeTopics(id, Set.copyOf(cmd.topics()));
                    }
                } catch (Exception ignored) {
                    // unknown message type — silently ignore per spec
                }
            });

            ws.onClose(ctx -> eventBus.removeSubscriber(ctx.sessionId()));
            ws.onError(ctx -> eventBus.removeSubscriber(ctx.sessionId()));
        });
    }
}
```

- [ ] **Step 2: Compile check**

Run: `cd agent-memory && ./gradlew compileJava -q 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add agent-memory/src/main/java/com/agentmemory/ws/WebSocketHandler.java
git commit -m "feat(agent-memory): add WebSocketHandler with subscribe/unsubscribe"
```

---

### Task 5: Wire EventBus into ServeCommand and MCP Server

**Files:**
- Modify: `agent-memory/src/main/java/com/agentmemory/cli/ServeCommand.java`
- Modify: `agent-memory/src/main/java/com/agentmemory/mcp/AgentMemoryMcpServer.java`

Note: BrainEngine is not modified; anomaly events are published from MCP handlers instead.

- [ ] **Step 1: Update ServeCommand to create EventBus and register WS handler**

In `ServeCommand.java`, add imports and wire up:

```java
// Add imports
import com.agentmemory.ws.EventBus;
import com.agentmemory.ws.WebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
```

After line 48 (`BrainEngine brain = new BrainEngine(memoryStore, graphStore);`), add:

```java
ObjectMapper objectMapper = new ObjectMapper();
String throttleSetting = memoryStore.getSetting("ws.throttle.ms");
long throttleMs = throttleSetting != null ? Long.parseLong(throttleSetting) : 0;
EventBus eventBus = new EventBus(objectMapper, throttleMs);
```

Before `app.start(dashboardPort);`, add:

```java
WebSocketHandler wsHandler = new WebSocketHandler(
    eventBus, objectMapper,
    Set.of("audit", "memory", "anomaly", "agent", "goal", "graph")
);
wsHandler.register(app);
```

Update MCP server constructor call on line 81 to pass `eventBus`:

```java
AgentMemoryMcpServer mcpServer = new AgentMemoryMcpServer(memoryStore, graphStore, brain, eventBus);
```

- [ ] **Step 2: Update AgentMemoryMcpServer to accept and use EventBus**

Add field and update constructor:

```java
private final EventBus eventBus;

public AgentMemoryMcpServer(MemoryStore memoryStore, GraphStore graphStore, BrainEngine brain, EventBus eventBus) {
    this.memoryStore = memoryStore;
    this.graphStore = graphStore;
    this.brain = brain;
    this.eventBus = eventBus;
}
```

Update the `timed` method to publish audit events:

```java
private CallToolResult timed(String operation, Map<String, Object> args,
                              Function<Map<String, Object>, String> handler) {
    long start = System.nanoTime();
    String result = handler.apply(args);
    double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
    String agent = (String) args.getOrDefault("agent", "unknown");
    String key = (String) args.get("key");
    memoryStore.logAudit(agent, operation, key, null, latencyMs);

    // Publish audit event
    if (eventBus != null) {
        eventBus.publish("audit", Map.of(
            "agent", agent,
            "operation", operation,
            "key", key != null ? key : "",
            "latencyMs", latencyMs
        ));
    }

    return textResult(result);
}
```

Add topic-specific publish calls in handlers:

In `handleStore` — after `memoryStore.upsert(...)` call, add:

```java
if (eventBus != null) {
    eventBus.publish("memory", Map.of("action", "store", "key", key, "agent", agent, "project", project != null ? project : ""));
    // Publish agent event (covers new agent discovery for Recovery page)
    eventBus.publish("agent", Map.of("name", agent, "status", "active", "event", "operation"));
}
```

In `handleStore` — after `brain.onStore(...)` returns anomalies, if non-empty:

```java
if (eventBus != null && !anomalies.isEmpty()) {
    for (Anomaly a : anomalies) {
        eventBus.publish("anomaly", a);
    }
}
```

In `handleDelete` — after `memoryStore.softDeleteByKeyAndAgent(...)`:

```java
if (eventBus != null) {
    eventBus.publish("memory", Map.of("action", "delete", "key", key, "agent", agent));
}
```

In `handleShare` — after `memoryStore.share(...)`:

```java
if (eventBus != null) {
    eventBus.publish("memory", Map.of("action", "share", "key", key, "agent", agent));
}
```

In `handleGoals` — after `memoryStore.setGoals(...)`:

```java
if (eventBus != null) {
    eventBus.publish("goal", Map.of("agent", agent, "goals", goals, "action", "registered"));
}
```

In `handleCheck` — after `brain.check(...)` returns anomalies, if non-empty:

```java
if (eventBus != null && !anomalies.isEmpty()) {
    for (Anomaly a : anomalies) {
        eventBus.publish("anomaly", a);
    }
}
```

In `handleLink` — after `graphStore.link(...)`:

```java
if (eventBus != null) {
    eventBus.publish("graph", Map.of("source", sourceKey, "target", targetKey, "relation", relation));
}
```

- [ ] **Step 3: Compile check**

Run: `cd agent-memory && ./gradlew compileJava -q 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Run all existing tests**

Run: `cd agent-memory && ./gradlew test -q`
Expected: All tests pass

- [ ] **Step 5: Commit**

```bash
git add agent-memory/src/main/java/com/agentmemory/cli/ServeCommand.java agent-memory/src/main/java/com/agentmemory/mcp/AgentMemoryMcpServer.java
git commit -m "feat(agent-memory): wire EventBus into ServeCommand and MCP tool handlers"
```

---

### Task 6: Angular WebSocketService

**Files:**
- Create: `agent-memory/angular/src/app/services/ws.service.ts`

- [ ] **Step 1: Create WebSocketService**

```typescript
import { Injectable, OnDestroy } from '@angular/core';
import { BehaviorSubject, Observable, Subject, filter } from 'rxjs';

export interface WsMessage {
  type: string;
  topic: string;
  data: any;
  ts: string;
}

@Injectable({ providedIn: 'root' })
export class WebSocketService implements OnDestroy {
  private ws: WebSocket | null = null;
  private events = new Subject<WsMessage>();
  private connectedSubject = new BehaviorSubject<boolean>(false);
  private reconnectDelay = 1000;
  private maxReconnectDelay = 30000;
  private destroyed = false;

  events$ = this.events.asObservable();
  connected$ = this.connectedSubject.asObservable();

  constructor() {
    this.connect();
  }

  private connect() {
    if (this.destroyed) return;

    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const url = `${protocol}//${location.host}/ws/events`;

    this.ws = new WebSocket(url);

    this.ws.onopen = () => {
      this.connectedSubject.next(true);
      this.reconnectDelay = 1000;
    };

    this.ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        if (msg.type === 'batch' && Array.isArray(msg.events)) {
          msg.events.forEach((e: WsMessage) => this.events.next(e));
        } else if (msg.type === 'event') {
          this.events.next(msg);
        }
      } catch {}
    };

    this.ws.onclose = () => {
      this.connectedSubject.next(false);
      this.scheduleReconnect();
    };

    this.ws.onerror = () => {
      this.ws?.close();
    };
  }

  private scheduleReconnect() {
    if (this.destroyed) return;
    setTimeout(() => this.connect(), this.reconnectDelay);
    this.reconnectDelay = Math.min(this.reconnectDelay * 2, this.maxReconnectDelay);
  }

  subscribe(topics: string[]) {
    this.send({ type: 'subscribe', topics });
  }

  unsubscribe(topics: string[]) {
    this.send({ type: 'unsubscribe', topics });
  }

  on(topic: string): Observable<WsMessage> {
    return this.events$.pipe(filter(e => e.topic === topic));
  }

  private send(msg: any) {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(msg));
    }
  }

  ngOnDestroy() {
    this.destroyed = true;
    this.ws?.close();
  }
}
```

- [ ] **Step 2: Compile check**

Run: `cd agent-memory/angular && npx ng build --configuration production 2>&1 | tail -10`
Expected: Build succeeds (service is tree-shaken if unused, but providedIn root should still compile)

- [ ] **Step 3: Commit**

```bash
git add agent-memory/angular/src/app/services/ws.service.ts
git commit -m "feat(agent-memory): add WebSocketService with auto-reconnect"
```

---

### Task 7: Connection Status Indicator in Sidebar

**Files:**
- Modify: `agent-memory/angular/src/app/components/sidebar/sidebar.component.ts`

- [ ] **Step 1: Add connection indicator to sidebar**

Add import for `WebSocketService` and `AsyncPipe`:

```typescript
import { AsyncPipe } from '@angular/common';
import { WebSocketService } from '../../services/ws.service';
```

Update imports array: `imports: [RouterLink, RouterLinkActive, AsyncPipe]`

Add after the logo div in the template:

```html
<div class="ws-status">
  <span class="ws-dot" [class.connected]="ws.connected$ | async"></span>
  <span class="ws-label">{{ (ws.connected$ | async) ? 'Live' : 'Reconnecting...' }}</span>
</div>
```

Add styles:

```css
.ws-status {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 20px 16px;
  font-size: 11px;
  color: var(--text-secondary);
}
.ws-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #ef4444;
  transition: background 0.3s;
}
.ws-dot.connected {
  background: #22c55e;
}
```

Add constructor: `constructor(public ws: WebSocketService) {}`

- [ ] **Step 2: Compile check**

Run: `cd agent-memory/angular && npx ng build --configuration production 2>&1 | tail -10`
Expected: Build succeeds

- [ ] **Step 3: Commit**

```bash
git add agent-memory/angular/src/app/components/sidebar/sidebar.component.ts
git commit -m "feat(agent-memory): add WebSocket connection indicator to sidebar"
```

---

### Task 8: Wire WebSocket Events into Overview Page

**Files:**
- Modify: `agent-memory/angular/src/app/pages/overview/overview.component.ts`

- [ ] **Step 1: Add WS subscription to OverviewComponent**

Add import:

```typescript
import { WebSocketService } from '../../services/ws.service';
import { Subscription } from 'rxjs';
```

Add `implements OnDestroy` to the class declaration. Add `OnDestroy` to Angular import.

Add fields and update constructor:

```typescript
private subs: Subscription[] = [];

constructor(private api: ApiService, private ws: WebSocketService) {}
```

Add to `ngOnInit()` after existing REST calls:

```typescript
this.subs.push(
  this.ws.on('memory').subscribe(e => {
    if (e.data.action === 'store') this.totalMemories++;
    if (e.data.action === 'delete') this.totalMemories = Math.max(0, this.totalMemories - 1);
  }),
  this.ws.on('anomaly').subscribe(() => this.anomalyCount++),
  this.ws.on('audit').subscribe(e => {
    this.recentActivity.unshift(e.data);
    if (this.recentActivity.length > 10) this.recentActivity.pop();
  })
);
```

Add `ngOnDestroy`:

```typescript
ngOnDestroy() { this.subs.forEach(s => s.unsubscribe()); }
```

- [ ] **Step 2: Compile check**

Run: `cd agent-memory/angular && npx ng build --configuration production 2>&1 | tail -10`
Expected: Build succeeds

- [ ] **Step 3: Commit**

```bash
git add agent-memory/angular/src/app/pages/overview/overview.component.ts
git commit -m "feat(agent-memory): wire WS events into overview page"
```

---

### Task 9: Wire WebSocket Events into Agents Page

**Files:**
- Modify: `agent-memory/angular/src/app/pages/agents/agents.component.ts`

- [ ] **Step 1: Add WS subscription**

Add imports for `WebSocketService`, `Subscription`, `OnDestroy`.

Add `implements OnDestroy`. Add fields:

```typescript
private subs: Subscription[] = [];
constructor(private api: ApiService, private ws: WebSocketService) {}
```

Add to `ngOnInit()` after existing REST calls:

```typescript
this.subs.push(
  this.ws.on('audit').subscribe(e => {
    const agent = this.agents.find(a => a.name === e.data.agent);
    if (agent) {
      if (['store','delete','link','share','goals'].includes(e.data.operation)) {
        agent.totalWrites = (agent.totalWrites || 0) + 1;
      } else {
        agent.totalReads = (agent.totalReads || 0) + 1;
      }
    }
  })
);
```

Add:

```typescript
ngOnDestroy() { this.subs.forEach(s => s.unsubscribe()); }
```

- [ ] **Step 2: Compile check**

Run: `cd agent-memory/angular && npx ng build --configuration production 2>&1 | tail -10`
Expected: Build succeeds

- [ ] **Step 3: Commit**

```bash
git add agent-memory/angular/src/app/pages/agents/agents.component.ts
git commit -m "feat(agent-memory): wire WS audit events into agents page"
```

---

### Task 10: Wire WebSocket Events into Audit Trail Page

**Files:**
- Modify: `agent-memory/angular/src/app/pages/audit-trail/audit-trail.component.ts`

- [ ] **Step 1: Add WS subscription**

Add imports for `WebSocketService`, `Subscription`, `OnDestroy`.

Add `implements OnDestroy`. Update constructor and add field:

```typescript
private subs: Subscription[] = [];
constructor(private api: ApiService, private ws: WebSocketService) {}
```

Add to `ngOnInit()`:

```typescript
this.subs.push(
  this.ws.on('audit').subscribe(e => {
    if (this.page === 0) {
      this.auditLog.unshift(e.data);
      if (this.auditLog.length > this.pageSize) this.auditLog.pop();
    }
  })
);
```

Add:

```typescript
ngOnDestroy() { this.subs.forEach(s => s.unsubscribe()); }
```

- [ ] **Step 2: Compile check**

Run: `cd agent-memory/angular && npx ng build --configuration production 2>&1 | tail -10`
Expected: Build succeeds

- [ ] **Step 3: Commit**

```bash
git add agent-memory/angular/src/app/pages/audit-trail/audit-trail.component.ts
git commit -m "feat(agent-memory): wire WS audit events into audit trail page"
```

---

### Task 11: Wire WebSocket Events into Memory Explorer and Shared Memory Pages

**Files:**
- Modify: `agent-memory/angular/src/app/pages/memory-explorer/memory-explorer.component.ts`
- Modify: `agent-memory/angular/src/app/pages/shared-memory/shared-memory.component.ts`

- [ ] **Step 1: Add WS subscription to MemoryExplorerComponent**

Add imports for `WebSocketService`, `Subscription`, `OnDestroy`.

Add `implements OnDestroy`. Update constructor and add field:

```typescript
private subs: Subscription[] = [];
constructor(private api: ApiService, private ws: WebSocketService) {}
```

Add to `ngOnInit()`:

```typescript
this.subs.push(
  this.ws.on('memory').subscribe(() => this.loadMemories())
);
```

Add:

```typescript
ngOnDestroy() { this.subs.forEach(s => s.unsubscribe()); }
```

- [ ] **Step 2: Add WS subscription to SharedMemoryComponent**

Same pattern — add imports, `implements OnDestroy`, field, constructor update.

Add to `ngOnInit()`:

```typescript
this.subs.push(
  this.ws.on('memory').subscribe(() => {
    this.api.getMemories({ shared: true }).subscribe(m => {
      this.memories = m;
      this.filterMemories();
    });
  })
);
```

Add:

```typescript
ngOnDestroy() { this.subs.forEach(s => s.unsubscribe()); }
```

- [ ] **Step 3: Compile check**

Run: `cd agent-memory/angular && npx ng build --configuration production 2>&1 | tail -10`
Expected: Build succeeds

- [ ] **Step 4: Commit**

```bash
git add agent-memory/angular/src/app/pages/memory-explorer/memory-explorer.component.ts agent-memory/angular/src/app/pages/shared-memory/shared-memory.component.ts
git commit -m "feat(agent-memory): wire WS memory events into explorer pages"
```

---

### Task 12: Wire WebSocket Events into Anomalies, Knowledge Graph, and Recovery Pages

**Files:**
- Modify: `agent-memory/angular/src/app/pages/anomalies/anomalies.component.ts`
- Modify: `agent-memory/angular/src/app/pages/knowledge-graph/knowledge-graph.component.ts`
- Modify: `agent-memory/angular/src/app/pages/recovery/recovery.component.ts`

- [ ] **Step 1: Add WS subscription to AnomaliesComponent**

Add imports, `implements OnDestroy`, field, constructor.

Add to `ngOnInit()`:

```typescript
this.subs.push(
  this.ws.on('anomaly').subscribe(e => {
    this.anomalies.unshift(e.data);
  })
);
```

Add `ngOnDestroy`.

- [ ] **Step 2: Add WS subscription to KnowledgeGraphComponent**

Add imports for `WebSocketService`, `Subscription`. Add field:

```typescript
private subs: Subscription[] = [];
constructor(private api: ApiService, private ws: WebSocketService) {}
```

Add to `ngAfterViewInit()` (after Cytoscape is initialized, inside the subscribe callback):

```typescript
this.subs.push(
  this.ws.on('graph').subscribe(e => {
    if (this.cy) {
      const src = String(e.data.source);
      const tgt = String(e.data.target);
      if (!this.cy.getElementById(src).length) {
        this.cy.add({ data: { id: src, label: src } });
      }
      if (!this.cy.getElementById(tgt).length) {
        this.cy.add({ data: { id: tgt, label: tgt } });
      }
      this.cy.add({ data: { source: src, target: tgt, label: e.data.relation || '' } });
      this.cy.layout({ name: 'cose', animate: true } as any).run();
    }
  })
);
```

Update `ngOnDestroy`:

```typescript
ngOnDestroy() {
  this.cy?.destroy();
  this.subs.forEach(s => s.unsubscribe());
}
```

- [ ] **Step 3: Add WS subscription to RecoveryComponent**

Add imports, `implements OnDestroy`, field, constructor.

Add to `ngOnInit()`:

```typescript
this.subs.push(
  this.ws.on('agent').subscribe(() => {
    this.api.getAgents().subscribe(agents => {
      this.agents = agents;
      this.agents.forEach(a => {
        this.api.getAgentMetrics(a.name).subscribe(m => {
          a.totalOps = (m.totalWrites || 0) + (m.totalReads || 0);
        });
      });
    });
  })
);
```

Add `ngOnDestroy`.

- [ ] **Step 4: Compile check**

Run: `cd agent-memory/angular && npx ng build --configuration production 2>&1 | tail -10`
Expected: Build succeeds

- [ ] **Step 5: Commit**

```bash
git add agent-memory/angular/src/app/pages/anomalies/anomalies.component.ts agent-memory/angular/src/app/pages/knowledge-graph/knowledge-graph.component.ts agent-memory/angular/src/app/pages/recovery/recovery.component.ts
git commit -m "feat(agent-memory): wire WS events into anomalies, graph, and recovery pages"
```

---

### Task 13: Wire WebSocket Events into Performance and Analytics Pages

**Files:**
- Modify: `agent-memory/angular/src/app/pages/performance/performance.component.ts`
- Modify: `agent-memory/angular/src/app/pages/analytics/analytics.component.ts`

- [ ] **Step 1: Add WS subscription to PerformanceComponent**

Add imports, `implements OnDestroy`, field, constructor.

Add to `ngOnInit()`:

```typescript
this.subs.push(
  this.ws.on('audit').subscribe(e => {
    this.timeseries.unshift(e.data);
    if (this.timeseries.length > 20) this.timeseries.pop();
    this.summary.totalOps = (this.summary.totalOps || 0) + 1;
    if (['store','delete','link','share','goals'].includes(e.data.operation)) {
      this.summary.totalWrites = (this.summary.totalWrites || 0) + 1;
    } else {
      this.summary.totalReads = (this.summary.totalReads || 0) + 1;
    }
  })
);
```

Add `ngOnDestroy`.

- [ ] **Step 2: Add WS subscription to AnalyticsComponent**

Add imports, `implements OnDestroy`, field, constructor.

Add to `ngOnInit()`:

```typescript
this.subs.push(
  this.ws.on('audit').subscribe(() => {
    // Reload agent stats on new activity
    this.api.getAgents().subscribe(agents => {
      this.totalAgents = agents.length;
      let maxOps = 0;
      const stats: any[] = [];
      let pending = agents.length;
      if (pending === 0) return;
      agents.forEach(a => {
        this.api.getAgentMetrics(a.name).subscribe(m => {
          const totalOps = (m.totalWrites || 0) + (m.totalReads || 0);
          stats.push({ name: a.name, totalOps });
          if (totalOps > maxOps) maxOps = totalOps;
          pending--;
          if (pending === 0) {
            this.agentStats = stats
              .sort((a, b) => b.totalOps - a.totalOps)
              .map(s => ({ ...s, pct: maxOps > 0 ? (s.totalOps / maxOps) * 100 : 0 }));
          }
        });
      });
    });
  }),
  this.ws.on('memory').subscribe(() => {
    this.api.getMemories().subscribe(m => {
      this.totalMemories = m.length;
      this.sharedMemories = m.filter((x: any) => x.shared).length;
    });
  }),
  this.ws.on('anomaly').subscribe(() => this.totalAnomalies++)
);
```

Add `ngOnDestroy`.

- [ ] **Step 3: Compile check**

Run: `cd agent-memory/angular && npx ng build --configuration production 2>&1 | tail -10`
Expected: Build succeeds

- [ ] **Step 4: Commit**

```bash
git add agent-memory/angular/src/app/pages/performance/performance.component.ts agent-memory/angular/src/app/pages/analytics/analytics.component.ts
git commit -m "feat(agent-memory): wire WS events into performance and analytics pages"
```

---

### Task 14: Add WS Settings to Settings Page

**Files:**
- Modify: `agent-memory/angular/src/app/pages/settings/settings.component.ts`

- [ ] **Step 1: Add WS throttle fields to settings template**

Add after the existing "Drift Threshold" form group:

```html
<h2 class="section-title" style="margin-top: 24px;">WebSocket</h2>
<div class="form-group">
  <label class="form-label">Throttle Interval (ms, 0 = immediate)</label>
  <input class="form-input" type="number" [(ngModel)]="settings.wsThrottleMs">
</div>
<div class="form-group">
  <label class="form-label">Default Topics (* = all)</label>
  <input class="form-input" type="text" [(ngModel)]="settings.wsDefaultTopics">
</div>
```

Update the defaults object:

```typescript
settings: any = { loopWindowSeconds: 60, loopThreshold: 5, driftThreshold: 0.3, wsThrottleMs: 0, wsDefaultTopics: '*' };
```

- [ ] **Step 2: Compile check**

Run: `cd agent-memory/angular && npx ng build --configuration production 2>&1 | tail -10`
Expected: Build succeeds

- [ ] **Step 3: Commit**

```bash
git add agent-memory/angular/src/app/pages/settings/settings.component.ts
git commit -m "feat(agent-memory): add WebSocket settings to settings page"
```

---

### Task 15: Build and Smoke Test

**Files:**
- No new files

- [ ] **Step 1: Run all Java tests**

Run: `cd agent-memory && ./gradlew test -q`
Expected: All tests pass

- [ ] **Step 2: Build Angular**

Run: `cd agent-memory/angular && npx ng build --configuration production 2>&1 | tail -10`
Expected: Build succeeds

- [ ] **Step 3: Build shadow JAR**

Run: `cd agent-memory && ./gradlew shadowJar -q`
Expected: BUILD SUCCESSFUL, JAR at `agent-memory/build/libs/agent-memory-0.1.0.jar`

- [ ] **Step 4: Commit any remaining changes**

```bash
git add -A
git commit -m "feat(agent-memory): WebSocket real-time events complete"
```
