# Semantic (Hybrid) Search — Design Spec

**Date:** 2026-06-21
**Status:** Approved design — ready for implementation planning
**Feature:** Add opt-in semantic vector search to `cg_search` and `cg_context`, fused with the existing FTS5 keyword search via Reciprocal Rank Fusion (RRF), plus a benchmark that proves the relevance/token delta.

---

## 1. Motivation

`code-navigator` search today is **lexical only**: `SearchService` runs SQLite FTS5 with a LIKE fallback (`SearchService.java`). That means a query for `"auth"` cannot find `login`, `credential`, or `validateToken` — words that share *meaning* but no characters. `cg_context` (the "smart context builder for a task description") is the most affected: it takes natural-language tasks like *"where does request validation happen"* yet matches them with pure keyword extraction.

### Competitive validation
A scan of the MCP/code-search field (Reddit r/mcp, r/ClaudeAI, r/ClaudeCode, r/LocalLLaMA, plus web) shows hybrid lexical+vector search with **local** embeddings is the established pattern for token-efficient code MCPs:

- **CocoSearch** — indexes code, embeds via **Ollama**, fuses vector + keyword with **RRF**. Near-identical to this design (it uses Postgres/pgvector; we use SQLite BLOBs).
- **codanna** (cited as best-in-class for code semantic search) — tree-sitter + a **bundled FastEmbed model** (all-MiniLM-L6-v2, 384-dim, no API key) + Tantivy full-text + IVFFlat vectors; embeds **doc-comments**.
- **Qdrant MCP**, **semantic-context-mcp** — hybrid semantic+keyword, Ollama/OpenAI pluggable.
- **codebase-memory-mcp** — on-device embeddings, no Ollama.

Two confirmations from that field:
1. "Degrade to lexical-only when the embedder is down" is the standard safety behaviour — matching our Noop path.
2. The strongest *local* tools either use Ollama (CocoSearch, Qdrant) or a bundled on-device model (codanna). We ship Ollama first and keep the seam open for a bundled model later.

### Goal
Add opt-in local vector embeddings so `cg_search` and `cg_context` rank by semantic similarity fused with FTS5 keyword scoring via RRF, **while never regressing the no-embeddings path**, and prove the improvement with a deterministic benchmark.

---

## 2. Scope

**In scope**
- Pluggable `EmbeddingProvider` seam with `OllamaEmbeddingProvider` (the one shipped impl) and `NoopEmbeddingProvider`.
- Embedding storage in SQLite; little-endian `float[]` BLOBs.
- Hybrid RRF ranking in **both** `cg_search` (`search()`) and `cg_context` (`contextSearch()`).
- Opt-in via `CODE_NAVIGATOR_EMBEDDINGS=1`; graceful degrade to today's behaviour when off/unreachable.
- Benchmark proof: a deterministic recall@k fixture test, plus an optional `semantic-context` benchmark row.
- An embedding-coverage line in `cg_status`.

**Out of scope (deliberately deferred — YAGNI)**
- A bundled on-device ONNX model. The `EmbeddingProvider` interface makes this a future drop-in (`OnnxEmbeddingProvider`) with no rework.
- Approximate-nearest-neighbour indexes (HNSW/IVF). Linear cosine scan over stored vectors is adequate at current graph sizes; revisit only if measured slow.
- Re-ranking models, OpenAI/cloud providers, multi-vector chunking.

---

## 3. Architecture

A pluggable embedding layer that is **opt-in and degrades safely**.

```
                    CODE_NAVIGATOR_EMBEDDINGS=1 ?
                          /            \
                       yes              no
                        |                |
            OllamaEmbeddingProvider   NoopEmbeddingProvider
            (HTTP -> localhost:11434)  (returns float[0])
                        \                /
                         EmbeddingProvider
                                |
        index time:  ProjectIndexer.embedNode() -> GraphStore.upsertEmbedding(BLOB)
        query time:  SearchService -> embed(query) -> cosine vs streamAllEmbeddings()
                                |                            |
                          FTS5 ranked list           vector ranked list
                                \            RRF             /
                                 fused, deduped result list
```

### Components

