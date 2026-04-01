package com.agentmemory.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "serve", description = "Start MCP server and dashboard")
public class ServeCommand implements Runnable {
    @Option(names = {"--port", "-p"}, description = "Dashboard HTTP port (default: 7070)", defaultValue = "7070")
    private int port;

    @Override
    public void run() {
        System.err.println("agent-memory server starting on port " + port + "...");
    }
}
