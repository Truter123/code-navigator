package com.codenavigator.embedding;

/** Computes a dense float embedding for a text string. */
public interface EmbeddingProvider {
    /**
     * Returns a dense embedding vector for the given text.
     * Returns an empty array ({@code new float[0]}) when unavailable.
     */
    float[] embed(String text);
}
