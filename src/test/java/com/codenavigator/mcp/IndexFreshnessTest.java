package com.codenavigator.mcp;

import com.codenavigator.cli.ProjectPaths;
import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.GraphTraversal;
import com.codenavigator.indexer.ProjectIndexer;
import com.codenavigator.search.SearchService;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IndexFreshnessTest {

    @TempDir Path tempDir;
    private GraphStore defaultStore;
    private CodeNavigatorMcpServer server;

    @BeforeEach
    void setUp() {
        defaultStore = new GraphStore(tempDir.resolve("default.db"));
        var traversal = new GraphTraversal(defaultStore);
        server = new CodeNavigatorMcpServer(defaultStore, traversal, new SearchService(defaultStore, traversal));
    }

    @AfterEach
    void tearDown() { defaultStore.close(); }

    private Path project(String name, boolean git, int classes) throws Exception {
        Path root = tempDir.resolve(name);
        Path dir = root.resolve("src/main/java/com/sample");
        Files.createDirectories(dir);
        for (int i = 0; i < classes; i++) {
            Files.writeString(dir.resolve("Widget" + i + "LongNameToFillTheReply.java"),
                "package com.sample;\npublic class Widget" + i + "LongNameToFillTheReply {}\n");
        }
        if (git) {
            try (Git g = Git.init().setDirectory(root.toFile()).call()) {
                g.add().addFilepattern(".").call();
                g.commit().setMessage("one").setSign(false).call();
            }
        }
        index(root);
        return root;
    }

    private void index(Path root) throws Exception {
        Files.createDirectories(ProjectPaths.codeGraphDir(root));
        try (var store = new GraphStore(ProjectPaths.graphDb(root))) {
            new ProjectIndexer(store).indexFull(root);
        }
    }

    private void sync(Path root) {
        try (var store = new GraphStore(ProjectPaths.graphDb(root))) {
            new ProjectIndexer(store).indexIncremental(root);
        }
    }

    private void commit(Path root, String msg) throws Exception {
        try (Git g = Git.open(root.toFile())) {
            g.commit().setMessage(msg).setAllowEmpty(true).setSign(false).call();
        }
    }

    private String call(String tool, Path root, Map<String, Object> more) {
        var args = new HashMap<String, Object>(more);
        args.put("projectPath", root.toString());
        return server.reply(tool, args);
    }

    private String node(Path root) { return call("cg_node", root, Map.of("symbol", "Widget0LongNameToFillTheReply")); }

    @Test
    void currentAfterInitAddsNothingToOtherReplies() throws Exception {
        Path git = project("withgit", true, 1);
        Path plain = project("plain", false, 1);

        assertThat(call("cg_map", git, Map.of())).contains("**Index:** current");
        assertThat(node(git)).doesNotContain("Index stale")
            .isEqualTo(node(plain).replace("plain", "withgit"));
    }

    @Test
    void newCommitMakesRepliesStaleUntilSync() throws Exception {
        Path root = project("proj", true, 1);
        commit(root, "two");

        String stale = "Index stale: 1 commit(s) behind, run sync.\n\n";
        assertThat(node(root)).startsWith(stale);
        assertThat(call("cg_guard", root, Map.of("symbol", "Widget0LongNameToFillTheReply"))).startsWith(stale);
        assertThat(call("cg_map", root, Map.of())).contains("**Index:** 1 commit(s) behind HEAD");

        sync(root);
        assertThat(node(root)).doesNotContain("Index stale");
        assertThat(call("cg_map", root, Map.of())).contains("**Index:** current");
    }

    @Test
    void dirtyFlagAloneIsStale() throws Exception {
        Path root = project("proj", true, 1);
        Files.writeString(ProjectPaths.dirtyFile(root), "");

        assertThat(node(root)).startsWith("Index stale: dirty flag set, run sync.\n\n");
        assertThat(call("cg_map", root, Map.of())).contains("**Index:** dirty flag set");
    }

    @Test
    void shaThatIsNotAnAncestorSaysHeadChanged() throws Exception {
        Path root = project("proj", true, 1);
        commit(root, "two");
        index(root);                                    // stores the second commit
        try (Git g = Git.open(root.toFile())) {
            g.checkout().setOrphan(true).setName("other").call();
            g.commit().setMessage("orphan").setAllowEmpty(true).setSign(false).call();
        }

        assertThat(node(root)).startsWith("Index stale: HEAD changed since sync, run sync.\n\n");
        assertThat(call("cg_map", root, Map.of())).contains("**Index:** HEAD changed since sync");
    }

    @Test
    void notAGitRepoGetsNoLineAndNoError() throws Exception {
        Path root = project("plain", false, 1);
        Files.writeString(ProjectPaths.dirtyFile(root), "");

        assertThat(node(root)).doesNotContain("Index stale").contains("Widget0");
        assertThat(call("cg_map", root, Map.of())).doesNotContain("**Index:**");
    }

    @Test
    void indexWithoutStoredShaIsUnknownNotStale() throws Exception {
        Path root = project("proj", true, 1);
        try (var store = new GraphStore(ProjectPaths.graphDb(root))) {
            store.setConfig("index.head", "");
        }
        // an older index has no key at all: drop it
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + ProjectPaths.graphDb(root));
             var st = c.createStatement()) {
            st.execute("DELETE FROM project_config WHERE key LIKE 'index.%'");
        }
        commit(root, "two");

        assertThat(node(root)).doesNotContain("Index stale");
        assertThat(call("cg_map", root, Map.of())).doesNotContain("**Index:**");
    }

    @Test
    void staleLineKeepsTheReplyWithinTheCap() throws Exception {
        Path root = project("big", true, 200);
        var search = Map.<String, Object>of("query", "LongNameToFillTheReply", "limit", 1000);
        String current = call("cg_search", root, search);
        assertThat(current.length()).isGreaterThan(5000).isLessThanOrEqualTo(6000);

        commit(root, "two");
        String stale = call("cg_search", root, search);
        assertThat(stale).startsWith("Index stale: 1 commit(s) behind, run sync.\n\n");
        assertThat(stale.length()).isLessThanOrEqualTo(6000);
    }

    @Test
    void eachProjectPathReportsItsOwnState() throws Exception {
        Path a = project("a", true, 1);
        Path b = project("b", true, 1);
        commit(b, "two");

        assertThat(node(a)).doesNotContain("Index stale");
        assertThat(node(b)).startsWith("Index stale: 1 commit(s) behind");
    }

    @Test
    void fastSyncAfterABodyEditClearsTheStaleLine() throws Exception {
        Path root = project("proj", true, 5);
        Path file = root.resolve("src/main/java/com/sample/Widget1LongNameToFillTheReply.java");
        Files.writeString(file, "package com.sample;\npublic class Widget1LongNameToFillTheReply { int field; }\n");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10_000));
        commit(root, "two");
        assertThat(node(root)).startsWith("Index stale: 1 commit(s) behind");

        ProjectIndexer.SyncResult result;
        try (var store = new GraphStore(ProjectPaths.graphDb(root))) {
            result = new ProjectIndexer(store).indexIncremental(root);
        }

        assertThat(result.fastPath()).as(result.fallbackReason()).isTrue();
        assertThat(node(root)).doesNotContain("Index stale");
        assertThat(call("cg_map", root, Map.of())).contains("**Index:** current");
    }
}
