# Semantic Vector Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add opt-in local vector embeddings to `cg_search` so results are ranked by semantic similarity fused with existing FTS5 keyword scoring via Reciprocal Rank Fusion (RRF), while never regressing the no-embeddings path.

**Architecture:** A pluggable `EmbeddingProvider` interface with `OllamaEmbeddingProvider` (HTTP POST to localhost:11434 via `java.net.http.HttpClient`, parsed with existing Jackson) and `NoopEmbeddingProvider` (returns empty); embeddings stored as little-endian `float[]` BLOBs in a new `embeddings` SQLite table keyed on `node_id`; `SearchService.search()` runs FTS5 as today and, when a query embedding is available, computes cosine similarity against stored vectors and fuses both ranked lists with RRF. Opt-in via `CODE_NAVIGATOR_EMBEDDINGS=1` env flag; when the flag is absent or Ollama is unreachable, the code path is identical to today's.

**Tech Stack:** Java 21, Gradle 9 shadowJar, SQLite JDBC 3.47.2.0, Jackson `ObjectMapper` 2.18.2 (already on classpath), `java.net.http.HttpClient` (JDK built-in — no new dependency), JUnit 5 + AssertJ (existing test infra), `@TempDir` SQLite for integration tests.

---

## File Structure

| Status | File | Responsibility |
|--------|------|----------------|
| Create | `src/main/java/com/codenavigator/embedding/EmbeddingProvider.java` | Interface: `float[] embed(String text)` |
| Create | `src/main/java/com/codenavigator/embedding/NoopEmbeddingProvider.java` | Returns `new float[0]`; used when embeddings off or Ollama absent |
| Create | `src/main/java/com/codenavigator/embedding/OllamaEmbeddingProvider.java` | POSTs to Ollama `/api/embeddings`; parses JSON via Jackson |
| Create | `src/main/java/com/codenavigator/embedding/VectorCodec.java` | `float[] toFloats(byte[])` / `byte[] toBytes(float[])` little-endian codec |
| Modify | `src/main/java/com/codenavigator/graph/GraphStore.java` | Add `embeddings` table to `initSchema()`; add `upsertEmbedding(String nodeId, float[] vector)` and `streamAllEmbeddings(BiConsumer<String, float[]>)` |
| Modify | `src/main/java/com/codenavigator/search/SearchService.java` | Add `EmbeddingProvider` constructor overload; add `cosine(float[], float[])` static method; add `rrf(List<String>...)` static method; upgrade `search(String)` to hybrid path |
| Modify | `src/main/java/com/codenavigator/indexer/ProjectIndexer.java` | Accept optional `EmbeddingProvider`; after `store.saveNode()` inside `indexFull` and `reindexChangedJavaFiles`, call `provider.embed(text)` and `store.upsertEmbedding()` when env flag is set |
| Modify | `src/main/java/com/codenavigator/CodeNavigatorApplication.java` | Wire `OllamaEmbeddingProvider` or `NoopEmbeddingProvider` into `ProjectIndexer` and `SearchService` based on `CODE_NAVIGATOR_EMBEDDINGS` env flag |
| Create | `src/test/java/com/codenavigator/embedding/VectorCodecTest.java` | Round-trip tests for `toBytes` / `toFloats` |
| Create | `src/test/java/com/codenavigator/embedding/EmbeddingProviderTest.java` | Tests for `NoopEmbeddingProvider`; stub-HTTP test for `OllamaEmbeddingProvider` |
| Create | `src/test/java/com/codenavigator/search/RrfFusionTest.java` | Deterministic unit tests for `cosine()` and `rrf()` |
| Modify | `src/test/java/com/codenavigator/graph/GraphStoreTest.java` | Add `upsertEmbedding` / `streamAllEmbeddings` tests |
| Modify | `src/test/java/com/codenavigator/search/SearchServiceTest.java` | Add tests: Noop path equals FTS5-only; fake provider changes ranking |

---

