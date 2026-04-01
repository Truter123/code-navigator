package com.agentmemory.cli;

import com.agentmemory.api.DashboardApi;
import com.agentmemory.brain.BrainEngine;
import com.agentmemory.mcp.AgentMemoryMcpServer;
import com.agentmemory.store.GraphStore;
import com.agentmemory.store.MemoryStore;
import io.javalin.Javalin;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;

@Command(name = "serve", description = "Start MCP server and dashboard")
public class ServeCommand implements Runnable {

    @Option(names = {"--port", "-p"}, description = "Dashboard HTTP port (default: 7070)", defaultValue = "7070")
    private int port;

    @Option(names = {"--db"}, description = "Database path (default: ~/.agent-memory/memory.db)")
    private String dbPath;

    @Override
    public void run() {
        // 1. Determine db path
        Path db;
        if (dbPath != null) {
            db = Path.of(dbPath);
        } else {
            db = Path.of(System.getProperty("user.home"), ".agent-memory", "memory.db");
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

        // 3. Start Javalin on daemon thread
        DashboardApi dashboardApi = new DashboardApi(memoryStore, graphStore, brain);

        final int dashboardPort = port;
        Thread dashboardThread = new Thread(() -> {
            Javalin app = Javalin.create(config -> {
                config.staticFiles.add("/static");
                config.startup.showJavalinBanner = false;

                // CORS headers
                config.routes.before(ctx -> {
                    ctx.header("Access-Control-Allow-Origin", "*");
                    ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
                    ctx.header("Access-Control-Allow-Headers", "Content-Type");
                });

                config.routes.options("/*", ctx -> ctx.status(204));

                // Register API routes
                dashboardApi.register(config.routes);

                // SPA fallback: serve index.html for non-API paths
                config.routes.error(404, ctx -> {
                    if (!ctx.path().startsWith("/api/")) {
                        ctx.result("""
                            <!DOCTYPE html>
                            <html><body>
                            <h1>Agent Memory Dashboard</h1>
                            <p>Dashboard UI not yet built. API available at /api/*</p>
                            </body></html>
                        """);
                        ctx.contentType("text/html");
                        ctx.status(200);
                    }
                });
            });

            app.start(dashboardPort);
            System.err.println("Dashboard started on http://localhost:" + dashboardPort);
        });
        dashboardThread.setDaemon(true);
        dashboardThread.start();

        // 4. Start MCP server on main thread (blocks)
        System.err.println("agent-memory MCP server starting (db: " + db + ")...");
        AgentMemoryMcpServer mcpServer = new AgentMemoryMcpServer(memoryStore, graphStore, brain);
        mcpServer.start();
    }
}
