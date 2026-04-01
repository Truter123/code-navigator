package com.agentmemory;

import com.agentmemory.cli.ServeCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "agent-memory",
    mixinStandardHelpOptions = true,
    version = "0.1.0",
    description = "Agent memory MCP server with dashboard",
    subcommands = { ServeCommand.class }
)
public class AgentMemoryApplication implements Runnable {
    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new AgentMemoryApplication()).execute(args);
        System.exit(exitCode);
    }
}
