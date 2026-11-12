package com.codenavigator.mcp;

import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.GraphTraversal;
import com.codenavigator.indexer.ProjectIndexer;
import com.codenavigator.search.SearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A class used through {@code new}, a static call, {@code main} or picocli {@code @Command} is not HIGH-confidence dead. */
class DeadCodeEntryPointsTest {

    @TempDir Path tempDir;
    private GraphStore store;
    private CodeNavigatorMcpServer server;

    @BeforeEach
    void setUp() {
        store = new GraphStore(tempDir.resolve("dead.db"));
        var traversal = new GraphTraversal(store);
        server = new CodeNavigatorMcpServer(store, traversal, new SearchService(store, traversal));
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private String highSection(Map<String, String> sources) throws IOException {
        Path dir = tempDir.resolve("proj/src/main/java/com/sample");
        Files.createDirectories(dir);
        for (var e : sources.entrySet()) {
            Files.writeString(dir.resolve(e.getKey() + ".java"), "package com.sample;\n" + e.getValue());
        }
        new ProjectIndexer(store).indexFull(tempDir.resolve("proj"));
        String reply = server.handleCgDead(Map.of("limit", 100));
        int start = reply.indexOf("### HIGH");
        if (start < 0) return "";
        int end = reply.indexOf("\n###", start + 1);
        return end < 0 ? reply.substring(start) : reply.substring(start, end);
    }

    @Test
    void classCreatedWithNewIsNotHigh() throws IOException {
        String high = highSection(Map.of(
            "Made", "class Made {}",
            "Maker", "class Maker { Object make() { return new Made(); } }"));
        assertThat(high).doesNotContain("Made");
    }

    @Test
    void classCalledThroughStaticMethodIsNotHigh() throws IOException {
        String high = highSection(Map.of(
            "Helper", "class Helper { static int twice(int x) { return x * 2; } }",
            "User", "class User { int run() { return Helper.twice(2); } }"));
        assertThat(high).doesNotContain("Helper");
    }

    @Test
    void deletedFileLeavesNoEntryPointRow() throws IOException {
        Path dir = tempDir.resolve("proj/src/main/java/com/sample");
        Files.createDirectories(dir);
        Path file = dir.resolve("Launcher.java");
        Files.writeString(file, "package com.sample;\nclass Launcher { public static void main(String[] a) {} }");
        var indexer = new ProjectIndexer(store);
        indexer.indexFull(tempDir.resolve("proj"));
        assertThat(store.isEntryPoint("com.sample.Launcher")).isTrue();

        Files.delete(file);
        indexer.indexIncremental(tempDir.resolve("proj"));

        assertThat(store.isEntryPoint("com.sample.Launcher")).isFalse();
    }

    @Test
    void classWithMainIsNotHigh() throws IOException {
        String high = highSection(Map.of(
            "Launcher", "class Launcher { public static void main(String[] args) {} }"));
        assertThat(high).doesNotContain("Launcher");
    }

    @Test
    void classWithPicocliCommandIsNotHigh() throws IOException {
        String high = highSection(Map.of(
            "InitCommand", "import picocli.CommandLine.Command;\n@Command(name = \"init\")\nclass InitCommand implements Runnable { public void run() {} }"));
        assertThat(high).doesNotContain("InitCommand");
    }

    @Test
    void unreferencedClassIsStillHigh() throws IOException {
        String high = highSection(Map.of("Orphan", "class Orphan { void x() {} }"));
        assertThat(high).contains("Orphan");
    }

    @Test
    void commandClassWithLongJavadocIsNotHigh() throws IOException {
        String doc = "/**\n" + " * line\n".repeat(15) + " */\n";
        String high = highSection(Map.of(
            "LongCommand", "import picocli.CommandLine.Command;\n" + doc + "@Command(name = \"x\")\nclass LongCommand {}"));
        assertThat(high).doesNotContain("LongCommand");
    }

    @Test
    void nestedCommandClassIsNotHigh() throws IOException {
        String high = highSection(Map.of(
            "Outer", "import picocli.CommandLine;\nclass Outer {\n    @CommandLine.Command(name = \"n\")\n    static class Inner {}\n}"));
        assertThat(high).doesNotContain("Inner");
    }

    @Test
    void nonPicocliCommandAnnotationIsStillHigh() throws IOException {
        String high = highSection(Map.of(
            "Command", "@interface Command {}",
            "Fake", "@Command\nclass Fake {}"));
        assertThat(high).contains("Fake");
    }

    @Test
    void nonStaticMainIsStillHigh() throws IOException {
        String high = highSection(Map.of(
            "Inst", "class Inst { public void main(String s) {} }"));
        assertThat(high).contains("Inst");
    }

    @Test
    void newOnUnindexedTypeEmitsNoEdgeAndDoesNotFail() throws IOException {
        String high = highSection(Map.of(
            "Maker", "class Maker { Object make() { return new java.util.ArrayList<String>(); } Object o() { return new Unknown(); } }"));
        assertThat(high).contains("Maker");
    }

    @Test
    void parameterNamedLikeIndexedClassCreatesNoEdge() throws IOException {
        String high = highSection(Map.of(
            "Helper", "class Helper { static void run() {} }",
            "Caller", "class Caller { void go(Runnable Helper) { Helper.run(); } }"));
        assertThat(high).contains("Helper");
    }
}
