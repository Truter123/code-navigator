package com.codenavigator.indexer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyParserTest {

    @TempDir
    Path projectRoot;

    private List<Dependency> parse() {
        return new DependencyParser().parse(projectRoot);
    }

    // ── build.gradle ──

    @Test
    void parsesGradleSingleQuoteShorthand() throws IOException {
        Files.writeString(projectRoot.resolve("build.gradle"), """
            plugins { id 'java' }
            dependencies {
                implementation 'org.springframework.boot:spring-boot-starter-web:3.2.0'
                testImplementation 'org.junit.jupiter:junit-jupiter:5.10.0'
            }
            """);

        var deps = parse();
        assertThat(deps).anyMatch(d ->
            d.group().equals("org.springframework.boot")
            && d.artifact().equals("spring-boot-starter-web")
            && d.version().equals("3.2.0"));
        assertThat(deps).anyMatch(d ->
            d.group().equals("org.junit.jupiter")
            && d.artifact().equals("junit-jupiter")
            && d.version().equals("5.10.0"));
    }

    @Test
    void parsesGradleDoubleQuoteShorthand() throws IOException {
        Files.writeString(projectRoot.resolve("build.gradle"), """
            dependencies {
                implementation "com.fasterxml.jackson.core:jackson-databind:2.18.2"
            }
            """);

        var deps = parse();
        assertThat(deps).anyMatch(d ->
            d.group().equals("com.fasterxml.jackson.core")
            && d.artifact().equals("jackson-databind"));
    }

    @Test
    void parsesGradleGroupNameVersionMap() throws IOException {
        Files.writeString(projectRoot.resolve("build.gradle"), """
            dependencies {
                implementation group: 'org.hibernate', name: 'hibernate-core', version: '6.4.0'
            }
            """);

        var deps = parse();
        assertThat(deps).anyMatch(d ->
            d.group().equals("org.hibernate")
            && d.artifact().equals("hibernate-core")
            && d.version().equals("6.4.0"));
    }

    @Test
    void parsesGradleKtsFile() throws IOException {
        Files.writeString(projectRoot.resolve("build.gradle.kts"), """
            dependencies {
                implementation("io.modelcontextprotocol.sdk:mcp:1.1.0")
            }
            """);

        var deps = parse();
        assertThat(deps).anyMatch(d ->
            d.group().equals("io.modelcontextprotocol.sdk")
            && d.artifact().equals("mcp"));
    }

    @Test
    void coordinateFormatIsGroupColonArtifact() throws IOException {
        Files.writeString(projectRoot.resolve("build.gradle"), """
            dependencies {
                implementation 'org.springframework.boot:spring-boot-starter-web:3.2.0'
            }
            """);

        var deps = parse();
        assertThat(deps.get(0).coordinate()).isEqualTo("org.springframework.boot:spring-boot-starter-web");
    }

    // ── pom.xml ──

    @Test
    void parsesMavenPom() throws IOException {
        Files.writeString(projectRoot.resolve("pom.xml"), """
            <project>
              <dependencies>
                <dependency>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-starter-data-jpa</artifactId>
                  <version>3.2.0</version>
                </dependency>
              </dependencies>
            </project>
            """);

        var deps = parse();
        assertThat(deps).anyMatch(d ->
            d.group().equals("org.springframework.boot")
            && d.artifact().equals("spring-boot-starter-data-jpa")
            && d.version().equals("3.2.0"));
    }

    @Test
    void returnsEmptyWhenNoBuildFile() {
        // projectRoot has no build file
        assertThat(parse()).isEmpty();
    }

    @Test
    void deduplicatesDependencies() throws IOException {
        Files.writeString(projectRoot.resolve("build.gradle"), """
            dependencies {
                implementation 'org.springframework.boot:spring-boot-starter-web:3.2.0'
                runtimeOnly 'org.springframework.boot:spring-boot-starter-web:3.2.0'
            }
            """);

        var deps = parse();
        long count = deps.stream()
            .filter(d -> d.artifact().equals("spring-boot-starter-web"))
            .count();
        assertThat(count).isEqualTo(1);
    }
}
