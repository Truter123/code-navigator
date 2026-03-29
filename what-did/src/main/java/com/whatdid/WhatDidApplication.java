package com.whatdid;

import com.whatdid.cli.ServeCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "what-did",
    mixinStandardHelpOptions = true,
    version = "0.1.0",
    description = "Track developer activity across git repos",
    subcommands = {
        ServeCommand.class
    }
)
public class WhatDidApplication implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new WhatDidApplication()).execute(args);
        System.exit(exitCode);
    }
}
