package com.codenavigator.embedding;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingProviderTest {

    @Test
    void noop_returnsEmptyArray() {
        EmbeddingProvider provider = new NoopEmbeddingProvider();
        assertThat(provider.embed("anything")).isEmpty();
    }

    @Test
    void noop_isConsistent() {
        EmbeddingProvider provider = new NoopEmbeddingProvider();
        assertThat(provider.embed("foo")).isEmpty();
        assertThat(provider.embed("bar")).isEmpty();
    }
}
