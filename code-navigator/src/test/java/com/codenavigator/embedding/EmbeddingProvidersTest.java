// src/test/java/com/codenavigator/embedding/EmbeddingProvidersTest.java
package com.codenavigator.embedding;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingProvidersTest {

    @Test
    void flagOne_buildsOllama() {
        assertThat(EmbeddingProviders.fromFlag("1", "http://localhost:11434", "nomic-embed-text"))
            .isInstanceOf(OllamaEmbeddingProvider.class);
    }

    @Test
    void flagTrue_buildsOllama() {
        assertThat(EmbeddingProviders.fromFlag("true", "http://localhost:11434", "nomic-embed-text"))
            .isInstanceOf(OllamaEmbeddingProvider.class);
    }

    @Test
    void flagNull_buildsNoop() {
        assertThat(EmbeddingProviders.fromFlag(null, "http://localhost:11434", "nomic-embed-text"))
            .isInstanceOf(NoopEmbeddingProvider.class);
    }

    @Test
    void flagZero_buildsNoop() {
        assertThat(EmbeddingProviders.fromFlag("0", "http://localhost:11434", "nomic-embed-text"))
            .isInstanceOf(NoopEmbeddingProvider.class);
    }
}