### Task 1: VectorCodec — float[] <-> byte[] round-trip

**Files:** `VectorCodec.java`, `VectorCodecTest.java`

- [ ] Write the failing test:

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
        float[] restored = VectorCodec.toFloats(bytes);
        assertThat(restored).isEmpty();
    }

    @Test
    void byteLength_isFourTimesFloatCount() {
        float[] vec = {1.0f, 2.0f, 3.0f};
        assertThat(VectorCodec.toBytes(vec)).hasSize(12);
    }

    @Test
    void littleEndian_knownBytes() {
        // 1.0f in IEEE 754 little-endian = 0x00, 0x00, 0x80, 0x3F
        float[] vec = {1.0f};
        byte[] bytes = VectorCodec.toBytes(vec);
        assertThat(bytes).containsExactly((byte)0x00, (byte)0x00, (byte)0x80, (byte)0x3F);
    }
}
```

- [ ] Run and confirm FAIL:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.embedding.VectorCodecTest" 2>&1 | tail -20
```
Expected: compilation error (class does not exist yet).

- [ ] Implement `VectorCodec`:

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

- [ ] Run and confirm PASS:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.embedding.VectorCodecTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`.

- [ ] Commit:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && git add src/main/java/com/codenavigator/embedding/VectorCodec.java src/test/java/com/codenavigator/embedding/VectorCodecTest.java && git commit -m "$(cat <<'EOF'
feat(embedding): add VectorCodec for little-endian float[]<->byte[] BLOB codec

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: embeddings table + GraphStore upsert/streamAll

**Files:** `GraphStore.java`, `GraphStoreTest.java`

- [ ] Write the failing test (append to existing `GraphStoreTest`):

```java
// Add inside GraphStoreTest class — new test methods

@Test
void upsertEmbedding_storesAndRetrieves() {
    store.saveNode(new Node("n1", NodeType.SERVICE, "Svc", "com.Svc", "f1", 1, "", 0));
    float[] vec = {0.1f, 0.2f, 0.3f};
    store.upsertEmbedding("n1", vec);

    List<float[]> collected = new ArrayList<>();
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

    List<float[]> collected = new ArrayList<>();
    store.streamAllEmbeddings((nodeId, vector) -> collected.add(vector));
    assertThat(collected).hasSize(1);
    assertThat(collected.get(0)).containsExactly(0.9f, 0.8f);
}

@Test
void deleteNode_cascadesEmbedding() {
    store.saveNode(new Node("n1", NodeType.SERVICE, "Svc", "com.Svc", "f1", 1, "", 0));
    store.upsertEmbedding("n1", new float[]{1.0f});
    store.deleteNode("n1");

    List<float[]> collected = new ArrayList<>();
    store.streamAllEmbeddings((id, v) -> collected.add(v));
    assertThat(collected).isEmpty();
}

@Test
void streamAllEmbeddings_multipleNodes() {
    store.saveNode(new Node("n1", NodeType.SERVICE, "A", "com.A", "f1", 1, "", 0));
    store.saveNode(new Node("n2", NodeType.SERVICE, "B", "com.B", "f2", 1, "", 0));
    store.upsertEmbedding("n1", new float[]{1.0f, 0.0f});
    store.upsertEmbedding("n2", new float[]{0.0f, 1.0f});

    Map<String, float[]> seen = new java.util.LinkedHashMap<>();
    store.streamAllEmbeddings(seen::put);
    assertThat(seen).containsKeys("n1", "n2");
    assertThat(seen.get("n1")).containsExactly(1.0f, 0.0f);
    assertThat(seen.get("n2")).containsExactly(0.0f, 1.0f);
}
```

Also add imports at top of `GraphStoreTest.java`:
```java
import java.util.ArrayList;
import java.util.Map;
import java.util.function.BiConsumer;
```

- [ ] Run and confirm FAIL:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.graph.GraphStoreTest" 2>&1 | tail -20
```
Expected: compilation error (methods do not exist yet).

