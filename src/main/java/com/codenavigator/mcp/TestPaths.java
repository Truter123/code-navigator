package com.codenavigator.mcp;

/**
 * The one rule for "is this file under a test source root" shared by the tools that hide or count
 * test rows. Node paths are stored relative to the project root, so {@code src/test/...} has no
 * leading slash; a rule that demands one matches nothing.
 */
final class TestPaths {

    private TestPaths() {}

    /** A path under a {@code src/test/} directory, repo-relative or not, with either separator. */
    static boolean isUnderSrcTest(String path) {
        if (path == null) return false;
        String p = path.replace('\\', '/');
        return p.startsWith("src/test/") || p.contains("/src/test/");
    }
}
