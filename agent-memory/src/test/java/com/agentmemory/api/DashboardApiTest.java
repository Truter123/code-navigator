package com.agentmemory.api;

import com.agentmemory.brain.BrainEngine;
import com.agentmemory.store.GraphStore;
import com.agentmemory.store.MemoryStore;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardApiTest {

    @TempDir
    Path tempDir;

    MemoryStore store;
    GraphStore graphStore;
    BrainEngine brain;

    Javalin app;

    @BeforeEach
    void setUp() {
        store = new MemoryStore(tempDir.resolve("test.db"));
        graphStore = new GraphStore(store);
        brain = new BrainEngine(store, graphStore);

        DashboardApi api = new DashboardApi(store, graphStore, brain);
        app = Javalin.create(config -> {
            api.register(config.routes);
        });
    }

    @Test
    void listMemoriesReturnsStoredMemories() {
        store.upsert("test-key", "test-value", "agent1", "proj1",
                List.of("tag1"), 0.5, false);

        JavalinTest.test(app, (server, client) -> {
            var response = client.get("/api/memories");
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).contains("test-key");
        });
    }

    @Test
    void getAuditReturnsEntries() {
        store.logAudit("agent1", "store", "test-key", null, 1.5);

        JavalinTest.test(app, (server, client) -> {
            var response = client.get("/api/audit");
            assertThat(response.code()).isEqualTo(200);
            String body = response.body().string();
            assertThat(body).contains("agent1");
            assertThat(body).contains("store");
        });
    }

    @Test
    void getMemoryReturns404WhenNotFound() {
        JavalinTest.test(app, (server, client) -> {
            var response = client.get("/api/memories/nonexistent");
            assertThat(response.code()).isEqualTo(404);
        });
    }

    @Test
    void getSettingsReturnsEmptyInitially() {
        JavalinTest.test(app, (server, client) -> {
            var response = client.get("/api/settings");
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).isEqualTo("{}");
        });
    }
}