- [ ] Add `embeddings` table to `initSchema()` in `GraphStore.java` — append inside the existing `try (Statement stmt ...)` block, after the last `stmt.execute(...)` call:

```java
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS embeddings (
                    node_id TEXT PRIMARY KEY REFERENCES nodes(id) ON DELETE CASCADE,
                    vector BLOB NOT NULL,
                    dim INTEGER NOT NULL
                )""");
```

- [ ] Add import and two new methods to `GraphStore.java` — add after the `searchLike` method:

```java
    // ---- Embedding operations ----

    public void upsertEmbedding(String nodeId, float[] vector) {
        byte[] blob = com.codenavigator.embedding.VectorCodec.toBytes(vector);
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT OR REPLACE INTO embeddings (node_id, vector, dim) VALUES (?, ?, ?)""")) {
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
```

- [ ] Run and confirm PASS:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.graph.GraphStoreTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`.

- [ ] Commit:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && git add src/main/java/com/codenavigator/graph/GraphStore.java src/test/java/com/codenavigator/graph/GraphStoreTest.java && git commit -m "$(cat <<'EOF'
feat(graph): add embeddings table with upsert and streamAll methods

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: EmbeddingProvider interface + NoopEmbeddingProvider + cosine() pure function

**Files:** `EmbeddingProvider.java`, `NoopEmbeddingProvider.java`, `EmbeddingProviderTest.java`, `RrfFusionTest.java` (cosine part)

- [ ] Write the failing tests:

```java
// src/test/java/com/codenavigator/embedding/EmbeddingProviderTest.java
package com.codenavigator.embedding;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingProviderTest {

    @Test
    void noop_returnsEmptyArray() {
        EmbeddingProvider provider = new NoopEmbeddingProvider();
        float[] result = provider.embed("anything");
        assertThat(result).isEmpty();
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
        float[] a = {1.0f, 0.0f};
        float[] b = {0.0f, 1.0f};
        assertThat(SearchService.cosine(a, b)).isCloseTo(0.0f, within(1e-6f));
    }

    @Test
    void cosine_identicalVectors_returnsOne() {
        float[] a = {3.0f, 4.0f};
        assertThat(SearchService.cosine(a, a)).isCloseTo(1.0f, within(1e-6f));
    }

    @Test
    void cosine_oppositeVectors_returnsNegativeOne() {
        float[] a = {1.0f, 0.0f};
        float[] b = {-1.0f, 0.0f};
        assertThat(SearchService.cosine(a, b)).isCloseTo(-1.0f, within(1e-6f));
    }

    @Test
    void cosine_emptyOrMismatchedDim_returnsZero() {
        assertThat(SearchService.cosine(new float[0], new float[0])).isEqualTo(0.0f);
        assertThat(SearchService.cosine(new float[]{1.0f}, new float[]{1.0f, 2.0f})).isEqualTo(0.0f);
    }
}
```

- [ ] Run and confirm FAIL:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.embedding.EmbeddingProviderTest" --tests "com.codenavigator.search.RrfFusionTest" 2>&1 | tail -20
```
Expected: compilation error.

- [ ] Implement `EmbeddingProvider`:

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

- [ ] Implement `NoopEmbeddingProvider`:

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

- [ ] Add `cosine()` static method to `SearchService.java` — append after `extractKeywords`:

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

