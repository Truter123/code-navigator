package com.codenavigator.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Computes embeddings by calling a local Ollama server.
 * Defaults to http://localhost:11434 with model nomic-embed-text.
 * Returns an empty array on any error (network, timeout, parse, non-200) so the
 * caller degrades gracefully to FTS5-only search.
 */
public final class OllamaEmbeddingProvider implements EmbeddingProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final String model;
    private final HttpClient httpClient;

    /** Production constructor — builds a default HttpClient. */
    public OllamaEmbeddingProvider(String baseUrl, String model) {
        this(baseUrl, model, HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    /** Package-visible constructor for testing with an injected HttpClient. */
    OllamaEmbeddingProvider(String baseUrl, String model, HttpClient httpClient) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.httpClient = httpClient;
    }

    @Override
    public float[] embed(String text) {
        try {
            String body = MAPPER.writeValueAsString(new OllamaRequest(model, text));
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/embeddings"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return new float[0];

            OllamaResponse parsed = MAPPER.readValue(response.body(), OllamaResponse.class);
            if (parsed.embedding() == null || parsed.embedding().isEmpty()) return new float[0];

            float[] result = new float[parsed.embedding().size()];
            for (int i = 0; i < result.length; i++) result[i] = parsed.embedding().get(i).floatValue();
            return result;
        } catch (Exception e) {
            return new float[0]; // degrade silently
        }
    }

    private record OllamaRequest(String model, String prompt) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record OllamaResponse(List<Double> embedding) {}
}
