# Semantic Hybrid Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add opt-in local vector embeddings to `cg_search` AND `cg_context`, ranked by semantic similarity fused with the existing FTS5 keyword search via Reciprocal Rank Fusion (RRF), with a deterministic benchmark proof — while never regressing the no-embeddings path.

**Architecture:** A pluggable `EmbeddingProvider` interface (`OllamaEmbeddingProvider` for the local Ollama server via JDK `HttpClient`; `NoopEmbeddingProvider` when off/unreachable). Embeddings are stored as little-endian `float[]` BLOBs in a new `embeddings` SQLite table keyed on `node_id`. `SearchService.search()` and `contextSearch()` run FTS5 as today and, when a query embedding is available, cosine-rank stored vectors and fuse with RRF. Opt-in via `CODE_NAVIGATOR_EMBEDDINGS=1`; when off or Ollama is unreachable the code path is byte-for-byte identical to today's.

**Tech Stack:** Java 21, Gradle shadowJar, SQLite JDBC 3.47.2.0, Jackson 2.18.2 (already on classpath), `java.net.http.HttpClient` (JDK built-in — no new dependency), JUnit 5 + AssertJ (existing infra), `@TempDir` SQLite + in-process `com.sun.net.httpserver.HttpServer` for tests.

**Source spec:** `code-navigator/docs/superpowers/specs/2026-06-21-semantic-hybrid-search-design.md`

---

## File Structure

| Status | File | Responsibility |
|--------|------|----------------|
| Create | `src/main/java/com/codenavigator/embedding/EmbeddingProvider.java` | Interface: `float[] embed(String text)` |
| Create | `src/main/java/com/codenavigator/embedding/NoopEmbeddingProvider.java` | Returns `new float[0]`; used when off/unavailable |
| Create | `src/main/java/com/codenavigator/embedding/OllamaEmbeddingProvider.java` | POSTs to Ollama `/api/embeddings`; parses JSON via Jackson |
| Create | `src/main/java/com/codenavigator/embedding/VectorCodec.java` | little-endian `float[]`↔`byte[]` codec |
| Create | `src/main/java/com/codenavigator/embedding/EmbeddingProviders.java` | DRY factory: `fromEnv()` selects Ollama vs Noop |
| Modify | `src/main/java/com/codenavigator/graph/GraphStore.java` | `embeddings` table in `initSchema()`; `upsertEmbedding` + `streamAllEmbeddings` |
| Modify | `src/main/java/com/codenavigator/search/SearchService.java` | `EmbeddingProvider` ctor; `cosine`, `rrf`; hybrid `search()` + `contextSearch()` |
| Modify | `src/main/java/com/codenavigator/indexer/ProjectIndexer.java` | `EmbeddingProvider` ctor; `embedNode()` after every node save |
| Modify | `src/main/java/com/codenavigator/cli/InitCommand.java` | Wire `EmbeddingProviders.fromEnv()` into `ProjectIndexer` |
| Modify | `src/main/java/com/codenavigator/cli/SyncCommand.java` | Wire provider into `ProjectIndexer` |
| Modify | `src/main/java/com/codenavigator/cli/SyncIfDirtyCommand.java` | Wire provider into `ProjectIndexer` |
| Modify | `src/main/java/com/codenavigator/cli/ServeCommand.java` | Wire provider into `SearchService` + MCP server |
| Modify | `src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java` | `EmbeddingProvider` field; use it in `handleCgSearch` resolved-store branch |
| Modify | `src/main/java/com/codenavigator/benchmark/BenchmarkRunner.java` | Optional `semantic-context` scenario |
| Modify | `src/main/java/com/codenavigator/cli/BenchmarkCommand.java` | Append semantic-context row when embeddings enabled |
| Modify | `src/main/java/com/codenavigator/cli/StatusCommand.java` | Embedding-coverage line |
| Create | `src/test/java/com/codenavigator/embedding/VectorCodecTest.java` | Round-trip tests |
| Create | `src/test/java/com/codenavigator/embedding/EmbeddingProviderTest.java` | Noop + Ollama (stub HTTP) tests |
| Create | `src/test/java/com/codenavigator/embedding/EmbeddingProvidersTest.java` | Factory selection tests |
| Create | `src/test/java/com/codenavigator/search/RrfFusionTest.java` | `cosine()` + `rrf()` unit tests |
| Create | `src/test/java/com/codenavigator/indexer/ProjectIndexerEmbeddingTest.java` | Indexer embeds + persists |
| Modify | `src/test/java/com/codenavigator/graph/GraphStoreTest.java` | embeddings upsert/stream/cascade |
| Modify | `src/test/java/com/codenavigator/search/SearchServiceTest.java` | Noop == FTS-only; hybrid promotes; contextSearch semantic seed |

**Commit convention:** every commit message ends with:
```
Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```
**Working dir for all commands:** `cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator`
**Branch:** work on `feat/semantic-hybrid-search` (already created).

---

### Task 1: VectorCodec — float[] <-> byte[] round-trip

**Files:**
- Create: `src/main/java/com/codenavigator/embedding/VectorCodec.java`
- Test: `src/test/java/com/codenavigator/embedding/VectorCodecTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/codenavigator/embedding/VectorCodecTest.java
package com.codenavigator.embedding;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class VectorCodecTest {

    @Test
    void roundTrip_preservesAllFloats() {
        float[] original = {0.1f, -0.5f, 1.0f, Float.MAX_VALUE, Float.MIN_VALUE, 0.0f};
        byte[] bytes = VectorCodec.toBytes(original);
        float[] restored = VectorCodec.toFloats(bytes);
        assertThat(restored).containsExactly(original);
    }

    @Test
    void roundTrip_emptyArray() {
        byte[] bytes = VectorCodec.toBytes(new float[0]);
        assertThat(bytes).isEmpty();
        assertThat(VectorCodec.toFloats(bytes)).isEmpty();
    }

    @Test
    void byteLength_isFourTimesFloatCount() {
        assertThat(VectorCodec.toBytes(new float[]{1.0f, 2.0f, 3.0f})).hasSize(12);
    }

    @Test
    void littleEndian_knownBytes() {
        // 1.0f in IEEE 754 little-endian = 0x00, 0x00, 0x80, 0x3F
        assertThat(VectorCodec.toBytes(new float[]{1.0f}))
            .containsExactly((byte)0x00, (byte)0x00, (byte)0x80, (byte)0x3F);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.codenavigator.embedding.VectorCodecTest" 2>&1 | tail -20`
