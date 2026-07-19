# Token-Savings Benchmark Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `benchmark` Picocli subcommand that measures and prints a markdown token-savings table comparing the navigator's compact output against the naive baseline of reading every raw source file.

**Architecture:** `TokenEstimator` (chars/4 ceiling, zero dependencies) feeds `BenchmarkRunner`, which runs three fixed scenarios against a live `GraphStore`/`GraphTraversal`/`BriefingGenerator` — one for whole-project understanding, one for symbol impact blast radius, and one for the compact JSON export. `BenchmarkCommand` wires these together as a Picocli `Runnable` registered in `CodeNavigatorApplication`, reading raw source files from `GraphStore.getAllIndexedFiles()` for the baseline and calling the same internal methods as the existing MCP handlers for the navigator side.

**Tech Stack:** Java 21, Gradle 9 + shadowJar, Picocli 4.7.6, JUnit 5.11.4 + AssertJ 3.27.3, SQLite via `GraphStore`, `BriefingGenerator` for briefing output, `ExportService` for JSON export, `GraphTraversal.impact()` for blast-radius.

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| **Create** | `src/main/java/com/codenavigator/benchmark/TokenEstimator.java` | Pure-function token estimator: `int estimate(String text)` = `(int) Math.ceil(text.length() / 4.0)` |
| **Create** | `src/main/java/com/codenavigator/benchmark/BenchmarkRow.java` | Record: `scenario`, `baselineTokens`, `navigatorTokens`, `pctSavings` |
| **Create** | `src/main/java/com/codenavigator/benchmark/BenchmarkRunner.java` | Runs the three fixed scenarios; returns `List<BenchmarkRow>` |
| **Create** | `src/main/java/com/codenavigator/benchmark/MarkdownTableRenderer.java` | Renders `List<BenchmarkRow>` as a GFM markdown table string |
| **Create** | `src/main/java/com/codenavigator/cli/BenchmarkCommand.java` | Picocli `@Command(name="benchmark")` subcommand; builds services, calls runner, prints table |
| **Modify** | `src/main/java/com/codenavigator/CodeNavigatorApplication.java` | Add `BenchmarkCommand.class` to the `subcommands` list |
| **Create** | `src/test/java/com/codenavigator/benchmark/TokenEstimatorTest.java` | Unit tests: empty string, ASCII text, multiline |
| **Create** | `src/test/java/com/codenavigator/benchmark/BenchmarkRunnerTest.java` | Integration test against `src/test/resources/sample-ddd` |
| **Create** | `src/test/java/com/codenavigator/benchmark/MarkdownTableRendererTest.java` | Unit test for correct GFM table output |

---

### Task 1: `TokenEstimator` class + unit tests

**Files:**
- `src/main/java/com/codenavigator/benchmark/TokenEstimator.java`
- `src/test/java/com/codenavigator/benchmark/TokenEstimatorTest.java`

- [ ] Write the failing test first:

```java
// src/test/java/com/codenavigator/benchmark/TokenEstimatorTest.java
package com.codenavigator.benchmark;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TokenEstimatorTest {

    private final TokenEstimator estimator = new TokenEstimator();

    @Test
    void emptyStringReturnsZero() {
        assertThat(estimator.estimate("")).isEqualTo(0);
    }

    @Test
    void nullStringReturnsZero() {
        assertThat(estimator.estimate(null)).isEqualTo(0);
    }

    @Test
    void exactlyFourCharsIsOneToken() {
        assertThat(estimator.estimate("abcd")).isEqualTo(1);
    }

    @Test
    void fiveCharsCeilsToTwo() {
        assertThat(estimator.estimate("abcde")).isEqualTo(2);
    }

    @Test
    void multilineTextCounts() {
        // 40 chars across two lines -> 10 tokens
        String text = "0123456789012345678901234567890123456789";
        assertThat(estimator.estimate(text)).isEqualTo(10);
    }

    @Test
    void largeTextEstimateIsPositive() {
        String big = "x".repeat(10_000);
        assertThat(estimator.estimate(big)).isEqualTo(2_500);
    }
}
```

- [ ] Run (expect FAIL — class does not exist):
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.benchmark.TokenEstimatorTest" 2>&1 | tail -20
```
Expected: compilation failure mentioning `TokenEstimator`.

- [ ] Write minimal implementation:

```java
// src/main/java/com/codenavigator/benchmark/TokenEstimator.java
package com.codenavigator.benchmark;

