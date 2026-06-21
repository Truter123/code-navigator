// src/main/java/com/codenavigator/embedding/EmbeddingProviders.java
package com.codenavigator.embedding;

/** Builds the configured EmbeddingProvider from environment variables. */
public final class EmbeddingProviders {

    private EmbeddingProviders() {}

    /** Reads CODE_NAVIGATOR_EMBEDDINGS / OLLAMA_BASE_URL / OLLAMA_MODEL. */
    public static EmbeddingProvider fromEnv() {
        return fromFlag(
            System.getenv("CODE_NAVIGATOR_EMBEDDINGS"),
            System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434"),
            System.getenv().getOrDefault("OLLAMA_MODEL", "nomic-embed-text"));
    }

    /** Pure selection logic, package-visible for testing. */
    static EmbeddingProvider fromFlag(String flag, String baseUrl, String model) {
        if ("1".equals(flag) || "true".equalsIgnoreCase(flag)) {
            return new OllamaEmbeddingProvider(baseUrl, model);
        }
        return new NoopEmbeddingProvider();
    }
}