| Component | Responsibility |
|---|---|
| `embedding/EmbeddingProvider` | Interface: `float[] embed(String text)`; empty array ⇒ "unavailable". The pluggable seam. |
| `embedding/NoopEmbeddingProvider` | Always returns `float[0]`. Used when embeddings are off or the backend is unreachable. |
| `embedding/OllamaEmbeddingProvider` | POSTs to `{baseUrl}/api/embeddings` (default `http://localhost:11434`, model `nomic-embed-text`) via JDK `HttpClient`, parses with existing Jackson. **Returns `float[0]` on any error** (network/timeout/parse/non-200). |
| `embedding/VectorCodec` | `byte[] toBytes(float[])` / `float[] toFloats(byte[])`, little-endian, 4 bytes per float. |
| `graph/GraphStore` (modify) | New `embeddings` table; `upsertEmbedding(nodeId, float[])`; `streamAllEmbeddings(BiConsumer<String,float[]>)`. Cascade-delete with the owning node. |
| `search/SearchService` (modify) | Injectable `EmbeddingProvider`; pure static `cosine(float[],float[])` and `rrf(int k, List<String>...)`; hybrid `search()` **and** hybrid `contextSearch()`. |
| `indexer/ProjectIndexer` (modify) | Optional `EmbeddingProvider`; after `saveNode`, `embedNode()` upserts a vector when the provider yields one. Applies in both `indexFull` and `reindexChangedJavaFiles`. |
| `benchmark/BenchmarkRunner` (modify) | Optional 4th `semantic-context` row when embeddings enabled. |
| `CodeNavigatorApplication` (modify) | Select provider from `CODE_NAVIGATOR_EMBEDDINGS`; wire into `ProjectIndexer` + `SearchService`. |
| `cli/StatusCommand` / `cg_status` (modify) | Add "embeddings: N/M nodes" coverage line. |

### Tech stack
Java 21, Gradle shadowJar, SQLite JDBC 3.47.2.0, Jackson 2.18.2 (already present), `java.net.http.HttpClient` (JDK built-in — **no new dependency**), JUnit 5 + AssertJ, `@TempDir` SQLite and an in-process `com.sun.net.httpserver.HttpServer` for tests.

---

## 4. Data model

New SQLite table:

```sql
CREATE TABLE IF NOT EXISTS embeddings (
    node_id TEXT PRIMARY KEY REFERENCES nodes(id) ON DELETE CASCADE,
    vector  BLOB NOT NULL,
    dim     INTEGER NOT NULL
);
```

- One vector per node, keyed by `node_id`. `ON DELETE CASCADE` keeps embeddings in lockstep with nodes (verified by a `deleteNode_cascadesEmbedding` test).
- `dim` is stored to detect mismatches; `cosine()` returns `0.0` on differing dimensions (e.g. after a model change) rather than throwing.
- **Embedding text** per node: `name + " " + qualifiedName + (codeSnippet != null ? " " + codeSnippet : "")`. (`Node` is a record with exactly these accessors.) Note: codanna embeds doc-comments; we embed identifier + snippet because that is what the graph already stores. Doc-comment embedding is a possible future refinement, not in scope.

---

## 5. Behaviour

### 5.1 `cg_search` (`SearchService.search`)
1. Run FTS5 (`+ "*"` prefix) and LIKE fallback as today → ordered FTS list.
2. `queryVec = embeddingProvider.embed(query)`.
3. **If `queryVec` is empty** (Noop / unreachable) → return the FTS list unchanged. *This is byte-for-byte today's behaviour.*
4. Otherwise: cosine-score `queryVec` against all stored vectors (`streamAllEmbeddings`), keep `sim > 0`, sort desc → vector-ranked list.
5. `rrf(60, ftsRanked, vecRanked)` → fused, deduped order.
6. Materialise `Node`s in fused order (including nodes that appear only in the vector list via `findNodeById`).

### 5.2 `cg_context` (`SearchService.contextSearch`) — the high-value extension
Today: `extractKeywords(task)` → FTS per keyword → chain-trace expand → dedupe.

New flow (semantic recall feeds the **seed set**, then graph traversal expands as before):
1. `keywords = extractKeywords(task)`; FTS per keyword → `directHits` (**unchanged**).
2. `queryVec = embed(task)` (the *whole* task description, not per-keyword).
3. **If `queryVec` non-empty:** add the top-N cosine hits (N small, e.g. 10) into `directHits`.
4. `expanded = directHits ∪ traceChain(each directHit)` (**unchanged**).
5. Return deduped `expanded`.

**Safety invariant:** with `NoopEmbeddingProvider`, `queryVec` is empty, so step 3 is a no-op and the seed set — therefore the entire output — is identical to today. Enforced by a test that compares Noop output to the legacy path.

### 5.3 Indexing
- When the provider yields a non-empty vector, `ProjectIndexer.embedNode()` calls `store.upsertEmbedding(node.id(), vec)` right after `saveNode`, in both full and incremental reindex paths.
- **Backfill:** projects indexed before the flag was enabled have no vectors until a reindex. `cg_status` surfaces coverage (e.g. `embeddings: 0/412 nodes`) so the gap is visible; enabling the flag and reindexing populates it.

---

## 6. Error handling & resilience

| Failure | Handling |
|---|---|
| Ollama not installed / down / timeout | `OllamaEmbeddingProvider.embed` catches, returns `float[0]` → search degrades to FTS5. No error surfaced to the agent. |
| Non-200 / malformed JSON | Same — empty vector, FTS path. |
| Embedding throws during indexing | `embedNode` swallows — **indexing must never break** because embeddings failed. |
| Dimension mismatch (model changed) | `cosine` returns `0.0`; mismatched vectors simply don't contribute. (A future migration could clear `embeddings` on model change.) |
| Flag off | `NoopEmbeddingProvider` everywhere; zero behaviour change, zero new runtime cost. |