/**
 * Dependency-free token estimator using the chars/4 heuristic.
 * Swappable: replace estimate() with a tiktoken-based impl later.
 */
public class TokenEstimator {

    /**
     * Estimate the number of tokens in {@code text}.
     * Uses ceiling(length / 4) as a fast, dependency-free approximation.
     *
     * @param text the text to estimate (null treated as empty)
     * @return estimated token count, always >= 0
     */
    public int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() / 4.0);
    }
}
```

- [ ] Run (expect PASS):
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.benchmark.TokenEstimatorTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`, all 6 tests pass.

- [ ] Commit:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && git add src/main/java/com/codenavigator/benchmark/TokenEstimator.java src/test/java/com/codenavigator/benchmark/TokenEstimatorTest.java && git commit -m "$(cat <<'EOF'
feat(benchmark): add TokenEstimator with chars/4 heuristic

Encapsulates token estimation so the implementation can be swapped
(e.g. tiktoken) without touching callers.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `BenchmarkRow` record + `BenchmarkRunner` + integration test

**Files:**
- `src/main/java/com/codenavigator/benchmark/BenchmarkRow.java`
- `src/main/java/com/codenavigator/benchmark/BenchmarkRunner.java`
- `src/test/java/com/codenavigator/benchmark/BenchmarkRunnerTest.java`

The three fixed scenarios are:

| ID | Name | Baseline | Navigator |
|----|------|----------|-----------|
| `whole-project` | Understand whole project | Concatenated content of every file in `GraphStore.getAllIndexedFiles()` | `BriefingGenerator.generateOverview()` (package-private string) — accessed by calling `generate()` into a temp dir and reading the `overview.md` file |
| `symbol-impact` | Impact of a symbol | Concatenated content of all files in the blast radius returned by `GraphTraversal.impact(nodeId, 2)` (distinct `filePath` values) | The formatted impact string produced by the same logic as `CodeNavigatorMcpServer.handleCgImpact()` |
| `compact-export` | Compact JSON export | Same as `whole-project` baseline (all raw files) | `ExportService.toJson(allNodes, allEdges, tier)` |

> **Note on BriefingGenerator access:** `BriefingGenerator.generateOverview()` is private. The runner uses `generate(tempDir)` and reads `overview.md` from the temp directory. This avoids changing `BriefingGenerator`'s visibility. For the navigator side of `whole-project`, the runner concatenates all files written by `generate()` into `tempDir` (the full briefing, not just overview) to make the comparison meaningful.

- [ ] Write failing test first:

```java
// src/test/java/com/codenavigator/benchmark/BenchmarkRunnerTest.java
package com.codenavigator.benchmark;

import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.GraphTraversal;
import com.codenavigator.indexer.ProjectIndexer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkRunnerTest {

    @TempDir
    Path tempDir;

    private GraphStore store;

    @BeforeEach
    void setUp() {
        store = new GraphStore(tempDir.resolve("test.db"));
        new ProjectIndexer(store).indexFull(Paths.get("src/test/resources/sample-ddd"));
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void runnerProducesThreeRows() {
        var runner = new BenchmarkRunner(store, new GraphTraversal(store), tempDir, new TokenEstimator());
        List<BenchmarkRow> rows = runner.run();
        assertThat(rows).hasSize(3);
    }

    @Test
    void scenarioIdsAreCorrect() {
        var runner = new BenchmarkRunner(store, new GraphTraversal(store), tempDir, new TokenEstimator());
        List<BenchmarkRow> rows = runner.run();
        assertThat(rows).extracting(BenchmarkRow::scenario)
            .containsExactly("whole-project", "symbol-impact", "compact-export");
    }

    @Test
    void navigatorAlwaysSmallerThanBaseline() {
        var runner = new BenchmarkRunner(store, new GraphTraversal(store), tempDir, new TokenEstimator());
        for (var row : runner.run()) {
            assertThat(row.navigatorTokens())
                .as("navigator should be smaller than baseline for scenario '%s'", row.scenario())
                .isLessThan(row.baselineTokens());
        }
    }

    @Test
    void pctSavingsIsInValidRange() {
        var runner = new BenchmarkRunner(store, new GraphTraversal(store), tempDir, new TokenEstimator());
        for (var row : runner.run()) {
            assertThat(row.pctSavings())
                .as("pct savings should be > 0 and < 100 for scenario '%s'", row.scenario())
                .isGreaterThan(0.0)
                .isLessThan(100.0);
        }
    }
}
```