- [ ] Run and confirm PASS:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.embedding.EmbeddingProviderTest" --tests "com.codenavigator.search.RrfFusionTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`.

- [ ] Commit:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && git add src/main/java/com/codenavigator/embedding/EmbeddingProvider.java src/main/java/com/codenavigator/embedding/NoopEmbeddingProvider.java src/main/java/com/codenavigator/search/SearchService.java src/test/java/com/codenavigator/embedding/EmbeddingProviderTest.java src/test/java/com/codenavigator/search/RrfFusionTest.java && git commit -m "$(cat <<'EOF'
feat(embedding): add EmbeddingProvider interface, NoopEmbeddingProvider, and cosine() pure function

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: RRF fusion pure function

**Files:** `SearchService.java`, `RrfFusionTest.java`

- [ ] Add failing tests to `RrfFusionTest.java`:

```java
    @Test
    void rrf_singleList_returnsInOrder() {
        List<String> list = List.of("a", "b", "c");
        List<String> result = SearchService.rrf(60, list);
        assertThat(result).containsExactly("a", "b", "c");
    }

    @Test
    void rrf_twoLists_itemInBothRanksHigher() {
        // "shared" appears rank-1 in both lists => high combined RRF score
        List<String> fts  = List.of("shared", "only-fts-1", "only-fts-2");
        List<String> vec  = List.of("shared", "only-vec-1", "only-vec-2");
        List<String> result = SearchService.rrf(60, fts, vec);
        assertThat(result.get(0)).isEqualTo("shared");
    }

    @Test
    void rrf_emptyList_ignoredGracefully() {
        List<String> result = SearchService.rrf(60, List.of(), List.of("x", "y"));
        assertThat(result).containsExactly("x", "y");
    }

    @Test
    void rrf_deduplicatesAcrossLists() {
        List<String> a = List.of("x", "y");
        List<String> b = List.of("y", "z");
        List<String> result = SearchService.rrf(60, a, b);
        assertThat(result).doesNotHaveDuplicates();
        assertThat(result).containsExactlyInAnyOrder("x", "y", "z");
    }
```

Also add import at top of `RrfFusionTest.java`:
```java
import java.util.List;
```

- [ ] Run and confirm FAIL:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.search.RrfFusionTest" 2>&1 | tail -20
```
Expected: compilation error (`rrf` method does not exist).

- [ ] Add `rrf()` static method to `SearchService.java` — append after `cosine()`:

```java
    /**
     * Reciprocal Rank Fusion over an arbitrary number of ranked ID lists.
     * Score for each ID = sum_over_lists( 1 / (k + rank_1based) ).
     * Returns IDs in descending score order, deduplicated.
     *
     * @param k  RRF smoothing constant (60 is standard)
     * @param lists  ranked lists of node IDs (best first)
     */
    @SafeVarargs
    static List<String> rrf(int k, List<String>... lists) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (List<String> list : lists) {
            for (int i = 0; i < list.size(); i++) {
                String id = list.get(i);
                scores.merge(id, 1.0 / (k + i + 1), Double::sum);
            }
        }
        return scores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }
```

Also add `import java.util.Map;` to `SearchService.java` imports if not already present.

- [ ] Run and confirm PASS:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.search.RrfFusionTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`.

- [ ] Commit:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && git add src/main/java/com/codenavigator/search/SearchService.java src/test/java/com/codenavigator/search/RrfFusionTest.java && git commit -m "$(cat <<'EOF'
feat(search): add rrf() Reciprocal Rank Fusion pure function

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: SearchService hybrid path — inject EmbeddingProvider

**Files:** `SearchService.java`, `SearchServiceTest.java`

- [ ] Write the failing tests (add to existing `SearchServiceTest.java`):

```java
    // New field and imports needed:
    // import com.codenavigator.embedding.EmbeddingProvider;
    // import com.codenavigator.embedding.NoopEmbeddingProvider;

    @Test
    void searchWithNoop_behaviorIdenticalToFtsOnly() {
        // Default constructor (no provider) and Noop constructor should give same results
        var noopService = new SearchService(store, new GraphTraversal(store), new NoopEmbeddingProvider());
        var defaultService = new SearchService(store, new GraphTraversal(store));

        var withNoop = noopService.search("PurchaseOrder");
        var withDefault = defaultService.search("PurchaseOrder");

        assertThat(withNoop).extracting(Node::id)
            .containsExactlyInAnyOrderElementsOf(withDefault.stream().map(Node::id).toList());
    }

    @Test
    void searchWithFakeProvider_topResultChanges() {
        // Store embeddings manually so the fake provider's query vector dominates ranking
        // "WorkerController" gets a vector perfectly aligned with the query
        store.upsertEmbedding("com.WorkerController", new float[]{1.0f, 0.0f});
        store.upsertEmbedding("com.PurchaseOrder",    new float[]{0.0f, 1.0f});
        store.upsertEmbedding("com.CreatePurchaseOrderCommand", new float[]{0.0f, 1.0f});

        // Query vector points at WorkerController
        EmbeddingProvider fakeProvider = text -> new float[]{1.0f, 0.0f};

        var hybridService = new SearchService(store, new GraphTraversal(store), fakeProvider);
        // Query that FTS would not rank WorkerController first (no "PurchaseOrder" in its snippet)
        var results = hybridService.search("PurchaseOrder");

        // With hybrid, WorkerController should be promoted due to vector similarity
        assertThat(results).extracting(Node::name).contains("WorkerController");
    }