---

## 7. Benchmark proof

The field's lesson: a token-reduction claim is dismissed unless paired with a quality measurement. Two deterministic pieces:

1. **Recall@k fixture test** (`search` test): a small labelled fixture (`query → expected symbol`) using a **stub provider** with hand-crafted vectors. Asserts the hybrid path ranks the expected symbol within top-k on a *synonym* query where FTS-only misses it. Deterministic, no network, no Ollama.
2. **Optional `semantic-context` benchmark row** in `BenchmarkRunner`: runs only when embeddings are enabled; for a fixed NL query, compares tokens to reach the relevant set via hybrid `cg_context` vs FTS-only-then-read-files. When embeddings are off, the row is rendered as `skipped (embeddings off)` — never a faked number. This slots beside the existing three scenarios (`whole-project`, `symbol-impact`, `compact-export`).

---

## 8. Testing strategy

Everything deterministic and offline:
- `VectorCodec` round-trip + known little-endian bytes.
- `GraphStore`: upsert/replace/stream/cascade-delete of embeddings.
- `cosine()` and `rrf()` pure-function unit tests (orthogonal/identical/opposite/empty; dedupe, cross-list promotion, empty-list).
- `SearchService`: **Noop == FTS5-only** (the regression guard); fake provider changes ranking; hybrid `contextSearch` adds a semantically-related seed that pure keywords miss.
- `OllamaEmbeddingProvider`: in-process JDK `HttpServer` stub for the happy path; unreachable-port returns empty.
- `ProjectIndexer`: counting provider proves `embed` is called per node and vectors persist; Noop stores nothing.

No test requires a network connection or a running Ollama.

---

## 9. Configuration summary

| Env var | Default | Meaning |
|---|---|---|
| `CODE_NAVIGATOR_EMBEDDINGS` | unset (off) | `1`/`true` enables embeddings. |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama server URL. |
| `OLLAMA_MODEL` | `nomic-embed-text` | Embedding model; swap for any local embedding model. |

User setup when enabling: `curl -fsSL https://ollama.com/install.sh | sh`, then `ollama pull nomic-embed-text`, then set the flag and reindex.

**Model choice & the one-model rule.** Use an Ollama *embedding* model (not a chat LLM): `nomic-embed-text` (768-dim, default), `all-minilm` (384-dim, tiny/fast), `mxbai-embed-large` (1024-dim, higher quality), `embeddinggemma`/`qwen3-embedding` (Matryoshka-truncatable). Every node vector **and** the query must use the *same* model, because dimensions must match — `cosine()` returns `0.0` on a mismatch, so mixing models silently disables semantic hits. **Therefore changing `OLLAMA_MODEL` requires clearing the `embeddings` table and reindexing.** A future migration may auto-clear embeddings when the configured model changes; until then it is a manual reindex.

---

## 10. Decisions & rationale

- **Hybrid + RRF, not vector-only.** Pure vector search regresses exact-symbol lookups; RRF fusion (k=60, standard) keeps FTS strengths and adds semantic recall. Matches CocoSearch/codanna.
- **Ollama first, pluggable seam.** Lowest-risk, zero new JAR deps, and it's what the user wants (local LLM). A bundled ONNX model is a future drop-in via the interface — built only if no-install ever becomes a real need.
- **Opt-in, off by default.** Ships dormant; no Ollama needed to build, test, or run with the flag off. Removes any adoption risk.
- **Extend `cg_context`, not just `cg_search`.** `cg_context` is the bigger token-saver and the most natural-language input — the place semantic recall matters most.
- **Prove it deterministically.** Recall@k on a fixture demonstrates the quality delta without an LLM judge or network.

---

## 11. File-level change map (for the implementation plan)

| Status | File |
|---|---|
| Create | `embedding/EmbeddingProvider.java`, `NoopEmbeddingProvider.java`, `OllamaEmbeddingProvider.java`, `VectorCodec.java` |
| Modify | `graph/GraphStore.java` (table + upsert/stream) |
| Modify | `search/SearchService.java` (provider, `cosine`, `rrf`, hybrid `search` + `contextSearch`) |
| Modify | `indexer/ProjectIndexer.java` (`embedNode` in full + incremental) |
| Modify | `CodeNavigatorApplication.java` (provider wiring from env) |
| Modify | `cli/StatusCommand.java` (embedding-coverage line) |
| Modify | `benchmark/BenchmarkRunner.java` (optional `semantic-context` row) |
| Create/Modify tests | `VectorCodecTest`, `EmbeddingProviderTest`, `RrfFusionTest`, `GraphStoreTest`, `SearchServiceTest`, `ProjectIndexerEmbeddingTest`, benchmark fixture |

A detailed TDD task breakdown for the foundation (Tasks 1–7) already exists in the prior plan and will be regenerated/extended (Tasks 8–9 for `cg_context` and benchmark) by the implementation-planning step.
