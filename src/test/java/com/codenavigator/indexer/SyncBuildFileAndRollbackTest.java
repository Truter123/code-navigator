package com.codenavigator.indexer;

import com.codenavigator.graph.EdgeType;
import com.codenavigator.graph.GraphStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two sync defects: a build-file-only edit triggered no work (and after any dependency edit the
 * fast path was off until the next init), and a run stopped between "edges cleared" and "edges
 * rebuilt" left an index with no edges.
 */
class SyncBuildFileAndRollbackTest {

    @TempDir Path tempDir;

    private long clock = 2_000_000_000_000L;

    private void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        clock += 5_000;
        Files.setLastModifiedTime(file, FileTime.fromMillis(clock));
    }

    private long count(GraphStore store, EdgeType type) {
        return store.getAllEdges().stream().filter(e -> e.type() == type).count();
    }

    private Path fixture() throws IOException {
        Path root = tempDir.resolve("fx");
        write(root, "build.gradle", "dependencies {\n}\n");
        write(root, "src/main/java/com/acme/Tool.java", """
            package com.acme;
            import picocli.CommandLine;
            public class Tool {
                private CommandLine line;
                private final Worker worker = new Worker();
                public int run() { return worker.work(); }
            }
            """);
        write(root, "src/main/java/com/acme/Worker.java", """
            package com.acme;
            public class Worker {
                public int work() { return 1; }
            }
            """);
        // One changed file must stay under the 30% share that forces the full rebuild.
        for (String name : new String[] {"One", "Two", "Three"}) {
            write(root, "src/main/java/com/acme/" + name + ".java",
                "package com.acme;\npublic class " + name + " { public int value() { return 0; } }\n");
        }
        return root;
    }

    @Test
    void aBuildFileOnlyEditIsSyncedAndDoesNotSwitchTheFastPathOff() throws IOException {
        Path root = fixture();
        try (var store = new GraphStore(tempDir.resolve("a.db"))) {
            var indexer = new ProjectIndexer(store);
            indexer.indexFull(root);
            assertThat(store.getConfig("dependencies")).doesNotContain("picocli");
            assertThat(count(store, EdgeType.USES_LIBRARY)).isZero();

            write(root, "build.gradle", "dependencies {\n    implementation 'info.picocli:picocli:4.7.6'\n}\n");
            var buildOnly = indexer.indexIncremental(root);

            assertThat(buildOnly.fastPath()).isFalse();
            assertThat(buildOnly.fallbackReason()).isEqualTo("build file changed");
            assertThat(store.getConfig("dependencies")).contains("picocli");
            assertThat(count(store, EdgeType.USES_LIBRARY)).isPositive();

            var nothing = indexer.indexIncremental(root);
            assertThat(nothing.fallbackReason()).as("the stored dependency list was updated").isNull();

            write(root, "src/main/java/com/acme/Worker.java", """
                package com.acme;
                public class Worker {
                    public int work() { return 2; }
                }
                """);
            var bodyEdit = indexer.indexIncremental(root);
            assertThat(bodyEdit.fastPath()).as("fast path (reason: %s)", bodyEdit.fallbackReason()).isTrue();
        }
    }

    @Test
    void aRunStoppedAfterTheEdgesWereClearedLeavesTheOldGraph() throws IOException {
        Path root = fixture();
        try (var store = new GraphStore(tempDir.resolve("b.db"))) {
            var indexer = new ProjectIndexer(store);
            indexer.indexFull(root);
            int edges = store.getAllEdges().size();
            long calls = count(store, EdgeType.CALLS);
            int nodes = store.getAllNodes().size();
            assertThat(calls).isPositive();

            indexer.withAfterEdgesCleared(() -> { throw new IllegalStateException("killed"); });

            // A new method changes the file's shape, so the sync takes the full rebuild.
            write(root, "src/main/java/com/acme/Worker.java", """
                package com.acme;
                public class Worker {
                    public int work() { return 1; }
                    public int more() { return 2; }
                }
                """);
            assertThatThrownBy(() -> indexer.indexIncremental(root)).hasMessageContaining("killed");
            assertThat(store.getAllEdges()).hasSize(edges);
            assertThat(count(store, EdgeType.CALLS)).isEqualTo(calls);
            assertThat(store.getAllNodes()).hasSize(nodes);

            assertThatThrownBy(() -> indexer.indexFull(root)).hasMessageContaining("killed");
            assertThat(store.getAllEdges()).hasSize(edges);
            assertThat(store.getAllNodes()).hasSize(nodes);

            indexer.withAfterEdgesCleared(() -> { });
            var retry = indexer.indexIncremental(root);
            assertThat(retry.fallbackReason()).isNotNull();
            assertThat(store.findNodeById("com.acme.Worker#more()")).isPresent();
            assertThat(count(store, EdgeType.CALLS)).isEqualTo(calls);
        }
    }
}
