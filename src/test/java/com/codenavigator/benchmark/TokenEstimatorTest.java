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
