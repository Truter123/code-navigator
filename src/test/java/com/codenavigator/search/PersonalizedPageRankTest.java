package com.codenavigator.search;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PersonalizedPageRankTest {

    @Test
    void massOnOneSeedInAChainDecaysWithDistance() {
        // Seed in the middle of a 5-chain. A seed at the END of a chain is outranked by its
        // neighbour (degree 2 collects from both sides): undirected PageRank is degree-biased.
        var r = PersonalizedPageRank.rank(5, new int[] {0, 1, 2, 3}, new int[] {1, 2, 3, 4}, new double[] {0, 0, 1, 0, 0});
        assertThat(r[2]).isGreaterThan(r[1]);
        assertThat(r[1]).isGreaterThan(r[0]);
        assertThat(r[0]).isGreaterThan(0);
    }

    @Test
    void aNodeBesideTwoSeedsOutranksOneBesideOne() {
        // 0 and 1 are seeds; 2 touches both, 3 touches only 0.
        var r = PersonalizedPageRank.rank(4, new int[] {2, 2, 3}, new int[] {0, 1, 0}, new double[] {1, 1, 0, 0});
        assertThat(r[2]).isGreaterThan(r[3]);
    }

    @Test
    void anUnreachableNodeScoresZero() {
        var r = PersonalizedPageRank.rank(3, new int[] {0}, new int[] {1}, new double[] {1, 0, 0});
        assertThat(r[2]).isZero();
    }

    @Test
    void parallelEdgesWeighSqrtOfTheirCount() {
        // Seed 0 has a single edge to 1 and four parallel edges to 2 (weight 2): 2 gets twice 1's share, not four times.
        var r = PersonalizedPageRank.rank(3, new int[] {0, 0, 0, 0, 0}, new int[] {1, 2, 2, 2, 2}, new double[] {1, 0, 0});
        // Both leaves are dangling-free here (each has an edge), so after one hop the ratio is the weight ratio.
        assertThat(r[2] / r[1]).isCloseTo(2.0, within(0.2));
        assertThat(r[2] / r[1]).isLessThan(3.0);
    }

    @Test
    void moreMassOnASeedRanksItHigher() {
        var r = PersonalizedPageRank.rank(2, new int[0], new int[0], new double[] {2, 1});
        assertThat(r[0]).isCloseTo(2.0 / 3, within(1e-9));
        assertThat(r[1]).isCloseTo(1.0 / 3, within(1e-9));
    }

    @Test
    void scoresSumToOne() {
        var r = PersonalizedPageRank.rank(5, new int[] {0, 1, 2, 3}, new int[] {1, 2, 3, 0}, new double[] {1, 0, 0, 0, 1});
        assertThat(Arrays.stream(r).sum()).isCloseTo(1.0, within(1e-9));
    }
}
