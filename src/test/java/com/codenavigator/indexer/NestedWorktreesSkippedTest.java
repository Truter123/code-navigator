package com.codenavigator.indexer;

import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A git worktree under {@code <root>/.claude/worktrees} is a second copy of the project. Indexing it
 * with the root multiplies the work per worktree and lets a copy overwrite the real node.
 */
class NestedWorktreesSkippedTest {

    private static final String WORKTREE = ".claude/worktrees/wt/";

    @TempDir Path tempDir;

    private Path fixture() throws IOException {
        Path root = tempDir.resolve("fx");
        write(root, "src/main/java/com/acme/Real.java",
            "package com.acme;\npublic class Real { public int one() { return 1; } }\n");
        write(root, "scripts/run.sh", "#!/bin/bash\necho real\n");
        write(root, WORKTREE + "src/main/java/com/acme/Real.java",
            "package com.acme;\npublic class Real { public int one() { return 1; } public int copy() { return 2; } }\n");
        write(root, WORKTREE + "src/main/java/com/acme/OnlyInWorktree.java",
            "package com.acme;\npublic class OnlyInWorktree { public int two() { return 2; } }\n");
        write(root, WORKTREE + "scripts/only-in-worktree.sh", "#!/bin/bash\necho copy\n");
        return root;
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void indexFullLeavesTheWorktreeCopyOut() throws IOException {
        Path root = fixture();
        try (var store = new GraphStore(tempDir.resolve("a.db"))) {
            new ProjectIndexer(store).indexFull(root);

            assertThat(store.getAllIndexedFiles()).noneMatch(f -> f.contains(".claude/worktrees"));
            assertThat(store.getAllNodes()).noneMatch(n -> n.filePath() != null && n.filePath().contains(".claude/worktrees"));
            assertThat(store.findNodeById("com.acme.OnlyInWorktree")).isEmpty();
            assertThat(store.findNodeById("com.acme.Real")).isPresent();
            assertThat(store.findNodeById("com.acme.Real#one()")).isPresent();
            assertThat(store.findNodeById("com.acme.Real#copy()")).isEmpty();
        }
    }

    @Test
    void aDirectoryNamedWorktreesElsewhereIsStillIndexed() throws IOException {
        Path root = fixture();
        write(root, "src/main/java/com/acme/worktrees/Planner.java",
            "package com.acme.worktrees;\npublic class Planner { public int plan() { return 3; } }\n");
        try (var store = new GraphStore(tempDir.resolve("b.db"))) {
            new ProjectIndexer(store).indexFull(root);

            assertThat(store.findNodeById("com.acme.worktrees.Planner")).isPresent();
        }
    }

    @Test
    void rowsAnOlderJarStoredForAWorktreeArePrunedByInitAndBySync() throws IOException {
        Path root = fixture();
        String stale = WORKTREE + "src/main/java/com/acme/OnlyInWorktree.java";
        try (var store = new GraphStore(tempDir.resolve("c.db"))) {
            var indexer = new ProjectIndexer(store);
            indexer.indexFull(root);

            store.saveIndexedFile(stale, 1L, "x");
            indexer.indexIncremental(root);
            assertThat(store.getAllIndexedFiles()).doesNotContain(stale);

            store.saveIndexedFile(stale, 1L, "x");
            indexer.indexFull(root);
            assertThat(store.getAllIndexedFiles()).doesNotContain(stale);
            assertThat(store.findNodeById("com.acme.Real")).isPresent();
        }
    }

    @Test
    void aNodeLeftByAKilledRunWithNoTrackingRowIsPrunedByInit() throws IOException {
        Path root = fixture();
        String stale = WORKTREE + "src/main/java/com/acme/OnlyInWorktree.java";
        try (var store = new GraphStore(tempDir.resolve("d.db"))) {
            var indexer = new ProjectIndexer(store);
            indexer.indexFull(root);
            Node real = store.findNodeById("com.acme.Real").orElseThrow();
            store.saveNode(new Node("com.acme.OnlyInWorktree", real.type(), "OnlyInWorktree",
                "com.acme.OnlyInWorktree", stale, 2, "public class OnlyInWorktree {", 1L));
            store.saveNode(new Node("com.acme.Absolute", real.type(), "Absolute",
                "com.acme.Absolute", root.resolve(stale).toString(), 2, "public class Absolute {", 1L));

            indexer.indexFull(root);

            assertThat(store.findNodeById("com.acme.OnlyInWorktree")).isEmpty();
            assertThat(store.findNodeById("com.acme.Absolute")).isEmpty();
            assertThat(store.findNodeById("com.acme.Real")).isPresent();
        }
    }
}
