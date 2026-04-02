package com.agentmemory.cli;

import com.agentmemory.api.DashboardApi;
import com.agentmemory.brain.BrainEngine;
import com.agentmemory.mcp.AgentMemoryMcpServer;
import com.agentmemory.store.GraphStore;
import com.agentmemory.store.MemoryStore;
import com.agentmemory.ws.EventBus;
import com.agentmemory.ws.WebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

@Command(name = "serve", description = "Start MCP server and dashboard")
public class ServeCommand implements Runnable {

    @Option(names = {"--port", "-p"}, description = "Dashboard HTTP port (default: 7070)", defaultValue = "7070")
    private int port;

    @Option(names = {"--db"}, description = "Database path (default: <project-dir>/memory.db)")
    private String dbPath;

    @Override
    public void run() {
        // 1. Determine db path
        Path db;
        if (dbPath != null) {
            db = Path.of(dbPath);
        } else {
            try {
                db = Path.of(ServeCommand.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent().resolve("memory.db");
            } catch (Exception e) {
                db = Path.of("memory.db").toAbsolutePath();
            }
        }

        // 2. Create directories, init stores
        try {
            Files.createDirectories(db.getParent());
        } catch (Exception e) {
            System.err.println("Failed to create directory: " + db.getParent());
            return;
        }

        MemoryStore memoryStore = new MemoryStore(db);
        GraphStore graphStore = new GraphStore(memoryStore);
        BrainEngine brain = new BrainEngine(memoryStore, graphStore);

        ObjectMapper objectMapper = new ObjectMapper();
        String throttleSetting = memoryStore.getSetting("ws.throttle.ms");
        long throttleMs = throttleSetting != null ? Long.parseLong(throttleSetting) : 0;
        EventBus eventBus = new EventBus(objectMapper, throttleMs);

        // 3. Start Javalin on daemon thread
        DashboardApi dashboardApi = new DashboardApi(memoryStore, graphStore, brain);

        final int dashboardPort = port;
        Thread dashboardThread = new Thread(() -> {
            WebSocketHandler wsHandler = new WebSocketHandler(
                eventBus, objectMapper,
                Set.of("audit", "memory", "anomaly", "agent", "goal", "graph")
            );

            Javalin app = Javalin.create(config -> {
                config.staticFiles.add("/static/browser");
                config.startup.showJavalinBanner = false;
                config.spaRoot.addFile("/", "/static/browser/index.html", io.javalin.http.staticfiles.Location.CLASSPATH);

                // CORS headers
                config.routes.before(ctx -> {
                    ctx.header("Access-Control-Allow-Origin", "*");
                    ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
                    ctx.header("Access-Control-Allow-Headers", "Content-Type");
                });

                config.routes.options("/*", ctx -> ctx.status(204));

                // Register API routes
                dashboardApi.register(config.routes);

                // Register WebSocket routes
                wsHandler.register(config.routes);
            });

            app.start(dashboardPort);
            System.err.println("Dashboard started on http://localhost:" + dashboardPort);
        });
        dashboardThread.setDaemon(true);
        dashboardThread.start();

        // 4. Start MCP server on main thread (blocks)
        System.err.println("agent-memory MCP server starting (db: " + db + ")...");
        AgentMemoryMcpServer mcpServer = new AgentMemoryMcpServer(memoryStore, graphStore, brain, eventBus);
        mcpServer.start();
    }
}
