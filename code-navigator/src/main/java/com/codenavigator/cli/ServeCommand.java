package com.codenavigator.cli;

import com.codenavigator.domain.CodeNavigatorExtractor;
import com.codenavigator.domain.DomainSqliteStore;
import com.codenavigator.domain.DomainToolHandlers;
import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.GraphTraversal;
import com.codenavigator.mcp.CodeNavigatorMcpServer;
import com.codenavigator.search.SearchService;
import picocli.CommandLine.Command;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Command(name = "serve", description = "Start MCP server (stdio)")
public class ServeCommand implements Runnable {
    @Override
    public void run() {
        var projectPath = System.getenv("CODE_NAVIGATOR_PROJECT");
        var root = Path.of(projectPath != null ? projectPath : ".");

        if (!ProjectPaths.hasIndex(root)) {
            System.err.println("No index found at " + ProjectPaths.graphDb(root) + ". Run 'init' first.");
            return;
        }

        var store = new GraphStore(ProjectPaths.graphDb(root));
        var traversal = new GraphTraversal(store);
        var embeddingProvider = com.codenavigator.embedding.EmbeddingProviders.fromEnv();
        var search = new SearchService(store, traversal, embeddingProvider);

        // Bootstrap domain extraction
        var domainDb = ProjectPaths.domainDb(root);
        try { Files.createDirectories(domainDb.getParent()); } catch (IOException e) {
            System.err.println("Warning: cannot create domain directory");
        }
        var domainStore = new DomainSqliteStore(domainDb);
        if (domainStore.isEmpty() && ProjectPaths.hasIndex(root)) {
            System.err.println("Auto-extracting domain knowledge...");
            new CodeNavigatorExtractor().extract(domainStore, ProjectPaths.graphDb(root));
        }
        var domainHandlers = new DomainToolHandlers(domainStore, ProjectPaths.graphDb(root));

        var mcpServer = new CodeNavigatorMcpServer(store, traversal, search, domainHandlers, embeddingProvider);
        System.err.println("code-navigator MCP server started (stdio) — 22 tools");
        mcpServer.start();
    }
}
