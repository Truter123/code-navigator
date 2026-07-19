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
