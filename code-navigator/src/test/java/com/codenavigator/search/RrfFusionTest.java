package com.codenavigator.search;

import org.junit.jupiter.api.Test;
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
}