```

- [ ] Run and confirm FAIL:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.search.SearchServiceTest" 2>&1 | tail -20
```
Expected: compilation error (new constructor overload does not exist).

- [ ] Rewrite `SearchService.java` to add the provider field and hybrid `search` method. The class should compile with both existing constructors and the new test cases. Replace the class body:

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
     * Hybrid search: runs FTS5 (with LIKE fallback) and, when embeddings are
     * available, cosine-ranks stored vectors against the query embedding,
     * then fuses both ranked lists via RRF. When the provider returns an empty
     * vector (Noop or unavailable), this degrades to the original FTS5 path.
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
            // No embeddings — original behaviour
            return new ArrayList<>(ftsOrdered.values());
        }

        // Build cosine-ranked list from stored embeddings
        List<Map.Entry<String, Float>> scored = new ArrayList<>();
        store.streamAllEmbeddings((nodeId, vector) -> {
            float sim = cosine(queryVec, vector);
            if (sim > 0.0f) scored.add(Map.entry(nodeId, sim));
        });
        scored.sort(Map.Entry.<String, Float>comparingByValue().reversed());
        List<String> vecRanked = scored.stream().map(Map.Entry::getKey).collect(Collectors.toList());

        List<String> ftsRanked = new ArrayList<>(ftsOrdered.keySet());
        List<String> fused = rrf(60, ftsRanked, vecRanked);

        // Materialise Node objects in RRF order; include any extras from FTS not in fused
        Map<String, Node> allKnown = new LinkedHashMap<>(ftsOrdered);
        // also include nodes that only appeared in vector results
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
     * 1. Extract keywords
     * 2. FTS5 search each keyword with prefix matching
     * 3. Expand hits via chain tracing
     * 4. Deduplicate, return
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
     * Reciprocal Rank Fusion over an arbitrary number of ranked ID lists.
     * Score for each ID = sum_over_lists( 1 / (k + rank_1based) ).
     * Returns IDs in descending score order, deduplicated.
     *
     * @param k  RRF smoothing constant (60 is standard)
     * @param lists  ranked lists of node IDs (best first)
     */
    @SafeVarargs
    static List<String> rrf(int k, List<String>... lists) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (List<String> list : lists) {
            for (int i = 0; i < list.size(); i++) {
                String id = list.get(i);
                scores.merge(id, 1.0 / (k + i + 1), Double::sum);
            }
        }
        return scores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }
}
```

NOTE: The full class replaces the prior incremental edits from T3 and T4 — this is the canonical final version of `SearchService`.

- [ ] Run and confirm PASS:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.search.SearchServiceTest" --tests "com.codenavigator.search.RrfFusionTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`.

