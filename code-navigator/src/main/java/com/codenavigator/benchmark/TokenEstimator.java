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
