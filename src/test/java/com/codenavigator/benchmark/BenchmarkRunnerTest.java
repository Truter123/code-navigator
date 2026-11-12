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
    void runnerProducesARowPerScenario() {
        var runner = new BenchmarkRunner(store, new GraphTraversal(store), tempDir, new TokenEstimator(),
            Paths.get("src/test/resources/sample-ddd"));
        List<BenchmarkRow> rows = runner.run();
        assertThat(rows).hasSize(2);
    }

    @Test
    void scenarioIdsAreCorrect() {
        var runner = new BenchmarkRunner(store, new GraphTraversal(store), tempDir, new TokenEstimator(),
            Paths.get("src/test/resources/sample-ddd"));
        List<BenchmarkRow> rows = runner.run();
        assertThat(rows).extracting(BenchmarkRow::scenario)
            .containsExactly("symbol-impact", "compact-export");
    }

    @Test
    void impactSmallerThanBaseline() {
        // The cg_related report (symbol-impact) is always more compact than reading the raw files
        // it summarises. The full-graph JSON export (compact-export) only wins on large codebases —
        // on a tiny fixture its structural overhead can exceed the terse source, so it is checked
        // separately.
        var runner = new BenchmarkRunner(store, new GraphTraversal(store), tempDir, new TokenEstimator(),
            Paths.get("src/test/resources/sample-ddd"));
        var winning = runner.run().stream()
            .filter(r -> r.scenario().equals("symbol-impact"))
            .toList();
        assertThat(winning).hasSize(1);
        for (var row : winning) {
            assertThat(row.navigatorTokens())
                .as("navigator should be smaller than baseline for scenario '%s'", row.scenario())
                .isLessThan(row.baselineTokens());
            assertThat(row.pctSavings())
                .as("pct savings should be > 0 and < 100 for scenario '%s'", row.scenario())
                .isGreaterThan(0.0)
                .isLessThan(100.0);
        }
    }

    @Test
    void everyScenarioProducesValidMeasurement() {
        // Every scenario must yield a real, non-degenerate measurement: positive token
        // counts on both sides and a savings figure below 100% (it may be negative for
        // compact-export on a small fixture, which is itself a useful finding).
        var runner = new BenchmarkRunner(store, new GraphTraversal(store), tempDir, new TokenEstimator(),
            Paths.get("src/test/resources/sample-ddd"));
        for (var row : runner.run()) {
            assertThat(row.baselineTokens()).as("baseline for '%s'", row.scenario()).isPositive();
            assertThat(row.navigatorTokens()).as("navigator for '%s'", row.scenario()).isPositive();
            assertThat(row.pctSavings()).as("savings for '%s' must be < 100", row.scenario())
                .isLessThan(100.0);
        }
    }

    @Test
    void semanticContextBaselineCoversEveryFileTheReplyNamesAndSavesTokens() {
        var root = Paths.get("src/test/resources/sample-ddd");
        var traversal = new GraphTraversal(store);
        var search = new com.codenavigator.search.SearchService(store, traversal);
        var runner = new BenchmarkRunner(store, traversal, tempDir, new TokenEstimator(), root);

        var row = runner.runSemanticContext(search, "modify order creation");

        assertThat(row.scenario()).isEqualTo("semantic-context");
        var named = com.codenavigator.mcp.ContextReport.forTask(search, "modify order creation", 10).files();
        assertThat(named).isNotEmpty();
        assertThat(runner.lastSemanticBaselineFiles()).containsAll(named);
        assertThat(row.pctSavings()).isGreaterThan(0.0);
    }

    @Test
    void semanticContextWithSeedsPrintsAPercentageAndTheBaselineLine() {
        var root = Paths.get("src/test/resources/sample-ddd");
        var traversal = new GraphTraversal(store);
        var search = new com.codenavigator.search.SearchService(store, traversal);
        var runner = new BenchmarkRunner(store, traversal, tempDir, new TokenEstimator(), root);

        var row = runner.runSemanticContext(search, "modify order creation");

        assertThat(row.measured()).isTrue();
        assertThat(new MarkdownTableRenderer().render(List.of(row))).doesNotContain("n/a");
        assertThat(runner.lastSemanticNote()).matches(
            "semantic-context baseline: \\d+ file\\(s\\) from keyword hits, \\d+ more named by the reply\\.");
    }

    @Test
    void semanticContextWithoutSeedsPrintsNotApplicableAndTheNote() {
        var root = Paths.get("src/test/resources/sample-ddd");
        var traversal = new GraphTraversal(store);
        var search = new com.codenavigator.search.SearchService(store, traversal);
        var runner = new BenchmarkRunner(store, traversal, tempDir, new TokenEstimator(), root);

        var row = runner.runSemanticContext(search, "where does zzqxv happen");

        assertThat(row.measured()).isFalse();
        assertThat(new MarkdownTableRenderer().render(List.of(row))).contains("| n/a |").doesNotContain("0.0%");
        assertThat(runner.lastSemanticNote()).isEqualTo(
            "semantic-context: the query \"where does zzqxv happen\" matches nothing in this project; "
                + "pass --query \"<a task that fits it>\".");
    }
}
