package com.codenavigator.benchmark;

/**
 * A single benchmark result row.
 *
 * @param scenario       short identifier for this benchmark scenario
 * @param baselineTokens estimated tokens for the naive "read all files" baseline
 * @param navigatorTokens estimated tokens for the navigator output
 * @param pctSavings     (1 - navigatorTokens/baselineTokens) * 100, rounded to 1 decimal
 * @param measured       false when the scenario has nothing to measure (the table prints n/a, not a percentage)
 */
public record BenchmarkRow(
    String scenario,
    int baselineTokens,
    int navigatorTokens,
    double pctSavings,
    boolean measured
) {
    public BenchmarkRow(String scenario, int baselineTokens, int navigatorTokens, double pctSavings) {
        this(scenario, baselineTokens, navigatorTokens, pctSavings, true);
    }

    public BenchmarkRow {
        if (scenario == null || scenario.isBlank()) throw new IllegalArgumentException("scenario must not be blank");
        if (baselineTokens < 0) throw new IllegalArgumentException("baselineTokens must be >= 0");
        if (navigatorTokens < 0) throw new IllegalArgumentException("navigatorTokens must be >= 0");
    }
}
