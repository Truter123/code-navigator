package com.codenavigator.embedding;

/** Always returns an empty vector; used when embeddings are disabled or unavailable. */
public final class NoopEmbeddingProvider implements EmbeddingProvider {
    @Override
    public float[] embed(String text) {
        return new float[0];
    }
}
