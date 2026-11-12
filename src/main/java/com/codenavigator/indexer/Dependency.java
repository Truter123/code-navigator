package com.codenavigator.indexer;

public record Dependency(String group, String artifact, String version) {

    /** Returns "group:artifact" — the stable identifier without version. */
    public String coordinate() {
        return group + ":" + artifact;
    }

    /**
     * True when {@code importFqn} belongs to this artifact's packages: under the group-derived
     * prefix, or under the group's last segment alone for a group that adds a reverse-DNS
     * prefix to the package ({@code info.picocli} publishes {@code picocli.*}).
     */
    public boolean owns(String importFqn) {
        if (importFqn.startsWith(packagePrefix())) return true;
        String[] parts = group.split("\\.");
        return parts.length == 2 && importFqn.startsWith(parts[1] + ".");
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
