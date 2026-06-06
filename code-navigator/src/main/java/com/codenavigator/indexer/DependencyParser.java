package com.codenavigator.indexer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses declared dependencies from build.gradle, build.gradle.kts, or pom.xml.
 * Purely offline — reads only the build file text, no network or jar inspection.
 */
public class DependencyParser {

    // Matches both Groovy shorthand (implementation 'g:a:v') and Kotlin DSL
    // (implementation("g:a:v")). The "\\s*\\(?\\s*" between the config name and the
    // opening quote tolerates an optional '(' so the Kotlin DSL form is covered too.
    private static final Pattern GRADLE_SHORTHAND = Pattern.compile(
        "(?:implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|annotationProcessor)"
        + "\\s*\\(?\\s*['\"]([\\.\\w-]+):([\\.\\w-]+)(?::([\\.\\w-]+))?['\"]");

    // Matches: group: 'g', name: 'a', version: 'v'  (any quote style)
    private static final Pattern GRADLE_MAP = Pattern.compile(
        "group:\\s*['\"]([^'\"]+)['\"].*?name:\\s*['\"]([^'\"]+)['\"](?:.*?version:\\s*['\"]([^'\"]+)['\"])?");

    // Maven pom.xml <dependency> block
    private static final Pattern MAVEN_GROUP    = Pattern.compile("<groupId>([^<]+)</groupId>");
    private static final Pattern MAVEN_ARTIFACT = Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern MAVEN_VERSION  = Pattern.compile("<version>([^<]+)</version>");
    private static final Pattern MAVEN_DEP_BLOCK = Pattern.compile(
        "<dependency>(.*?)</dependency>", Pattern.DOTALL);

    public List<Dependency> parse(Path projectRoot) {
        var seen = new LinkedHashSet<Dependency>();

        // Try Gradle files first
        for (String name : List.of("build.gradle", "build.gradle.kts")) {
            Path buildFile = projectRoot.resolve(name);
            if (Files.exists(buildFile)) {
                parseGradle(buildFile, seen);
                break; // stop after first found
            }
        }

        // Try Maven pom
        Path pomFile = projectRoot.resolve("pom.xml");
        if (Files.exists(pomFile)) {
            parseMaven(pomFile, seen);
        }

        return new ArrayList<>(seen);
    }

    private void parseGradle(Path buildFile, LinkedHashSet<Dependency> seen) {
        String content;
        try {
            content = Files.readString(buildFile);
        } catch (IOException e) {
            return;
        }

        // Shorthand: 'g:a:v' or "g:a:v" (Groovy or Kotlin DSL)
        var m1 = GRADLE_SHORTHAND.matcher(content);
        while (m1.find()) {
            String group    = m1.group(1);
            String artifact = m1.group(2);
            String version  = m1.group(3) != null ? m1.group(3) : "";
            seen.add(new Dependency(group, artifact, version));
        }

        // Map form: group: 'g', name: 'a', version: 'v'
        var m2 = GRADLE_MAP.matcher(content);
        while (m2.find()) {
            String group    = m2.group(1);
            String artifact = m2.group(2);
            String version  = m2.group(3) != null ? m2.group(3) : "";
            seen.add(new Dependency(group, artifact, version));
        }
    }

    private void parseMaven(Path pomFile, LinkedHashSet<Dependency> seen) {
        String content;
        try {
            content = Files.readString(pomFile);
        } catch (IOException e) {
            return;
        }

        var blockMatcher = MAVEN_DEP_BLOCK.matcher(content);
        while (blockMatcher.find()) {
            String block = blockMatcher.group(1);
            var gm = MAVEN_GROUP.matcher(block);
            var am = MAVEN_ARTIFACT.matcher(block);
            var vm = MAVEN_VERSION.matcher(block);
            if (gm.find() && am.find()) {
                String group    = gm.group(1).trim();
                String artifact = am.group(1).trim();
                String version  = vm.find() ? vm.group(1).trim() : "";
                seen.add(new Dependency(group, artifact, version));
            }
        }
    }
}
