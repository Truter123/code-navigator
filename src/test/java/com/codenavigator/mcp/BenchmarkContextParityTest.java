package com.codenavigator.mcp;

import com.codenavigator.benchmark.BenchmarkRunner;
import com.codenavigator.benchmark.TokenEstimator;
import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.GraphTraversal;
import com.codenavigator.indexer.ProjectIndexer;
import com.codenavigator.search.SearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkContextParityTest {

    @TempDir
    Path tempDir;

    @Test
    void semanticContextNavigatorTextIsTheCgContextReply() {
        var root = Paths.get("src/test/resources/sample-ddd");
        var store = new GraphStore(tempDir.resolve("test.db"));
        try {
            new ProjectIndexer(store).indexFull(root);
            var traversal = new GraphTraversal(store);
            var search = new SearchService(store, traversal);
            var server = new CodeNavigatorMcpServer(store, traversal, search);
            var runner = new BenchmarkRunner(store, traversal, tempDir, new TokenEstimator(), root);

            String query = "modify order creation";
            runner.runSemanticContext(search, query);

            assertThat(runner.lastSemanticNavigatorText())
                .isEqualTo(server.handleCgContext(Map.of("task", query)));
        } finally {
            store.close();
        }
    }

    @Test
    void symbolImpactNavigatorTextIsTheCgGuardReplyAndTheBaselineIsItsDependentsFiles() {
        var root = Paths.get("src/test/resources/sample-ddd");
        var store = new GraphStore(tempDir.resolve("guard.db"));
        try {
            new ProjectIndexer(store).indexFull(root);
            var traversal = new GraphTraversal(store);
            var search = new SearchService(store, traversal);
            var server = new CodeNavigatorMcpServer(store, traversal, search);
            var runner = new BenchmarkRunner(store, traversal, tempDir, new TokenEstimator(), root);

            var row = runner.run().stream().filter(r -> r.scenario().equals("symbol-impact")).findFirst().orElseThrow();
            String symbol = runner.lastSymbolImpactNodeId();

            var node = store.findNodeById(symbol).orElseThrow();
            assertThat(node.type()).isNotEqualTo(com.codenavigator.graph.NodeType.LIBRARY);
            assertThat(runner.lastSymbolImpactText())
                .startsWith("## Guard: ")
                .isEqualTo(server.reply("cg_guard", Map.of("symbol", symbol)));

            var guard = new GuardReportBuilder(store, traversal);
            var files = new java.util.LinkedHashSet<String>();
            files.add(node.filePath());
            guard.dependents(symbol, 2).forEach(n -> { if (n.filePath() != null) files.add(n.filePath()); });
            assertThat(guard.dependents(symbol, 2)).isNotEmpty();
            assertThat(runner.lastSymbolImpactNote()).isEqualTo("symbol-impact: cg_guard on `"
                + com.codenavigator.graph.MethodIds.shortLabel(symbol) + "`, " + guard.dependents(symbol, 2).size()
                + " dependent node(s); baseline reads " + files.size() + " file(s) whole.");
            assertThat(row.navigatorTokens()).isEqualTo(new TokenEstimator().estimate(runner.lastSymbolImpactText()));
            assertThat(row.baselineTokens()).isPositive();
        } finally {
            store.close();
        }
    }
}
