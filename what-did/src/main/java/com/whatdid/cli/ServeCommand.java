package com.whatdid.cli;

import com.whatdid.mcp.WhatDidMcpServer;
import com.whatdid.scanner.GitScanner;
import com.whatdid.store.WhatDidStore;
import picocli.CommandLine.Command;

import java.nio.file.Files;
import java.nio.file.Path;

@Command(name = "serve", description = "Start MCP server (stdio)")
public class ServeCommand implements Runnable {

    @Override
    public void run() {
        var dbPathEnv = System.getenv("WHAT_DID_DB");
        var dbPath = dbPathEnv != null ? Path.of(dbPathEnv) : Path.of(System.getProperty("user.home"), ".what-did", "what-did.db");

        try {
            Files.createDirectories(dbPath.getParent());
        } catch (Exception e) {
            System.err.println("Failed to create directory: " + dbPath.getParent());
            return;
        }

        var store = new WhatDidStore(dbPath);
        var scanner = new GitScanner(store);
        var mcpServer = new WhatDidMcpServer(store, scanner);

        System.err.println("what-did MCP server started (stdio)");
        mcpServer.start();
    }
}