- [ ] Run full test suite to confirm no regression:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test 2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL`, all prior tests still green.

- [ ] Commit:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && git add src/main/java/com/codenavigator/search/SearchService.java src/test/java/com/codenavigator/search/SearchServiceTest.java && git commit -m "$(cat <<'EOF'
feat(search): hybrid RRF search with injected EmbeddingProvider; Noop degrades to FTS5-only

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: OllamaEmbeddingProvider — HTTP client with seam for testing

**Files:** `OllamaEmbeddingProvider.java`, `EmbeddingProviderTest.java`

The test strategy avoids WireMock by injecting a `java.net.http.HttpClient` factory (`Function<Void, HttpClient>`) or more simply by using a package-private constructor that accepts a pre-built `HttpClient`. For tests, we provide a `HttpClient` backed by an `HttpHandler` registered on an embedded `com.sun.net.httpserver.HttpServer` (JDK built-in, no extra dependency).

- [ ] Add failing tests to `EmbeddingProviderTest.java`:

```java
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;

@Test
void ollama_returnsEmbeddingFromStubServer() throws IOException {
    // Start a stub HTTP server on a random port
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    int port = server.getAddress().getPort();
    server.createContext("/api/embeddings", exchange -> {
        String response = "{\"embedding\":[0.1,0.2,0.3]}";
        byte[] body = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
    });
    server.start();
    try {
        OllamaEmbeddingProvider provider = new OllamaEmbeddingProvider(
            "http://localhost:" + port, "nomic-embed-text",
            HttpClient.newHttpClient());
        float[] result = provider.embed("hello world");
        assertThat(result).containsExactly(0.1f, 0.2f, 0.3f);
    } finally {
        server.stop(0);
    }
}

@Test
void ollama_returnsEmptyArrayWhenServerUnreachable() {
    OllamaEmbeddingProvider provider = new OllamaEmbeddingProvider(
        "http://localhost:1", "nomic-embed-text",
        HttpClient.newHttpClient());
    // Port 1 is unreachable; should return empty gracefully
    assertThat(provider.embed("hello")).isEmpty();
}
```

Also add these additional imports to `EmbeddingProviderTest.java`:
```java
import com.codenavigator.embedding.OllamaEmbeddingProvider;
```

- [ ] Run and confirm FAIL:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.embedding.EmbeddingProviderTest" 2>&1 | tail -20
```
Expected: compilation error.

- [ ] Implement `OllamaEmbeddingProvider`:

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
 * Returns an empty array on any error (network, timeout, parse) so the
 * caller degrades gracefully to FTS5-only search.
 */
public final class OllamaEmbeddingProvider implements EmbeddingProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final String model;
    private final HttpClient httpClient;

    /** Production constructor — uses a default HttpClient. */
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

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) return new float[0];

            OllamaResponse parsed = MAPPER.readValue(response.body(), OllamaResponse.class);
            if (parsed.embedding() == null || parsed.embedding().isEmpty()) return new float[0];

            float[] result = new float[parsed.embedding().size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = parsed.embedding().get(i).floatValue();
            }
            return result;
        } catch (Exception e) {
            // Network error, timeout, JSON parse error — degrade silently
            return new float[0];
        }
    }

    private record OllamaRequest(String model, String prompt) {}
    private record OllamaResponse(List<Double> embedding) {}
}
```

- [ ] Run and confirm PASS:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.embedding.EmbeddingProviderTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`.

- [ ] Run full test suite:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test 2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL`.

- [ ] Commit:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && git add src/main/java/com/codenavigator/embedding/OllamaEmbeddingProvider.java src/test/java/com/codenavigator/embedding/EmbeddingProviderTest.java && git commit -m "$(cat <<'EOF'
feat(embedding): add OllamaEmbeddingProvider with injectable HttpClient seam for testing

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Opt-in wiring in ProjectIndexer guarded by CODE_NAVIGATOR_EMBEDDINGS

**Files:** `ProjectIndexer.java`, `CodeNavigatorApplication.java`

- [ ] Write failing test (new file):

```java
// src/test/java/com/codenavigator/indexer/ProjectIndexerEmbeddingTest.java
package com.codenavigator.indexer;

