package com.codenavigator.mcp;

import com.codenavigator.domain.DomainSqliteStore;
import com.codenavigator.domain.DomainToolHandlers;
import com.codenavigator.graph.*;
import com.codenavigator.search.SearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CgDepsHandlerTest {

    @TempDir Path tempDir;
    private GraphStore store;
    private CodeNavigatorMcpServer mcpServer;

    @BeforeEach
    void setUp() {
        store = new GraphStore(tempDir.resolve("test.db"));
        var traversal = new GraphTraversal(store);
        var search = new SearchService(store, traversal);
        var domainHandlers = new DomainToolHandlers(new DomainSqliteStore(tempDir.resolve("domain.db")), null);
        mcpServer = new CodeNavigatorMcpServer(store, traversal, search, domainHandlers);
        buildLibraryGraph();
    }

    @AfterEach
    void tearDown() { store.close(); }

    private void buildLibraryGraph() {
        // Two internal nodes
        store.saveNode(new Node("com.example.OrderController", NodeType.CONTROLLER, "OrderController",
            "com.example.OrderController", "Ctrl.java", 1, "", 0));
        store.saveNode(new Node("com.example.PaymentController", NodeType.CONTROLLER, "PaymentController",
            "com.example.PaymentController", "Pay.java", 1, "", 0));

        // Two LIBRARY nodes from the same artifact
        store.saveNode(new Node(
            "org.springframework.web.bind.annotation.RestController",
            NodeType.LIBRARY, "RestController",
            "org.springframework.web.bind.annotation.RestController",
            "", 0, "org.springframework.boot:spring-boot-starter-web", 0));
        store.saveNode(new Node(
            "org.springframework.web.bind.annotation.RequestMapping",
            NodeType.LIBRARY, "RequestMapping",
            "org.springframework.web.bind.annotation.RequestMapping",
            "", 0, "org.springframework.boot:spring-boot-starter-web", 0));

        // One LIBRARY node from a different artifact
        store.saveNode(new Node(
            "org.hibernate.annotations.Entity",
            NodeType.LIBRARY, "Entity",
            "org.hibernate.annotations.Entity",
            "", 0, "org.hibernate:hibernate-core", 0));

        // USES_LIBRARY edges
        store.saveEdge(new Edge("e1", EdgeType.USES_LIBRARY,
            "com.example.OrderController", "org.springframework.web.bind.annotation.RestController"));
        store.saveEdge(new Edge("e2", EdgeType.USES_LIBRARY,
            "com.example.OrderController", "org.springframework.web.bind.annotation.RequestMapping"));
        store.saveEdge(new Edge("e3", EdgeType.USES_LIBRARY,
            "com.example.PaymentController", "org.springframework.web.bind.annotation.RestController"));
        store.saveEdge(new Edge("e4", EdgeType.USES_LIBRARY,
            "com.example.PaymentController", "org.hibernate.annotations.Entity"));

        // Store declared dependencies in config (JSON array of "g:a:v")
        store.setConfig("dependencies",
            "[\"org.springframework.boot:spring-boot-starter-web:3.2.0\",\"org.hibernate:hibernate-core:6.4.0\"]");
    }

    @Test
    void cgDepsListsDeclaredDependencies() {
        var result = mcpServer.handleCgDeps(Map.of());
        assertThat(result).contains("spring-boot-starter-web");
        assertThat(result).contains("hibernate-core");
    }

    @Test
    void cgDepsShowsUsageCountPerDependency() {
        var result = mcpServer.handleCgDeps(Map.of());
        // spring-boot-starter-web: 2 LIBRARY nodes (RestController, RequestMapping)
        assertThat(result).contains("spring-boot-starter-web");
        assertThat(result).contains("2");
        // hibernate-core: 1 LIBRARY node
        assertThat(result).contains("hibernate-core");
        assertThat(result).contains("1");
    }

    @Test
    void cgDepsReturnsMessageWhenNoDependenciesStored() {
        // Use a fresh store/server with NO "dependencies" config key set.
        // (Do NOT call setConfig("dependencies", null) — the project_config.value
        // column is TEXT NOT NULL, so a null bind throws a SQLiteException.
        // getConfig returns null for an absent key, which is the case under test.)
        var emptyStore = new GraphStore(tempDir.resolve("empty.db"));
        var emptyTraversal = new GraphTraversal(emptyStore);
        var emptySearch = new SearchService(emptyStore, emptyTraversal);
        var emptyDomain = new DomainToolHandlers(
            new DomainSqliteStore(tempDir.resolve("empty-domain.db")), null);
        var emptyServer = new CodeNavigatorMcpServer(emptyStore, emptyTraversal, emptySearch, emptyDomain);

        var result = emptyServer.handleCgDeps(Map.of());
        assertThat(result).contains("No dependency");
        emptyStore.close();
    }
}
