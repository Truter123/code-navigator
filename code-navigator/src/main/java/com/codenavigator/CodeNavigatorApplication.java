package com.codenavigator;

import com.codenavigator.cli.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "code-navigator",
    mixinStandardHelpOptions = true,
    version = "0.1.0",
    description = "Multi-tier Java code navigator MCP server",
    subcommands = {
        InitCommand.class,
        SyncCommand.class,
        ServeCommand.class,
        StatusCommand.class,
        MarkDirtyCommand.class,
        SyncIfDirtyCommand.class,
        InstallCommand.class,
        ExportCommand.class,
        BriefingCommand.class,
        BenchmarkCommand.class
    }
)
public class CodeNavigatorApplication implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new CodeNavigatorApplication()).execute(args);
        System.exit(exitCode);
    }
}
