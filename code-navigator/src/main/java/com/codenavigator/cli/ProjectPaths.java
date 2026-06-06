package com.codenavigator.cli;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared path constants and resolution for the navigators directory structure.
 */
public final class ProjectPaths {

    private static final String CODE_GRAPH_DIR = "navigators/code";
    private static final String DB_FILE = "code-navigator.db";
    private static final String DIRTY_FILE = ".dirty";

    private ProjectPaths() {}

    public static Path codeGraphDir(Path projectRoot) {
        return projectRoot.resolve(CODE_GRAPH_DIR);
    }

    public static Path graphDb(Path projectRoot) {
        return codeGraphDir(projectRoot).resolve(DB_FILE);
    }

    public static Path dirtyFile(Path projectRoot) {
        return codeGraphDir(projectRoot).resolve(DIRTY_FILE);
    }

    public static boolean hasIndex(Path projectRoot) {
        return Files.exists(graphDb(projectRoot));
    }

    private static final String DOMAIN_GRAPH_DIR = "navigators/domain";
    private static final String DOMAIN_DB_FILE = "domain-navigator.db";

    public static Path domainDb(Path projectRoot) {
        return projectRoot.resolve(DOMAIN_GRAPH_DIR).resolve(DOMAIN_DB_FILE);
    }

    public static boolean hasDomainIndex(Path projectRoot) {
        return Files.exists(domainDb(projectRoot));
    }

    /**
     * Resolve project path from: (1) explicit path, (2) CLI argument, (3) env var, (4) cwd.
     */
    public static Path resolveProjectPath(Path explicitPath, String cliArg) {
        if (explicitPath != null) return explicitPath;
        if (cliArg != null && !cliArg.isEmpty()) return Path.of(cliArg);
        var env = System.getenv("CODE_NAVIGATOR_PROJECT");
        return Path.of(env != null ? env : ".");
    }
}