Expected: compilation error (class does not exist).

- [ ] **Step 3: Write minimal implementation**

```java
// src/main/java/com/codenavigator/embedding/VectorCodec.java
package com.codenavigator.embedding;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class VectorCodec {

    private VectorCodec() {}

    /** Encode float[] to a little-endian byte[] (4 bytes per float). */
    public static byte[] toBytes(float[] vector) {
        if (vector.length == 0) return new byte[0];
        ByteBuffer buf = ByteBuffer.allocate(4 * vector.length).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : vector) buf.putFloat(f);
        return buf.array();
    }

    /** Decode a little-endian byte[] back to float[]. */
    public static float[] toFloats(byte[] bytes) {
        if (bytes.length == 0) return new float[0];
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] result = new float[bytes.length / 4];
        for (int i = 0; i < result.length; i++) result[i] = buf.getFloat();
        return result;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.codenavigator.embedding.VectorCodecTest" 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/codenavigator/embedding/VectorCodec.java src/test/java/com/codenavigator/embedding/VectorCodecTest.java
git commit -m "$(cat <<'EOF'
feat(embedding): add VectorCodec for little-endian float[]<->byte[] BLOB codec

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: embeddings table + GraphStore upsert/streamAll

**Files:**
- Modify: `src/main/java/com/codenavigator/graph/GraphStore.java` (add table in `initSchema()` ~line 96; add methods after `searchLike` ~line 395)
- Test: `src/test/java/com/codenavigator/graph/GraphStoreTest.java`

- [ ] **Step 1: Write the failing test** — append these methods to the existing `GraphStoreTest` class:

```java
    @Test
    void upsertEmbedding_storesAndRetrieves() {
        store.saveNode(new Node("n1", NodeType.SERVICE, "Svc", "com.Svc", "f1", 1, "", 0));
        float[] vec = {0.1f, 0.2f, 0.3f};
        store.upsertEmbedding("n1", vec);

        java.util.List<float[]> collected = new java.util.ArrayList<>();
        store.streamAllEmbeddings((nodeId, vector) -> {
            if ("n1".equals(nodeId)) collected.add(vector);
        });
        assertThat(collected).hasSize(1);
        assertThat(collected.get(0)).containsExactly(vec);
    }

    @Test
    void upsertEmbedding_replacesOnDuplicate() {
        store.saveNode(new Node("n1", NodeType.SERVICE, "Svc", "com.Svc", "f1", 1, "", 0));
        store.upsertEmbedding("n1", new float[]{0.1f, 0.2f});
        store.upsertEmbedding("n1", new float[]{0.9f, 0.8f});

        java.util.List<float[]> collected = new java.util.ArrayList<>();
        store.streamAllEmbeddings((nodeId, vector) -> collected.add(vector));
        assertThat(collected).hasSize(1);
        assertThat(collected.get(0)).containsExactly(0.9f, 0.8f);
    }

    @Test
    void deleteNode_cascadesEmbedding() {
        store.saveNode(new Node("n1", NodeType.SERVICE, "Svc", "com.Svc", "f1", 1, "", 0));
        store.upsertEmbedding("n1", new float[]{1.0f});
        store.deleteNode("n1");

        java.util.List<float[]> collected = new java.util.ArrayList<>();
        store.streamAllEmbeddings((id, v) -> collected.add(v));
        assertThat(collected).isEmpty();
    }

    @Test
    void streamAllEmbeddings_multipleNodes() {
        store.saveNode(new Node("n1", NodeType.SERVICE, "A", "com.A", "f1", 1, "", 0));
        store.saveNode(new Node("n2", NodeType.SERVICE, "B", "com.B", "f2", 1, "", 0));
        store.upsertEmbedding("n1", new float[]{1.0f, 0.0f});
        store.upsertEmbedding("n2", new float[]{0.0f, 1.0f});

        java.util.Map<String, float[]> seen = new java.util.LinkedHashMap<>();
        store.streamAllEmbeddings(seen::put);
        assertThat(seen).containsKeys("n1", "n2");
        assertThat(seen.get("n1")).containsExactly(1.0f, 0.0f);
        assertThat(seen.get("n2")).containsExactly(0.0f, 1.0f);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.codenavigator.graph.GraphStoreTest" 2>&1 | tail -20`
Expected: compilation error (methods do not exist).

- [ ] **Step 3a: Add the `embeddings` table** to `initSchema()` in `GraphStore.java`. Insert immediately **after** the `idx_co_change_b` index line (currently line 96), still **inside** the `try (Statement stmt ...)` block:

```java
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS embeddings (
                    node_id TEXT PRIMARY KEY REFERENCES nodes(id) ON DELETE CASCADE,
                    vector  BLOB NOT NULL,
                    dim     INTEGER NOT NULL
                )""");
```
(Cascade works because `PRAGMA foreign_keys=ON` is already set in the constructor.)

- [ ] **Step 3b: Add the two methods** to `GraphStore.java`, immediately after the `searchLike` method (ends ~line 400):

```java
    // ---- Embedding operations ----

    public void upsertEmbedding(String nodeId, float[] vector) {
        byte[] blob = com.codenavigator.embedding.VectorCodec.toBytes(vector);
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO embeddings (node_id, vector, dim) VALUES (?, ?, ?)")) {
            ps.setString(1, nodeId);
            ps.setBytes(2, blob);
            ps.setInt(3, vector.length);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert embedding for node: " + nodeId, e);
        }
    }

    public void streamAllEmbeddings(java.util.function.BiConsumer<String, float[]> consumer) {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT node_id, vector FROM embeddings")) {
            while (rs.next()) {
                String nodeId = rs.getString("node_id");
                byte[] blob = rs.getBytes("vector");
                consumer.accept(nodeId, com.codenavigator.embedding.VectorCodec.toFloats(blob));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to stream embeddings", e);
        }
    }

    /** Count of nodes that currently have a stored embedding. */
    public int countEmbeddings() {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM embeddings")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count embeddings", e);
        }
    }
```
(`countEmbeddings` is used by `cg_status` in Task 12.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.codenavigator.graph.GraphStoreTest" 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/codenavigator/graph/GraphStore.java src/test/java/com/codenavigator/graph/GraphStoreTest.java
git commit -m "$(cat <<'EOF'
feat(graph): add embeddings table with upsert, streamAll, and count methods

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: EmbeddingProvider + NoopEmbeddingProvider + cosine()

**Files:**
- Create: `src/main/java/com/codenavigator/embedding/EmbeddingProvider.java`, `NoopEmbeddingProvider.java`
- Modify: `src/main/java/com/codenavigator/search/SearchService.java` (add `cosine` static)
- Test: `src/test/java/com/codenavigator/embedding/EmbeddingProviderTest.java`, `src/test/java/com/codenavigator/search/RrfFusionTest.java`

- [ ] **Step 1: Write the failing tests**

```java
// src/test/java/com/codenavigator/embedding/EmbeddingProviderTest.java
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
```

```java
// src/test/java/com/codenavigator/search/RrfFusionTest.java
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.codenavigator.embedding.EmbeddingProviderTest" --tests "com.codenavigator.search.RrfFusionTest" 2>&1 | tail -20`
Expected: compilation errors.

- [ ] **Step 3a: Implement the interface**

```java
// src/main/java/com/codenavigator/embedding/EmbeddingProvider.java
package com.codenavigator.embedding;

/** Computes a dense float embedding for a text string. */
public interface EmbeddingProvider {
    /**
     * Returns a dense embedding vector for the given text.
     * Returns an empty array ({@code new float[0]}) when unavailable.
     */
    float[] embed(String text);
}
```

- [ ] **Step 3b: Implement Noop**

```java
// src/main/java/com/codenavigator/embedding/NoopEmbeddingProvider.java
package com.codenavigator.embedding;

/** Always returns an empty vector; used when embeddings are disabled or unavailable. */
public final class NoopEmbeddingProvider implements EmbeddingProvider {
    @Override
    public float[] embed(String text) {
        return new float[0];
    }
}
```

- [ ] **Step 3c: Add `cosine()`** to `SearchService.java` — append as a static method after `extractKeywords`:

```java
    /**
     * Cosine similarity between two float vectors.
     * Returns 0.0 if either is empty or they have different dimensions.
     */
    static float cosine(float[] a, float[] b) {
        if (a.length == 0 || b.length == 0 || a.length != b.length) return 0.0f;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot   += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0.0 ? 0.0f : (float) (dot / denom);
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.codenavigator.embedding.EmbeddingProviderTest" --tests "com.codenavigator.search.RrfFusionTest" 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/codenavigator/embedding/EmbeddingProvider.java src/main/java/com/codenavigator/embedding/NoopEmbeddingProvider.java src/main/java/com/codenavigator/search/SearchService.java src/test/java/com/codenavigator/embedding/EmbeddingProviderTest.java src/test/java/com/codenavigator/search/RrfFusionTest.java
git commit -m "$(cat <<'EOF'
feat(embedding): add EmbeddingProvider interface, NoopEmbeddingProvider, cosine()

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: RRF fusion pure function

**Files:**
- Modify: `src/main/java/com/codenavigator/search/SearchService.java`
- Test: `src/test/java/com/codenavigator/search/RrfFusionTest.java`

- [ ] **Step 1: Add failing tests** to `RrfFusionTest.java` (add `import java.util.List;` at top):

```java
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.codenavigator.search.RrfFusionTest" 2>&1 | tail -20`
Expected: compilation error (`rrf` not defined).

- [ ] **Step 3: Add `rrf()`** to `SearchService.java`, after `cosine()` (the class already imports `java.util.*` and `java.util.stream.Collectors`):

```java
    /**
     * Reciprocal Rank Fusion over an arbitrary number of ranked ID lists.
     * Score for each ID = sum_over_lists( 1 / (k + rank_1based) ).
     * Returns IDs in descending score order, deduplicated.
     *
     * @param k     RRF smoothing constant (60 is standard)
     * @param lists ranked lists of node IDs (best first)
     */
    @SafeVarargs
    static List<String> rrf(int k, List<String>... lists) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (List<String> list : lists) {
            for (int i = 0; i < list.size(); i++) {
                scores.merge(list.get(i), 1.0 / (k + i + 1), Double::sum);
            }
        }
        return scores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.codenavigator.search.RrfFusionTest" 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/codenavigator/search/SearchService.java src/test/java/com/codenavigator/search/RrfFusionTest.java
git commit -m "$(cat <<'EOF'
feat(search): add rrf() Reciprocal Rank Fusion pure function

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: SearchService hybrid `search()` with injected EmbeddingProvider

**Files:**
- Modify: `src/main/java/com/codenavigator/search/SearchService.java` (full canonical rewrite)
- Test: `src/test/java/com/codenavigator/search/SearchServiceTest.java`

- [ ] **Step 1: Add failing tests** to `SearchServiceTest.java`. Add imports `import com.codenavigator.embedding.EmbeddingProvider;` and `import com.codenavigator.embedding.NoopEmbeddingProvider;`, then:

```java
    @Test
    void searchWithNoop_behaviorIdenticalToFtsOnly() {
        var noopService = new SearchService(store, new GraphTraversal(store), new NoopEmbeddingProvider());
        var defaultService = new SearchService(store, new GraphTraversal(store));

        var withNoop = noopService.search("PurchaseOrder");
        var withDefault = defaultService.search("PurchaseOrder");

        assertThat(withNoop).extracting(Node::id)
            .containsExactlyInAnyOrderElementsOf(withDefault.stream().map(Node::id).toList());
    }

    @Test
    void searchWithFakeProvider_promotesSemanticMatchFtsMisses() {
        // A node whose text contains no "PurchaseOrder" token, so FTS will not surface it,
        // but whose embedding is perfectly aligned with the (faked) query vector.
        store.saveNode(new Node("com.WorkerController", NodeType.CONTROLLER,
            "WorkerController", "com.WorkerController", "Worker.java", 1, "handles work", 0));
        store.upsertEmbedding("com.WorkerController", new float[]{1.0f, 0.0f});

        EmbeddingProvider fakeProvider = text -> new float[]{1.0f, 0.0f}; // query points at WorkerController
        var hybrid = new SearchService(store, new GraphTraversal(store), fakeProvider);

        var results = hybrid.search("PurchaseOrder");
        assertThat(results).extracting(Node::name).contains("WorkerController");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.codenavigator.search.SearchServiceTest" 2>&1 | tail -20`
Expected: compilation error (3-arg constructor not present).

- [ ] **Step 3: Replace the entire `SearchService.java`** with this canonical version (keeps `contextSearch` legacy for now — Task 10 upgrades it; folds in `cosine`/`rrf` from Tasks 3–4):

```java
package com.codenavigator.search;

import com.codenavigator.embedding.EmbeddingProvider;
import com.codenavigator.embedding.NoopEmbeddingProvider;
import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.GraphTraversal;
import com.codenavigator.graph.Node;

import java.util.*;
import java.util.stream.Collectors;

public class SearchService {

    private static final Set<String> STOP_WORDS = Set.of(
        "a", "an", "the", "to", "from", "in", "on", "at", "by", "for", "with",
        "of", "and", "or", "is", "it", "that", "this", "be", "as", "are", "was",
        "add", "remove", "update", "fix", "change", "modify", "create", "delete",
        "field", "method", "class", "file", "when", "if", "not", "all", "new"
    );

    private final GraphStore store;
    private final GraphTraversal traversal;
    private final EmbeddingProvider embeddingProvider;

    /** Legacy constructor — embeddings disabled (Noop). */
    public SearchService(GraphStore store, GraphTraversal traversal) {
        this(store, traversal, new NoopEmbeddingProvider());
    }

    /** Full constructor with injectable EmbeddingProvider. */
    public SearchService(GraphStore store, GraphTraversal traversal, EmbeddingProvider embeddingProvider) {
        this.store = store;
        this.traversal = traversal;
        this.embeddingProvider = embeddingProvider;
    }

    /**
     * Hybrid search: FTS5 (with LIKE fallback) and, when embeddings are available,
     * cosine-rank stored vectors against the query embedding, fused via RRF.
     * When the provider returns an empty vector, this degrades to the original FTS5 path.
     */
    public List<Node> search(String query) {
        String ftsQuery = query.endsWith("*") ? query : query + "*";
        var ftsOrdered = new LinkedHashMap<String, Node>();
        for (var node : store.searchFts(ftsQuery)) {
            ftsOrdered.put(node.id(), node);
        }
        for (var node : store.searchLike(query)) {
            ftsOrdered.putIfAbsent(node.id(), node);
        }

        float[] queryVec = embeddingProvider.embed(query);
        if (queryVec.length == 0) {
            return new ArrayList<>(ftsOrdered.values()); // original behaviour
        }

        List<String> vecRanked = vectorRank(queryVec);
        List<String> ftsRanked = new ArrayList<>(ftsOrdered.keySet());
        List<String> fused = rrf(60, ftsRanked, vecRanked);

        Map<String, Node> allKnown = new LinkedHashMap<>(ftsOrdered);
        for (String id : vecRanked) {
            allKnown.computeIfAbsent(id, k -> store.findNodeById(k).orElse(null));
        }
        allKnown.values().removeIf(Objects::isNull);

        return fused.stream()
            .filter(allKnown::containsKey)
            .map(allKnown::get)
            .collect(Collectors.toList());
    }

    /**
     * Context search for a task description:
     * 1. Extract keywords -> FTS5 prefix search
     * 2. Expand hits via chain tracing
     * 3. Deduplicate, return
     */
    public List<Node> contextSearch(String taskDescription) {
        var keywords = extractKeywords(taskDescription);
        if (keywords.isEmpty()) return List.of();

        var directHits = new LinkedHashSet<Node>();
        for (var keyword : keywords) {
            directHits.addAll(store.searchFts(keyword + "*"));
        }

        var expanded = new LinkedHashSet<Node>(directHits);
        for (var hit : directHits) {
            expanded.addAll(traversal.traceChain(hit.id()));
        }
        return new ArrayList<>(expanded);
    }

    /** Cosine-rank all stored embeddings against a query vector (desc, sim>0). */
    private List<String> vectorRank(float[] queryVec) {
        List<Map.Entry<String, Float>> scored = new ArrayList<>();
        store.streamAllEmbeddings((nodeId, vector) -> {
            float sim = cosine(queryVec, vector);
            if (sim > 0.0f) scored.add(Map.entry(nodeId, sim));
        });
        scored.sort(Map.Entry.<String, Float>comparingByValue().reversed());
        return scored.stream().map(Map.Entry::getKey).collect(Collectors.toList());
    }

    /** Extract meaningful keywords from text, filtering stop words */
    public List<String> extractKeywords(String text) {
        return Arrays.stream(text.toLowerCase().split("\\s+"))
            .map(w -> w.replaceAll("[^a-z0-9]", ""))
            .filter(w -> !w.isEmpty() && w.length() > 2)
            .filter(w -> !STOP_WORDS.contains(w))
            .distinct()
            .collect(Collectors.toList());
    }

    /**
     * Cosine similarity between two float vectors.
     * Returns 0.0 if either is empty or they have different dimensions.
     */
    static float cosine(float[] a, float[] b) {
        if (a.length == 0 || b.length == 0 || a.length != b.length) return 0.0f;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot   += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0.0 ? 0.0f : (float) (dot / denom);
    }

    /**
     * Reciprocal Rank Fusion over ranked ID lists. Score = sum( 1 / (k + rank_1based) ).
     */
    @SafeVarargs
    static List<String> rrf(int k, List<String>... lists) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (List<String> list : lists) {
            for (int i = 0; i < list.size(); i++) {
                scores.merge(list.get(i), 1.0 / (k + i + 1), Double::sum);
            }
        }
        return scores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }
}
```

- [ ] **Step 4: Run the search tests, then the full suite**

Run: `./gradlew test --tests "com.codenavigator.search.SearchServiceTest" --tests "com.codenavigator.search.RrfFusionTest" 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`.
Run: `./gradlew test 2>&1 | tail -15`
Expected: `BUILD SUCCESSFUL`, no regressions.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/codenavigator/search/SearchService.java src/test/java/com/codenavigator/search/SearchServiceTest.java
git commit -m "$(cat <<'EOF'
feat(search): hybrid RRF search() with injected EmbeddingProvider; Noop degrades to FTS5

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: OllamaEmbeddingProvider — HTTP client with test seam

**Files:**
- Create: `src/main/java/com/codenavigator/embedding/OllamaEmbeddingProvider.java`
- Test: `src/test/java/com/codenavigator/embedding/EmbeddingProviderTest.java`

- [ ] **Step 1: Add failing tests** to `EmbeddingProviderTest.java` (add imports at top):

```java
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
```

```java
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.codenavigator.embedding.EmbeddingProviderTest" 2>&1 | tail -20`
Expected: compilation error.

- [ ] **Step 3: Implement `OllamaEmbeddingProvider`**

```java
// src/main/java/com/codenavigator/embedding/OllamaEmbeddingProvider.java
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
```

- [ ] **Step 4: Run tests, then full suite**

Run: `./gradlew test --tests "com.codenavigator.embedding.EmbeddingProviderTest" 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`.
Run: `./gradlew test 2>&1 | tail -15`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/codenavigator/embedding/OllamaEmbeddingProvider.java src/test/java/com/codenavigator/embedding/EmbeddingProviderTest.java
git commit -m "$(cat <<'EOF'
feat(embedding): add OllamaEmbeddingProvider with injectable HttpClient test seam

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: EmbeddingProviders factory (DRY env wiring)

**Files:**
- Create: `src/main/java/com/codenavigator/embedding/EmbeddingProviders.java`
- Test: `src/test/java/com/codenavigator/embedding/EmbeddingProvidersTest.java`

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.codenavigator.embedding.EmbeddingProvidersTest" 2>&1 | tail -20`
Expected: compilation error.

- [ ] **Step 3: Implement the factory**

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.codenavigator.embedding.EmbeddingProvidersTest" 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/codenavigator/embedding/EmbeddingProviders.java src/test/java/com/codenavigator/embedding/EmbeddingProvidersTest.java
git commit -m "$(cat <<'EOF'
feat(embedding): add EmbeddingProviders.fromEnv factory for DRY provider selection

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: ProjectIndexer embeds nodes; wire index commands

**Files:**
- Modify: `src/main/java/com/codenavigator/indexer/ProjectIndexer.java`
- Modify: `src/main/java/com/codenavigator/cli/InitCommand.java`, `SyncCommand.java`, `SyncIfDirtyCommand.java`
- Test: `src/test/java/com/codenavigator/indexer/ProjectIndexerEmbeddingTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/codenavigator/indexer/ProjectIndexerEmbeddingTest.java
package com.codenavigator.indexer;

import com.codenavigator.embedding.EmbeddingProvider;
import com.codenavigator.graph.GraphStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectIndexerEmbeddingTest {

    @TempDir Path tempDir;
    @TempDir Path projectDir;
    private GraphStore store;

    @BeforeEach
    void setUp() { store = new GraphStore(tempDir.resolve("test.db")); }

    @AfterEach
    void tearDown() { store.close(); }

    private void writeDummy() throws Exception {
        Path src = projectDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Dummy.java"),
            "package com.example;\npublic class Dummy {}");
    }

    @Test
    void embeddingProviderCalledAndPersistedWhenEnabled() throws Exception {
        writeDummy();
        AtomicInteger callCount = new AtomicInteger(0);
        EmbeddingProvider counting = text -> { callCount.incrementAndGet(); return new float[]{0.1f, 0.2f}; };

        new ProjectIndexer(store, counting).indexFull(projectDir);

        assertThat(callCount.get()).isGreaterThan(0);
        List<float[]> stored = new ArrayList<>();
        store.streamAllEmbeddings((id, v) -> stored.add(v));
        assertThat(stored).isNotEmpty();
    }

    @Test
    void noopProviderStoresNoEmbeddings() throws Exception {
        writeDummy();
        new ProjectIndexer(store).indexFull(projectDir); // default Noop

        List<float[]> stored = new ArrayList<>();
        store.streamAllEmbeddings((id, v) -> stored.add(v));
        assertThat(stored).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.codenavigator.indexer.ProjectIndexerEmbeddingTest" 2>&1 | tail -20`
Expected: compilation error (2-arg constructor not present).

- [ ] **Step 3a: Modify `ProjectIndexer.java`** — add the field and constructor overload. Replace the existing single constructor (lines 27–33) with:

```java
    private final com.codenavigator.embedding.EmbeddingProvider embeddingProvider;

    public ProjectIndexer(GraphStore store) {
        this(store, new com.codenavigator.embedding.NoopEmbeddingProvider());
    }

    public ProjectIndexer(GraphStore store, com.codenavigator.embedding.EmbeddingProvider embeddingProvider) {
        this.store = store;
        this.embeddingProvider = embeddingProvider;
        this.projectDetector = new ProjectDetector();
        this.tsIndexer = new TypeScriptIndexer();
        this.methodExtractor = new MethodExtractor();
        this.dependencyParser = new DependencyParser();
    }
```

- [ ] **Step 3b: Add the `embedNode` helper** to `ProjectIndexer.java` (place near the bottom, before `computeChecksum`):

```java
    /** Embeds a node's text and stores the vector. Failures never break indexing. */
    private void embedNode(Node node) {
        try {
            String text = node.name() + " " + node.qualifiedName()
                + (node.codeSnippet() != null ? " " + node.codeSnippet() : "");
            float[] vec = embeddingProvider.embed(text);
            if (vec.length > 0) {
                store.upsertEmbedding(node.id(), vec);
            }
        } catch (Exception e) {
            // Embedding failures must never break indexing
        }
    }
```

- [ ] **Step 3c: Call `embedNode` at all four node-save sites.** After each `store.saveNode(...)` line that saves a graph **node** (not method/edge), add `embedNode(...)`:

  1. `indexFull`, Java nodes (after `store.saveNode(nodeWithTime);`, ~line 69):
     ```java
                     store.saveNode(nodeWithTime);
                     embedNode(nodeWithTime);
     ```
  2. `indexFull`, TS nodes (after `store.saveNode(node);`, ~line 95):
     ```java
                 store.saveNode(node);
                 embedNode(node);
     ```
  3. `reindexChangedJavaFiles` (after `store.saveNode(nodeWithTime);`, ~line 177):
     ```java
                     store.saveNode(nodeWithTime);
                     embedNode(nodeWithTime);
     ```
  4. `reindexChangedTsFiles` (after `store.saveNode(node);`, ~line 203):
     ```java
                     store.saveNode(node);
                     embedNode(node);
     ```

- [ ] **Step 3d: Wire the factory into the three index commands.**

  `InitCommand.java` — replace line 22 (`var indexer = new ProjectIndexer(store);`):
  ```java
              var indexer = new ProjectIndexer(store, com.codenavigator.embedding.EmbeddingProviders.fromEnv());
  ```
  `SyncCommand.java` — replace line 23 (`var indexer = new ProjectIndexer(store);`):
  ```java
              var indexer = new ProjectIndexer(store, com.codenavigator.embedding.EmbeddingProviders.fromEnv());
  ```
  `SyncIfDirtyCommand.java` — replace line 35 (`var indexer = new ProjectIndexer(store);`):
  ```java
                  var indexer = new ProjectIndexer(store, com.codenavigator.embedding.EmbeddingProviders.fromEnv());
  ```

- [ ] **Step 4: Run test, then full suite**

Run: `./gradlew test --tests "com.codenavigator.indexer.ProjectIndexerEmbeddingTest" 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`.
Run: `./gradlew test 2>&1 | tail -15`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/codenavigator/indexer/ProjectIndexer.java src/main/java/com/codenavigator/cli/InitCommand.java src/main/java/com/codenavigator/cli/SyncCommand.java src/main/java/com/codenavigator/cli/SyncIfDirtyCommand.java src/test/java/com/codenavigator/indexer/ProjectIndexerEmbeddingTest.java
git commit -m "$(cat <<'EOF'
feat(indexer): embed nodes during full + incremental indexing, wired via env factory

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: Wire SearchService + MCP server (cg_search both store paths)

**Files:**
- Modify: `src/main/java/com/codenavigator/cli/ServeCommand.java`
- Modify: `src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java`
- Test: `src/test/java/com/codenavigator/mcp/CodeNavigatorMcpServerTest.java` (existing — adjust constructor call)

- [ ] **Step 1: Update the failing test.** In `CodeNavigatorMcpServerTest.java`, every `new CodeNavigatorMcpServer(store, traversal, search, domainHandlers)` becomes a 5-arg call. Add `import com.codenavigator.embedding.NoopEmbeddingProvider;` and pass `new NoopEmbeddingProvider()` as the last argument, e.g.:

```java
        var serverUnderTest = new CodeNavigatorMcpServer(
            store, traversal, search, domainHandlers, new NoopEmbeddingProvider());
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.codenavigator.mcp.CodeNavigatorMcpServerTest" 2>&1 | tail -20`
Expected: compilation error (5-arg constructor not present).

- [ ] **Step 3a: Add the provider to `CodeNavigatorMcpServer.java`.** Add the import `import com.codenavigator.embedding.EmbeddingProvider;`, add a field after line 29, and extend the constructor (lines 33–38):

```java
    private final EmbeddingProvider embeddingProvider;

    public CodeNavigatorMcpServer(GraphStore store, GraphTraversal traversal, SearchService searchService,
                                  DomainToolHandlers domainHandlers, EmbeddingProvider embeddingProvider) {
        this.store = store;
        this.traversal = traversal;
        this.searchService = searchService;
        this.domainHandlers = domainHandlers;
        this.embeddingProvider = embeddingProvider;
    }
```

- [ ] **Step 3b: Thread the provider through the resolved-store branch** of `handleCgSearch` (line 370–371). Replace:

```java
        var resolvedSearch = resolvedStore == store ? searchService
            : new SearchService(resolvedStore, new GraphTraversal(resolvedStore));
```
with:
```java
        var resolvedSearch = resolvedStore == store ? searchService
            : new SearchService(resolvedStore, new GraphTraversal(resolvedStore), embeddingProvider);
```

- [ ] **Step 3c: Wire the factory into `ServeCommand.java`.** Replace line 29 (`var search = new SearchService(store, traversal);`) and the server construction (line 43):

```java
        var embeddingProvider = com.codenavigator.embedding.EmbeddingProviders.fromEnv();
        var search = new SearchService(store, traversal, embeddingProvider);
```
and:
```java
        var mcpServer = new CodeNavigatorMcpServer(store, traversal, search, domainHandlers, embeddingProvider);
```

- [ ] **Step 4: Run the MCP test, then full suite**

Run: `./gradlew test --tests "com.codenavigator.mcp.CodeNavigatorMcpServerTest" 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`.
Run: `./gradlew test 2>&1 | tail -15`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/codenavigator/cli/ServeCommand.java src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java src/test/java/com/codenavigator/mcp/CodeNavigatorMcpServerTest.java
git commit -m "$(cat <<'EOF'
feat(mcp): wire EmbeddingProvider into ServeCommand + cg_search resolved-store path

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: `cg_context` goes hybrid (contextSearch semantic seeds)

**Files:**
- Modify: `src/main/java/com/codenavigator/search/SearchService.java` (`contextSearch` + a constant)
- Test: `src/test/java/com/codenavigator/search/SearchServiceTest.java`

- [ ] **Step 1: Add failing tests** to `SearchServiceTest.java`:

```java
    @Test
    void contextSearchWithNoop_identicalToLegacy() {
        var noop = new SearchService(store, new GraphTraversal(store), new NoopEmbeddingProvider());
        var dflt = new SearchService(store, new GraphTraversal(store));
        assertThat(noop.contextSearch("create purchase order"))
            .extracting(Node::id)
            .containsExactlyElementsOf(dflt.contextSearch("create purchase order").stream().map(Node::id).toList());
    }

    @Test
    void contextSearchWithFakeProvider_addsSemanticSeedKeywordsMiss() {
        // A node with no keyword overlap with the task, surfaced only by its embedding.
        store.saveNode(new Node("com.LoginValidator", NodeType.SERVICE,
            "LoginValidator", "com.LoginValidator", "Login.java", 1, "checks credentials", 0));
        store.upsertEmbedding("com.LoginValidator", new float[]{1.0f, 0.0f});

        EmbeddingProvider fake = text -> new float[]{1.0f, 0.0f};
        var hybrid = new SearchService(store, new GraphTraversal(store), fake);

        var nodes = hybrid.contextSearch("where does request authentication happen");
        assertThat(nodes).extracting(Node::name).contains("LoginValidator");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.codenavigator.search.SearchServiceTest" 2>&1 | tail -20`
Expected: `contextSearchWithFakeProvider_addsSemanticSeedKeywordsMiss` FAILS (LoginValidator not surfaced — contextSearch is still keyword-only).

- [ ] **Step 3a: Add the seed-limit constant** to `SearchService.java`, next to `STOP_WORDS`:

```java
    private static final int SEMANTIC_SEED_LIMIT = 10;
```

- [ ] **Step 3b: Replace the `contextSearch` method** in `SearchService.java` with the hybrid version:

```java
    /**
     * Context search for a task description:
     * 1. Extract keywords -> FTS5 prefix search (seed set)
     * 2. If embeddings available, add top-N cosine hits for the whole task to the seed set
     * 3. Expand seeds via chain tracing
     * 4. Deduplicate, return
     *
     * With NoopEmbeddingProvider the query vector is empty, so step 2 is a no-op
     * and the output is identical to the legacy keyword-only behaviour.
     */
    public List<Node> contextSearch(String taskDescription) {
        var keywords = extractKeywords(taskDescription);
        if (keywords.isEmpty()) return List.of();

        var directHits = new LinkedHashSet<Node>();
        for (var keyword : keywords) {
            directHits.addAll(store.searchFts(keyword + "*"));
        }

        float[] queryVec = embeddingProvider.embed(taskDescription);
        if (queryVec.length > 0) {
            vectorRank(queryVec).stream()
                .limit(SEMANTIC_SEED_LIMIT)
                .map(id -> store.findNodeById(id).orElse(null))
                .filter(Objects::nonNull)
                .forEach(directHits::add);
        }

        var expanded = new LinkedHashSet<Node>(directHits);
        for (var hit : directHits) {
            expanded.addAll(traversal.traceChain(hit.id()));
        }
        return new ArrayList<>(expanded);
    }
```

- [ ] **Step 4: Run tests, then full suite**

Run: `./gradlew test --tests "com.codenavigator.search.SearchServiceTest" 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`.
Run: `./gradlew test 2>&1 | tail -15`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/codenavigator/search/SearchService.java src/test/java/com/codenavigator/search/SearchServiceTest.java
git commit -m "$(cat <<'EOF'
feat(search): cg_context hybrid — semantic seeds feed chain expansion; Noop == legacy

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: Benchmark proof — recall@k test + optional semantic-context row

**Files:**
- Test: `src/test/java/com/codenavigator/search/SearchServiceTest.java` (recall@k proof)
- Modify: `src/main/java/com/codenavigator/benchmark/BenchmarkRunner.java`
- Modify: `src/main/java/com/codenavigator/cli/BenchmarkCommand.java`

- [ ] **Step 1a: Add the deterministic recall@k proof** to `SearchServiceTest.java`. This is the quality evidence: a synonym query where FTS-only fails but hybrid ranks the target in top-k.

```java
    @Test
    void recallAtK_hybridFindsSynonymTargetThatFtsOnlyMisses() {
        // Target shares NO query token, so FTS-only cannot find it.
        store.saveNode(new Node("com.CredentialChecker", NodeType.SERVICE,
            "CredentialChecker", "com.CredentialChecker", "Cred.java", 1, "verifies password", 0));
        store.upsertEmbedding("com.CredentialChecker", new float[]{0.0f, 1.0f});
        // A distractor with a different vector.
        store.saveNode(new Node("com.Unrelated", NodeType.SERVICE,
            "Unrelated", "com.Unrelated", "U.java", 1, "does other things", 0));
        store.upsertEmbedding("com.Unrelated", new float[]{1.0f, 0.0f});

        // FTS-only finds nothing for "authentication".
        var ftsOnly = new SearchService(store, new GraphTraversal(store));
        assertThat(ftsOnly.search("authentication")).extracting(Node::name)
            .doesNotContain("CredentialChecker");

        // Hybrid: query vector aligned with CredentialChecker -> top-k contains it.
        EmbeddingProvider fake = text -> new float[]{0.0f, 1.0f};
        var hybrid = new SearchService(store, new GraphTraversal(store), fake);
        var topK = hybrid.search("authentication").stream().limit(3).map(Node::name).toList();
        assertThat(topK).contains("CredentialChecker");
    }
```

- [ ] **Step 1b: Run it — confirm it passes** (the hybrid path from Task 5 already supports this):

Run: `./gradlew test --tests "com.codenavigator.search.SearchServiceTest" 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`. (If it fails, the hybrid `search` from Task 5 is broken — fix there.)

- [ ] **Step 2: Add the optional `semantic-context` scenario to `BenchmarkRunner.java`.** Add an import `import com.codenavigator.search.SearchService;` and append this public method (after `run()`):

```java
    /**
     * Optional 4th scenario: tokens to answer a natural-language query.
     * Baseline = raw content of files for the keyword (FTS) hits;
     * Navigator = compact hybrid cg_context listing for the same query.
     * Only meaningful when embeddings are enabled (caller decides whether to invoke).
     */
    public BenchmarkRow runSemanticContext(SearchService hybridSearch, String query) {
        // Baseline: files surfaced by keyword search alone, read raw.
        List<String> keywordFiles = new ArrayList<>();
        for (String kw : hybridSearch.extractKeywords(query)) {
            for (Node n : store.searchFts(kw + "*")) {
                if (!keywordFiles.contains(n.filePath())) keywordFiles.add(n.filePath());
            }
        }
        int baselineTokens = estimator.estimate(readFiles(keywordFiles));

        // Navigator: compact hybrid context listing.
        List<Node> ctx = hybridSearch.contextSearch(query);
        StringBuilder sb = new StringBuilder();
        sb.append("## Context for: ").append(query).append("\n\n");
        for (Node n : ctx) {
            sb.append("- ").append(n.type()).append(" ").append(n.name())
              .append(" (").append(n.filePath()).append(")\n");
        }
        int navigatorTokens = estimator.estimate(sb.toString());

        return makeRow("semantic-context", baselineTokens, navigatorTokens);
    }
```
(`readFiles`, `estimator`, `store`, and `makeRow` already exist as private members of `BenchmarkRunner`.)

- [ ] **Step 3: Append the row in `BenchmarkCommand.java` only when embeddings are enabled.** Replace the body of the `try (var store ...)` block (lines 59–66) with:

```java
        try (var store = new GraphStore(ProjectPaths.graphDb(projectPath))) {
            var traversal = new GraphTraversal(store);
            var runner = new BenchmarkRunner(store, traversal, tempDir, new TokenEstimator(), projectPath);

            List<BenchmarkRow> rows = new java.util.ArrayList<>(runner.run());

            // Probe: only add the semantic-context row when an embedding backend responds.
            var provider = com.codenavigator.embedding.EmbeddingProviders.fromEnv();
            if (provider.embed("probe").length > 0) {
                var search = new com.codenavigator.search.SearchService(store, traversal, provider);
                rows.add(runner.runSemanticContext(search, "where does request validation happen"));
            } else {
                System.err.println("(semantic-context scenario skipped: embeddings off or backend unreachable)");
            }

            String table = new MarkdownTableRenderer().render(rows);
            System.out.println(table);
        } catch (Exception e) {
            System.err.println("Benchmark failed: " + e.getMessage());
        } finally {
            deleteTempDir(tempDir);
        }
```
Add the import `import com.codenavigator.graph.Node;` is **not** needed in BenchmarkCommand (it builds no Node directly). Ensure `import java.util.List;` remains.

- [ ] **Step 4: Run the full suite and build the JAR**

Run: `./gradlew test 2>&1 | tail -15`
Expected: `BUILD SUCCESSFUL`.
Run: `./gradlew shadowJar 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/codenavigator/search/SearchServiceTest.java src/main/java/com/codenavigator/benchmark/BenchmarkRunner.java src/main/java/com/codenavigator/cli/BenchmarkCommand.java
git commit -m "$(cat <<'EOF'
feat(benchmark): recall@k hybrid proof + optional semantic-context scenario

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: cg_status embedding-coverage line

**Files:**
- Modify: `src/main/java/com/codenavigator/cli/StatusCommand.java`

- [ ] **Step 1: Add the coverage line.** In `StatusCommand.run()`, after the `Total: ... nodes, ... edges` line (line 30), add:

```java
            var embeddingCount = store.countEmbeddings();
            System.out.printf("Embeddings: %d/%d nodes%n%n", embeddingCount, totalNodes);
```
(Remove the existing trailing `%n` duplication: the current line 30 prints `...edges%n%n`; change it to `...edges%n` so the spacing stays correct, since the new line now provides the blank line.)

Concretely, replace line 30:
```java
            System.out.printf("Total: %d nodes, %d edges%n%n", totalNodes, totalEdges);
```
with:
```java
            System.out.printf("Total: %d nodes, %d edges%n", totalNodes, totalEdges);
            var embeddingCount = store.countEmbeddings();
            System.out.printf("Embeddings: %d/%d nodes%n%n", embeddingCount, totalNodes);
```

- [ ] **Step 2: Verify it compiles and the suite is green**

Run: `./gradlew test 2>&1 | tail -15`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Manual smoke check** (optional, no Ollama needed — coverage shows 0):

Run: `./gradlew shadowJar 2>&1 | tail -5 && java -jar build/libs/*-all.jar status .` (from a project that has an index)
Expected: output includes `Embeddings: 0/<n> nodes`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/codenavigator/cli/StatusCommand.java
git commit -m "$(cat <<'EOF'
feat(status): show embedding coverage (N/M nodes) in cg_status

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

**1. Spec coverage**

| Spec requirement | Task |
|---|---|
| `EmbeddingProvider` interface (`embed → float[]`) | T3 |
| `NoopEmbeddingProvider` (empty / off / unreachable) | T3, T6 (unreachable), T8 (default ctor) |
| `OllamaEmbeddingProvider` (POST /api/embeddings, Jackson, JDK HttpClient, no new dep) | T6 |
| `VectorCodec` little-endian BLOB | T1 |
| `embeddings` table (PK→nodes ON DELETE CASCADE, vector, dim) | T2 |
| `GraphStore.upsertEmbedding` + `streamAllEmbeddings` | T2 |
| Pluggable seam, Ollama the one shipped impl | T3/T6/T7 |
| Opt-in via `CODE_NAVIGATOR_EMBEDDINGS` (+ OLLAMA_BASE_URL/MODEL) | T7 factory; T8/T9 wiring |
| Embedding text = name + qualifiedName + codeSnippet | T8 `embedNode` |
| `cosine` similarity | T3 |
| `rrf` fusion | T4 |
| Hybrid `cg_search` — Noop == FTS-only; semantic promotes | T5 |
| cg_search resolved-store path also uses provider | T9 |
| Hybrid `cg_context` — semantic seeds; Noop == legacy | T10 |
| Benchmark proof — recall@k + optional semantic-context row | T11 |
| `cg_status` embedding coverage | T12 (uses `countEmbeddings` from T2) |
| Indexing failures never break indexing | T8 `embedNode` try/catch |
| Embedding errors degrade to FTS | T6 (empty on error) + T5/T10 (empty ⇒ FTS path) |
| Dimension mismatch ⇒ cosine 0 | T3 |
| No new Gradle dependency | JDK HttpClient + existing Jackson; tests use JDK `HttpServer` |
| Fully deterministic / offline tests | All tests: Noop, stub vectors, in-process HttpServer |

No gaps found.

**2. Placeholder scan:** No TBD/TODO; every code step shows complete code; every run step shows the command + expected result.

**3. Type/signature consistency:**
- `EmbeddingProvider.embed(String) → float[]` consistent across T3, T6, T8, T10, T11.
- `SearchService` 3-arg ctor `(GraphStore, GraphTraversal, EmbeddingProvider)` consistent across T5, T9, T10, T11.
- `ProjectIndexer` 2-arg ctor `(GraphStore, EmbeddingProvider)` consistent T8.
- `CodeNavigatorMcpServer` 5-arg ctor consistent T9.
- `GraphStore.upsertEmbedding(String, float[])`, `streamAllEmbeddings(BiConsumer<String,float[]>)`, `countEmbeddings()` defined in T2, used in T5/T8/T10/T11/T12.
- `EmbeddingProviders.fromEnv()` / `fromFlag(...)` defined T7, used T8/T9/T11.
- `BenchmarkRunner.runSemanticContext(SearchService, String)` defined T11, used in BenchmarkCommand T11.
- `SearchService.extractKeywords` is `public` (used by `BenchmarkRunner.runSemanticContext` in T11) — confirmed public in the current code and T5 rewrite.

Consistent. Plan ready.
