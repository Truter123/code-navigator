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
