package com.whatdid;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "what-did",
    mixinStandardHelpOptions = true,
    version = "0.1.0",
    description = "Track developer activity across git repos"
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
