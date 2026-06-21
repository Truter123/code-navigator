package com.codenavigator.embedding;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;

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

    @Test
    void ollama_returnsEmbeddingFromStubServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/api/embeddings", exchange -> {
            byte[] body = "{\"embedding\":[0.1,0.2,0.3]}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });
        server.start();
        try {
            var provider = new OllamaEmbeddingProvider(
                "http://localhost:" + port, "nomic-embed-text", HttpClient.newHttpClient());
            assertThat(provider.embed("hello world")).containsExactly(0.1f, 0.2f, 0.3f);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ollama_returnsEmptyArrayWhenServerUnreachable() {
        var provider = new OllamaEmbeddingProvider(
            "http://localhost:1", "nomic-embed-text", HttpClient.newHttpClient());
        assertThat(provider.embed("hello")).isEmpty();
    }
}