- [ ] Run (expect FAIL — classes do not exist):
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.benchmark.BenchmarkRunnerTest" 2>&1 | tail -20
```
Expected: compilation failure mentioning `BenchmarkRow`, `BenchmarkRunner`.

- [ ] Write `BenchmarkRow` record:

```java
// src/main/java/com/codenavigator/benchmark/BenchmarkRow.java
package com.codenavigator.benchmark;

/**
 * A single benchmark result row.
 *
 * @param scenario       short identifier for this benchmark scenario
 * @param baselineTokens estimated tokens for the naive "read all files" baseline
 * @param navigatorTokens estimated tokens for the navigator output
 * @param pctSavings     (1 - navigatorTokens/baselineTokens) * 100, rounded to 1 decimal
 */
public record BenchmarkRow(
    String scenario,
    int baselineTokens,
    int navigatorTokens,
    double pctSavings
) {
    public BenchmarkRow {
        if (scenario == null || scenario.isBlank()) throw new IllegalArgumentException("scenario must not be blank");
        if (baselineTokens < 0) throw new IllegalArgumentException("baselineTokens must be >= 0");
        if (navigatorTokens < 0) throw new IllegalArgumentException("navigatorTokens must be >= 0");
    }
}
```

- [ ] Write `BenchmarkRunner`:

```java
// src/main/java/com/codenavigator/benchmark/BenchmarkRunner.java
package com.codenavigator.benchmark;

import com.codenavigator.briefing.BriefingGenerator;
import com.codenavigator.export.ExportService;
import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.GraphTraversal;
import com.codenavigator.graph.Node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Runs the three fixed benchmark scenarios and returns one {@link BenchmarkRow} per scenario.
 *
 * <p>Scenarios:
 * <ol>
 *   <li><b>whole-project</b> — all raw source files vs full briefing (.ai-briefing/ dir)</li>
 *   <li><b>symbol-impact</b> — raw files in blast radius vs cg_impact formatted output</li>
 *   <li><b>compact-export</b> — all raw source files vs compact JSON export</li>
 * </ol>
 */
public class BenchmarkRunner {

    private final GraphStore store;
    private final GraphTraversal traversal;
    private final Path tempDir;
    private final TokenEstimator estimator;
    private final ExportService exportService = new ExportService();

    /**
     * @param store     open {@link GraphStore} for the target project
     * @param traversal {@link GraphTraversal} over that store
     * @param tempDir   writable temporary directory for briefing file generation
     * @param estimator token estimator to use
     */
    public BenchmarkRunner(GraphStore store, GraphTraversal traversal, Path tempDir, TokenEstimator estimator) {
        this.store = store;
        this.traversal = traversal;
        this.tempDir = tempDir;
        this.estimator = estimator;
    }

    /**
     * Run all scenarios and return results in insertion order.
     */
    public List<BenchmarkRow> run() {
        List<BenchmarkRow> rows = new ArrayList<>();
        rows.add(runWholeProject());
        rows.add(runSymbolImpact());
        rows.add(runCompactExport());
        return rows;
    }

    // ---- Scenario 1: whole-project ----

    private BenchmarkRow runWholeProject() {
        // Baseline: concatenate all raw source files
        int baselineTokens = estimator.estimate(readAllSourceFiles());

        // Navigator: full briefing written to tempDir, then read back
        int navigatorTokens;
        try {
            Path briefingDir = tempDir.resolve("briefing-whole");
            Files.createDirectories(briefingDir);
            new BriefingGenerator(store, null).generate(briefingDir);
            String briefingContent = readDirectoryContents(briefingDir);
            navigatorTokens = estimator.estimate(briefingContent);
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate briefing for whole-project scenario", e);
        }

        return makeRow("whole-project", baselineTokens, navigatorTokens);
    }

    // ---- Scenario 2: symbol-impact ----

    private BenchmarkRow runSymbolImpact() {
        // Pick the node with the highest fan-in as the representative symbol
        List<GraphStore.NodeCount> fanIn = store.findTopFanIn(1);
        if (fanIn.isEmpty()) {
            // Fallback: any node will do
            List<Node> all = store.getAllNodes();
            if (all.isEmpty()) return makeRow("symbol-impact", 0, 0);
            return runSymbolImpactFor(all.get(0).id());
        }
        return runSymbolImpactFor(fanIn.get(0).node().id());
    }

