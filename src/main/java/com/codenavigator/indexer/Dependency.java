package com.codenavigator.indexer;

public record Dependency(String group, String artifact, String version) {

    /** Returns "group:artifact" — the stable identifier without version. */
    public String coordinate() {
        return group + ":" + artifact;
    }

    /**
     * Returns the root package prefix for this artifact, derived from the group id.
     * E.g. "org.springframework.boot:spring-boot-starter-web" -> "org.springframework"
     * Uses up to 2 segments for broad matching (avoids false positives from sub-artifacts).
     */
    public String packagePrefix() {
        String[] parts = group.split("\\.");
        if (parts.length <= 2) return group;
        return parts[0] + "." + parts[1];
    }
}
