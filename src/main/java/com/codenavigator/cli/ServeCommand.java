package com.codenavigator.cli;

import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.GraphTraversal;
import com.codenavigator.mcp.CodeNavigatorMcpServer;
import com.codenavigator.search.SearchService;
import picocli.CommandLine.Command;
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
        var search = new SearchService(store, traversal);

        var mcpServer = new CodeNavigatorMcpServer(store, traversal, search);
        System.err.println("code-navigator MCP server started (stdio) — 9 tools, project " + root);
        mcpServer.start();
    }
}