import com.codenavigator.embedding.EmbeddingProvider;
import com.codenavigator.graph.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class ProjectIndexerEmbeddingTest {

    @TempDir Path tempDir;
    @TempDir Path projectDir;
    private GraphStore store;

    @BeforeEach
    void setUp() {
        store = new GraphStore(tempDir.resolve("test.db"));
    }

    @AfterEach
    void tearDown() { store.close(); }

    @Test
    void embeddingProviderCalledForEachNodeWhenEnabled() throws Exception {
        // Create a minimal Java file so the indexer has nodes to process
        Path src = projectDir.resolve("src");
        src.toFile().mkdirs();
        Path file = src.resolve("Dummy.java");
        java.nio.file.Files.writeString(file,
            "package com.example;\npublic class Dummy {}");

        AtomicInteger callCount = new AtomicInteger(0);
        EmbeddingProvider countingProvider = text -> {
            callCount.incrementAndGet();
            return new float[]{0.1f, 0.2f};
        };

        ProjectIndexer indexer = new ProjectIndexer(store, countingProvider);
        indexer.indexFull(projectDir);

        assertThat(callCount.get()).isGreaterThan(0);

        // Embeddings should be persisted
        List<float[]> stored = new ArrayList<>();
        store.streamAllEmbeddings((id, v) -> stored.add(v));
        assertThat(stored).isNotEmpty();
    }

    @Test
    void noopProviderDoesNotStoreEmbeddings() throws Exception {
        Path src = projectDir.resolve("src");
        src.toFile().mkdirs();
        Path file = src.resolve("Dummy.java");
        java.nio.file.Files.writeString(file,
            "package com.example;\npublic class Dummy {}");

        ProjectIndexer indexer = new ProjectIndexer(store); // default Noop
        indexer.indexFull(projectDir);

        List<float[]> stored = new ArrayList<>();
        store.streamAllEmbeddings((id, v) -> stored.add(v));
        assertThat(stored).isEmpty();
    }
}
```

- [ ] Run and confirm FAIL:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.indexer.ProjectIndexerEmbeddingTest" 2>&1 | tail -20
```
Expected: compilation error (new constructor not present).

- [ ] Modify `ProjectIndexer.java` — add `EmbeddingProvider` field and new constructor overloads; add embedding step inside `indexFull` and `reindexChangedJavaFiles`:

Add at top of class (after existing fields):
```java
    private final com.codenavigator.embedding.EmbeddingProvider embeddingProvider;
```

Change existing constructor:
```java
    public ProjectIndexer(GraphStore store) {
        this(store, new com.codenavigator.embedding.NoopEmbeddingProvider());
    }

    public ProjectIndexer(GraphStore store, com.codenavigator.embedding.EmbeddingProvider embeddingProvider) {
        this.store = store;
        this.embeddingProvider = embeddingProvider;
        this.projectDetector = new ProjectDetector();
        this.tsIndexer = new TypeScriptIndexer();
        this.methodExtractor = new MethodExtractor();
    }
```

Inside `indexFull`, after `store.saveNode(nodeWithTime)` in the Phase 1 node loop, add the embedding step:
```java
                    store.saveNode(nodeWithTime);
                    // Embedding step (skipped silently when provider is Noop or returns empty)
                    embedNode(nodeWithTime);
```

Add private helper method:
```java
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

Also call `embedNode` in `reindexChangedJavaFiles` after `store.saveNode(nodeWithTime)`:
```java
                    store.saveNode(nodeWithTime);
                    embedNode(nodeWithTime);
```

- [ ] Modify `CodeNavigatorApplication.java` — add provider selection based on env flag. Locate where `ProjectIndexer` and `SearchService` are instantiated and wire the provider:

Find the application wiring (typically in `main()` or a factory method). Add:
```java
        // Embedding provider: opt-in via CODE_NAVIGATOR_EMBEDDINGS=1
        com.codenavigator.embedding.EmbeddingProvider embeddingProvider;
        String embFlag = System.getenv("CODE_NAVIGATOR_EMBEDDINGS");
        if ("1".equals(embFlag) || "true".equalsIgnoreCase(embFlag)) {
            String ollamaUrl = System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");
            String ollamaModel = System.getenv().getOrDefault("OLLAMA_MODEL", "nomic-embed-text");
            embeddingProvider = new com.codenavigator.embedding.OllamaEmbeddingProvider(ollamaUrl, ollamaModel);
        } else {
            embeddingProvider = new com.codenavigator.embedding.NoopEmbeddingProvider();
        }