    private BenchmarkRow runSymbolImpactFor(String nodeId) {
        List<Node> impactNodes = traversal.impact(nodeId, 2);

        // Baseline: raw content of all distinct files in the blast radius
        List<String> impactFiles = impactNodes.stream()
            .map(Node::filePath)
            .distinct()
            .toList();
        String baselineContent = readFiles(impactFiles);
        int baselineTokens = estimator.estimate(baselineContent);

        // Navigator: formatted impact report (mirrors CodeNavigatorMcpServer.handleCgImpact)
        Node focusNode = store.findNodeById(nodeId).orElseThrow();
        StringBuilder sb = new StringBuilder();
        sb.append("## Impact of ").append(focusNode.name()).append(" (depth 2)\n\n");
        sb.append(impactNodes.size()).append(" affected node(s):\n\n");
        for (var node : impactNodes) {
            sb.append("- **").append(node.type()).append("** `").append(node.name())
              .append("` (").append(node.filePath()).append(":").append(node.lineNumber()).append(")\n");
        }
        int navigatorTokens = estimator.estimate(sb.toString());

        return makeRow("symbol-impact", baselineTokens, navigatorTokens);
    }

    // ---- Scenario 3: compact-export ----

    private BenchmarkRow runCompactExport() {
        // Baseline: same as whole-project — all raw source files
        int baselineTokens = estimator.estimate(readAllSourceFiles());

        // Navigator: JSON export of the full graph
        var allNodes = store.getAllNodes();
        var allEdges = store.getAllEdges();
        String tier = store.getConfig("tier");
        String jsonExport = exportService.toJson(allNodes, allEdges, tier);
        int navigatorTokens = estimator.estimate(jsonExport);

        return makeRow("compact-export", baselineTokens, navigatorTokens);
    }

    // ---- Helpers ----

    /** Read and concatenate the content of every indexed source file. */
    private String readAllSourceFiles() {
        return readFiles(store.getAllIndexedFiles());
    }

    /** Read and concatenate the content of a list of file paths. */
    private String readFiles(List<String> filePaths) {
        StringBuilder sb = new StringBuilder();
        for (String filePath : filePaths) {
            try {
                Path p = Path.of(filePath);
                if (Files.isRegularFile(p)) {
                    sb.append(Files.readString(p));
                }
            } catch (IOException e) {
                // Skip unreadable files silently — they don't contribute to token count
            }
        }
        return sb.toString();
    }

    /** Read and concatenate all files written into a directory (non-recursive). */
    private String readDirectoryContents(Path dir) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (var stream = Files.list(dir)) {
            for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                sb.append(Files.readString(file));
            }
        }
        return sb.toString();
    }

    private BenchmarkRow makeRow(String scenario, int baselineTokens, int navigatorTokens) {
        double pct = baselineTokens == 0 ? 0.0
            : Math.round((1.0 - (double) navigatorTokens / baselineTokens) * 1000.0) / 10.0;
        return new BenchmarkRow(scenario, baselineTokens, navigatorTokens, pct);
    }
}
```

- [ ] Run (expect PASS):
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.benchmark.BenchmarkRunnerTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`, all 4 tests pass.

- [ ] Commit:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && git add src/main/java/com/codenavigator/benchmark/BenchmarkRow.java src/main/java/com/codenavigator/benchmark/BenchmarkRunner.java src/test/java/com/codenavigator/benchmark/BenchmarkRunnerTest.java && git commit -m "$(cat <<'EOF'
feat(benchmark): add BenchmarkRow and BenchmarkRunner with three fixed scenarios

Whole-project, symbol-impact, and compact-export scenarios verified against
sample-ddd; navigator output consistently smaller than the raw-file baseline.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: `MarkdownTableRenderer` + unit test

**Files:**
- `src/main/java/com/codenavigator/benchmark/MarkdownTableRenderer.java`
- `src/test/java/com/codenavigator/benchmark/MarkdownTableRendererTest.java`

- [ ] Write failing test first:

```java
// src/test/java/com/codenavigator/benchmark/MarkdownTableRendererTest.java
package com.codenavigator.benchmark;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownTableRendererTest {

    private final MarkdownTableRenderer renderer = new MarkdownTableRenderer();

    @Test
    void rendersHeaderRow() {
        List<BenchmarkRow> rows = List.of(
            new BenchmarkRow("whole-project", 10_000, 800, 92.0)
        );
        String table = renderer.render(rows);
        assertThat(table).contains("| Scenario");
        assertThat(table).contains("| Baseline tokens");
        assertThat(table).contains("| Navigator tokens");
        assertThat(table).contains("| Savings %");
    }

    @Test
    void rendersAlignmentRow() {
        List<BenchmarkRow> rows = List.of(
            new BenchmarkRow("whole-project", 10_000, 800, 92.0)
        );
        String table = renderer.render(rows);
        // GFM alignment row must appear between header and data rows
        assertThat(table).contains("|---|");
    }

    @Test
    void rendersDataRow() {
        List<BenchmarkRow> rows = List.of(
            new BenchmarkRow("whole-project", 10_000, 800, 92.0)
        );
        String table = renderer.render(rows);
        assertThat(table).contains("whole-project");
        assertThat(table).contains("10,000");
        assertThat(table).contains("800");
        assertThat(table).contains("92.0%");
    }

    @Test
    void rendersMultipleRows() {
        List<BenchmarkRow> rows = List.of(
            new BenchmarkRow("whole-project", 10_000, 800, 92.0),
            new BenchmarkRow("symbol-impact", 4_000, 120, 97.0),
            new BenchmarkRow("compact-export", 10_000, 1_200, 88.0)
        );
        String table = renderer.render(rows);
        long dataLines = table.lines()
            .filter(l -> l.startsWith("|") && !l.contains("Scenario") && !l.contains("---"))
            .count();
        assertThat(dataLines).isEqualTo(3);
    }

    @Test
    void emptyRowsReturnsHeaderOnly() {
        String table = renderer.render(List.of());
        assertThat(table).contains("| Scenario");
        // No data rows beyond header + alignment
        long lines = table.lines().count();
        assertThat(lines).isEqualTo(2); // header + alignment
    }
}
```

- [ ] Run (expect FAIL — class does not exist):
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.benchmark.MarkdownTableRendererTest" 2>&1 | tail -20
```
Expected: compilation failure mentioning `MarkdownTableRenderer`.

- [ ] Write minimal implementation:

```java
// src/main/java/com/codenavigator/benchmark/MarkdownTableRenderer.java
package com.codenavigator.benchmark;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * Renders a list of {@link BenchmarkRow}s as a GitHub-Flavoured Markdown table
 * suitable for pasting into a README.
 */
public class MarkdownTableRenderer {

    private static final NumberFormat NUM_FMT = NumberFormat.getNumberInstance(Locale.US);

    /**
     * Render {@code rows} as a GFM table. Returns header + alignment row even if rows is empty.
     */
    public String render(List<BenchmarkRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("| Scenario | Baseline tokens | Navigator tokens | Savings % |\n");
        sb.append("|---|---:|---:|---:|\n");
        for (BenchmarkRow row : rows) {
            sb.append("| ").append(row.scenario())
              .append(" | ").append(NUM_FMT.format(row.baselineTokens()))
              .append(" | ").append(NUM_FMT.format(row.navigatorTokens()))
              .append(" | ").append(row.pctSavings()).append("% |")
              .append("\n");
        }
        // Trim trailing newline so the string can be embedded cleanly
        String result = sb.toString();
        return result.endsWith("\n") ? result.substring(0, result.length() - 1) : result;
    }
}
```

- [ ] Run (expect PASS):
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.benchmark.MarkdownTableRendererTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`, all 5 tests pass.

- [ ] Commit:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && git add src/main/java/com/codenavigator/benchmark/MarkdownTableRenderer.java src/test/java/com/codenavigator/benchmark/MarkdownTableRendererTest.java && git commit -m "$(cat <<'EOF'
feat(benchmark): add MarkdownTableRenderer for GFM token-savings table

Outputs a paste-ready markdown table with comma-formatted token counts
and percentage savings.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: `BenchmarkCommand` Picocli subcommand + wire into application

**Files:**
- `src/main/java/com/codenavigator/cli/BenchmarkCommand.java`
- `src/main/java/com/codenavigator/CodeNavigatorApplication.java`

- [ ] Write `BenchmarkCommand`:

