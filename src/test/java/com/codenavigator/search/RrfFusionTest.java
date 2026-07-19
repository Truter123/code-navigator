package com.codenavigator.search;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RrfFusionTest {

    @Test
    void cosine_orthogonalVectors_returnsZero() {
        assertThat(SearchService.cosine(new float[]{1.0f, 0.0f}, new float[]{0.0f, 1.0f}))
            .isCloseTo(0.0f, within(1e-6f));
    }

    @Test
    void cosine_identicalVectors_returnsOne() {
        assertThat(SearchService.cosine(new float[]{3.0f, 4.0f}, new float[]{3.0f, 4.0f}))
            .isCloseTo(1.0f, within(1e-6f));
    }

    @Test
    void cosine_oppositeVectors_returnsNegativeOne() {
        assertThat(SearchService.cosine(new float[]{1.0f, 0.0f}, new float[]{-1.0f, 0.0f}))
            .isCloseTo(-1.0f, within(1e-6f));
    }

    @Test
    void cosine_emptyOrMismatchedDim_returnsZero() {
        assertThat(SearchService.cosine(new float[0], new float[0])).isEqualTo(0.0f);
        assertThat(SearchService.cosine(new float[]{1.0f}, new float[]{1.0f, 2.0f})).isEqualTo(0.0f);
    }

    @Test
    void rrf_singleList_returnsInOrder() {
        assertThat(SearchService.rrf(60, java.util.List.of("a", "b", "c")))
            .containsExactly("a", "b", "c");
    }

    @Test
    void rrf_twoLists_itemInBothRanksHigher() {
        var fts = java.util.List.of("shared", "only-fts-1", "only-fts-2");
        var vec = java.util.List.of("shared", "only-vec-1", "only-vec-2");
        assertThat(SearchService.rrf(60, fts, vec).get(0)).isEqualTo("shared");
    }

    @Test
    void rrf_emptyList_ignoredGracefully() {
        assertThat(SearchService.rrf(60, java.util.List.of(), java.util.List.of("x", "y")))
            .containsExactly("x", "y");
    }

    @Test
    void rrf_deduplicatesAcrossLists() {
        var a = java.util.List.of("x", "y");
        var b = java.util.List.of("y", "z");
        var result = SearchService.rrf(60, a, b);
        assertThat(result).doesNotHaveDuplicates().containsExactlyInAnyOrder("x", "y", "z");
    }
}