```

Pass `embeddingProvider` to `ProjectIndexer` and `SearchService` where they are constructed.

NOTE: Read `CodeNavigatorApplication.java` before editing to identify the exact insertion points. The class is not listed above but follows the pattern of building `GraphStore`, `GraphTraversal`, `SearchService`, `ProjectIndexer`, and `CodeNavigatorMcpServer`.

- [ ] Run and confirm PASS:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test --tests "com.codenavigator.indexer.ProjectIndexerEmbeddingTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`.

- [ ] Run full test suite:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test 2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL`, all tests green.

- [ ] Build shadow JAR:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew shadowJar 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`.

- [ ] Commit:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && git add src/main/java/com/codenavigator/indexer/ProjectIndexer.java src/main/java/com/codenavigator/CodeNavigatorApplication.java src/test/java/com/codenavigator/indexer/ProjectIndexerEmbeddingTest.java && git commit -m "$(cat <<'EOF'
feat(indexer): opt-in embedding indexing guarded by CODE_NAVIGATOR_EMBEDDINGS env flag

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

| Spec Item | Covered By |
|-----------|-----------|
| `EmbeddingProvider` interface with `embed(String text) -> float[]` | T3 — interface declared; signature consistent across T5, T6, T7 |
| `OllamaEmbeddingProvider` — POST to `http://localhost:11434/api/embeddings` via `java.net.http.HttpClient`, Jackson parse | T6 — full implementation; no new Gradle dependency |
| `NoopEmbeddingProvider` — returns empty, used when embeddings disabled or Ollama unreachable | T3, T6 (unreachable test), T7 (default constructor path) |
| `embeddings` table: `node_id TEXT PK REFERENCES nodes(id) ON DELETE CASCADE`, `vector BLOB`, `dim INTEGER` | T2 — DDL in `initSchema()`; cascade tested in `deleteNode_cascadesEmbedding` |
| `float[]` <-> `byte[]` as little-endian BLOB | T1 — `VectorCodec.toBytes` / `toFloats`; `littleEndian_knownBytes` test verifies byte order |
| `GraphStore.upsertEmbedding` + `streamAllEmbeddings` | T2 |
| Opt-in indexing via `CODE_NAVIGATOR_EMBEDDINGS=1` or config | T7 — env flag wired in `CodeNavigatorApplication`; default Noop path tested |
| Embedding text = `name + qualifiedName + codeSnippet` | T7 — `embedNode()` private helper builds that string |
| Cosine similarity | T3 — `cosine()` pure static; orthogonal/identical/opposite/empty cases tested |
| RRF fusion of FTS5 + vector ranked lists | T4 — `rrf()` pure static; dedup, cross-list promotion, empty list tested |
| Hybrid `search()` path — Noop equals FTS5-only | T5 — `searchWithNoop_behaviorIdenticalToFtsOnly` |
| Hybrid `search()` path — fake provider changes ranking | T5 — `searchWithFakeProvider_topResultChanges` |
| No new Gradle dependency | Confirmed: `java.net.http.HttpClient` is JDK 11+; Jackson already at 2.18.2; `com.sun.net.httpserver` is JDK built-in (test only) |
| `cg_search` output shape unchanged | T5 — `SearchService.search()` still returns `List<Node>`; `handleCgSearch` in `CodeNavigatorMcpServer` unchanged |
| No network required in tests | All tests use `NoopEmbeddingProvider`, hand-crafted vectors, or an in-process JDK `HttpServer` stub |