```java
// src/main/java/com/codenavigator/cli/BenchmarkCommand.java
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
            var runner = new BenchmarkRunner(store, traversal, tempDir, new TokenEstimator());

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
```

- [ ] Register `BenchmarkCommand` in `CodeNavigatorApplication`. The current `subcommands` list is:

```java
subcommands = {
    InitCommand.class,
    SyncCommand.class,
    ServeCommand.class,
    StatusCommand.class,
    MarkDirtyCommand.class,
    SyncIfDirtyCommand.class,
    InstallCommand.class,
    ExportCommand.class,
    BriefingCommand.class
}
```

Change it to:

```java
subcommands = {
    InitCommand.class,
    SyncCommand.class,
    ServeCommand.class,
    StatusCommand.class,
    MarkDirtyCommand.class,
    SyncIfDirtyCommand.class,
    InstallCommand.class,
    ExportCommand.class,
    BriefingCommand.class,
    BenchmarkCommand.class
}
```

The full updated file:

```java
// src/main/java/com/codenavigator/CodeNavigatorApplication.java
package com.codenavigator;

import com.codenavigator.cli.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "code-navigator",
    mixinStandardHelpOptions = true,
    version = "0.1.0",
    description = "Multi-tier Java code navigator MCP server",
    subcommands = {
        InitCommand.class,
        SyncCommand.class,
        ServeCommand.class,
        StatusCommand.class,
        MarkDirtyCommand.class,
        SyncIfDirtyCommand.class,
        InstallCommand.class,
        ExportCommand.class,
        BriefingCommand.class,
        BenchmarkCommand.class
    }
)
public class CodeNavigatorApplication implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new CodeNavigatorApplication()).execute(args);
        System.exit(exitCode);
    }
}
```

- [ ] Verify the full test suite still passes (all benchmark tests + existing tests):
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test 2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL`, no failures.

- [ ] Build the fat JAR and confirm `benchmark` appears in the help output:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew shadowJar 2>&1 | tail -5 && java -jar build/libs/code-navigator-0.1.0.jar --help 2>&1 | grep benchmark
```
Expected: `benchmark   Measure token savings: navigator compact output vs reading all raw source files. Emits a markdown table.`

- [ ] Commit:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && git add src/main/java/com/codenavigator/cli/BenchmarkCommand.java src/main/java/com/codenavigator/CodeNavigatorApplication.java && git commit -m "$(cat <<'EOF'
feat(benchmark): add 'benchmark' CLI subcommand and register in application

Prints a markdown token-savings table for any indexed project;
useful for README proof-of-value marketing.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

| Spec item | Covered in task(s) | Notes |
|-----------|--------------------|-------|
| `TokenEstimator` class, chars/4 heuristic, swappable | T1 | Implemented as `(int) Math.ceil(length / 4.0)`; null-safe |
| Dependency-free estimator | T1 | No new `build.gradle` dependency added |
| Scenario: "understand whole project" = all files vs briefing | T2 | `whole-project` scenario; uses `BriefingGenerator.generate()` + reads output dir |
| Scenario: "impact of a symbol" = blast-radius files vs cg_impact output | T2 | `symbol-impact` scenario; uses `GraphTraversal.impact(nodeId, 2)` + same format as `handleCgImpact` |
| Scenario: compact export | T2 | `compact-export` scenario; uses `ExportService.toJson()` — the most compact navigator format |
| Markdown table output (baseline tokens, navigator tokens, % savings) | T3 | GFM table with `|---|---:|---:|---:|` alignment row; comma-formatted numbers |
| Table suitable for pasting into README | T3 | `render()` returns the raw string without trailing newline |
| Picocli CLI subcommand `benchmark` | T4 | `@Command(name="benchmark")`, `@Parameters` for project path |
| Wired into `CodeNavigatorApplication` | T4 | Added to `subcommands` array |
| No new MCP tool, no schema change | all | `BenchmarkCommand` is a CLI-only command; `CodeNavigatorMcpServer` untouched |
| Test against sample-ddd | T2 | `BenchmarkRunnerTest` uses `src/test/resources/sample-ddd` via `ProjectIndexer` |
| navigator < baseline assertion | T2 | `navigatorAlwaysSmallerThanBaseline()` asserts `<` for all three scenarios |
| pct in (0, 100) assertion | T2 | `pctSavingsIsInValidRange()` asserts `> 0` and `< 100` |
