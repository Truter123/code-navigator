package com.codenavigator.cli;

import com.codenavigator.benchmark.BenchmarkRow;
import com.codenavigator.benchmark.BenchmarkRunner;
import com.codenavigator.benchmark.MarkdownTableRenderer;
import com.codenavigator.benchmark.TokenEstimator;
import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.GraphTraversal;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Benchmark subcommand: computes and prints a token-savings table comparing the
 * navigator's compact output against reading all raw source files.
 *
 * <p>Usage:
 * <pre>
 *   code-navigator benchmark /path/to/indexed-project
 * </pre>
 *
 * <p>Example output (paste into README):
 * <pre>
 * | Scenario       | Baseline tokens | Navigator tokens | Savings % |
 * |---|---:|---:|---:|
 * | whole-project  |          12,340 |              820 |     93.4% |
 * | symbol-impact  |           3,100 |              110 |     96.5% |
 * | compact-export |          12,340 |            1,450 |     88.2% |
 * </pre>
 */
@Command(
    name = "benchmark",
    description = "Measure token savings: navigator compact output vs reading all raw source files. Emits a markdown table."
)
public class BenchmarkCommand implements Runnable {

    @Parameters(index = "0", description = "Path to the project root (must already be indexed)")
    private Path projectPath;

    @Override
    public void run() {
        if (!ProjectPaths.hasIndex(projectPath)) {
            System.err.println("No index found at " + ProjectPaths.graphDb(projectPath) + ". Run 'init' first.");
            return;
        }

        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("code-navigator-benchmark-");
        } catch (IOException e) {
            System.err.println("Failed to create temp directory: " + e.getMessage());
            return;
        }

        try (var store = new GraphStore(ProjectPaths.graphDb(projectPath))) {
            var traversal = new GraphTraversal(store);
            var runner = new BenchmarkRunner(store, traversal, tempDir, new TokenEstimator(), projectPath);

            List<BenchmarkRow> rows = runner.run();

            String table = new MarkdownTableRenderer().render(rows);
            System.out.println(table);
        } catch (Exception e) {
            System.err.println("Benchmark failed: " + e.getMessage());
        } finally {
            deleteTempDir(tempDir);
        }
    }

    private static void deleteTempDir(Path dir) {
        try {
            if (dir != null && Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted(java.util.Comparator.reverseOrder())
                          .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
                }
            }
        } catch (IOException ignored) {}
    }
}
