# Git Co-Change Coupling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mine git history for files that change together, persist co-change pair counts in SQLite, and surface coupling via a new `cg_coupling` MCP tool plus annotations on `cg_impact` output.

**Architecture:** A new `GitHistoryAnalyzer` class uses JGit to walk the last N commits, collecting changed-file sets per commit and incrementing a pair counter for every unique file pair; results are persisted in a new `co_change` table in `GraphStore`. `ProjectIndexer.indexFull` calls the analyzer after the edge-extraction phase, guarded by a try/catch so projects without `.git` silently skip. The `cg_coupling` MCP tool queries `topCoupled(file, limit)` and `handleCgImpact` annotates each impact result line with its co-change count when > 0.

**Tech Stack:** Java 21, Gradle shadowJar, JGit (`org.eclipse.jgit:org.eclipse.jgit`), SQLite JDBC, JUnit 5 + AssertJ, MCP SDK 1.1.0.

---

## File Structure

| Action | Path | Responsibility |
|--------|------|----------------|
| Modify | `build.gradle` | Add JGit dependency |
| Modify | `src/main/java/com/codenavigator/graph/GraphStore.java` | Add `co_change` table, `upsertCoChange`, `topCoupled`, `CoChangePair` record |
| Create | `src/main/java/com/codenavigator/git/GitHistoryAnalyzer.java` | Walk JGit commits, accumulate pair counts, write to GraphStore |
| Modify | `src/main/java/com/codenavigator/indexer/ProjectIndexer.java` | Call `GitHistoryAnalyzer.analyze` at end of `indexFull`, non-git skip guard |
| Modify | `src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java` | Register `cg_coupling` tool, add `handleCgCoupling`, annotate `handleCgImpact` |
| Modify | `src/test/java/com/codenavigator/graph/GraphStoreTest.java` | Tests for `co_change` schema, upsert, and `topCoupled` |
| Create | `src/test/java/com/codenavigator/git/GitHistoryAnalyzerTest.java` | Tests using a temp JGit repo; asserts pair counts |
| Modify | `src/test/java/com/codenavigator/mcp/CodeNavigatorMcpServerTest.java` | Tests for `handleCgCoupling` and annotated impact lines |

---

### Task 1: Add JGit dependency and verify build

**Files:**
- Modify `build.gradle` (after line 34, inside `dependencies {}`)

- [ ] Open `build.gradle`. In the `dependencies` block, add the JGit line immediately after the last `implementation` entry (currently line 34 `info.picocli:picocli:4.7.6`):

```groovy
    implementation 'org.eclipse.jgit:org.eclipse.jgit:7.2.0.202503040940-r'
```

Full updated `dependencies` block:
```groovy
dependencies {
    implementation 'com.github.javaparser:javaparser-symbol-solver-core:3.26.4'
    implementation 'org.xerial:sqlite-jdbc:3.47.2.0'
    implementation 'io.modelcontextprotocol.sdk:mcp:1.1.0'
    implementation 'com.fasterxml.jackson.core:jackson-databind:2.18.2'
    implementation 'info.picocli:picocli:4.7.6'
    implementation 'org.eclipse.jgit:org.eclipse.jgit:7.2.0.202503040940-r'
    annotationProcessor 'info.picocli:picocli-codegen:4.7.6'

    testImplementation platform('org.junit:junit-bom:5.11.4')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testImplementation 'org.assertj:assertj-core:3.27.3'
}
```

- [ ] Run the build to confirm JGit resolves and the fat JAR compiles:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew shadowJar
```
Expected output ends with:
```
BUILD SUCCESSFUL in ...s
```

- [ ] Commit:
```bash
git -C /home/kamil/Documents/Project/My/tools/mcp/code-navigator add build.gradle && \
git -C /home/kamil/Documents/Project/My/tools/mcp/code-navigator commit -m "build: add JGit dependency for git co-change analysis"
```

---

### Task 2: co_change table + GraphStore upsert/query

**Files:**
- Modify `src/test/java/com/codenavigator/graph/GraphStoreTest.java` (add tests at the end of the class, before closing `}`)
- Modify `src/main/java/com/codenavigator/graph/GraphStore.java` (extend `initSchema`, add `CoChangePair` record, `upsertCoChange`, `topCoupled`)

- [ ] Add tests to `GraphStoreTest.java` (insert before the final `}`):

```java
    // ---- co_change tests ----

    @Test
    void coChangeUpsert_insertsNewPair() {
        store.upsertCoChange("src/A.java", "src/B.java");
        store.upsertCoChange("src/A.java", "src/B.java");
        store.upsertCoChange("src/A.java", "src/B.java");

        var coupled = store.topCoupled("src/A.java", 5);
        assertThat(coupled).hasSize(1);
        assertThat(coupled.get(0).otherFile()).isEqualTo("src/B.java");
        assertThat(coupled.get(0).count()).isEqualTo(3);
    }

    @Test
    void coChangeUpsert_canonicalOrdering() {
        // Insert in both orderings — must accumulate on the same row
        store.upsertCoChange("src/Z.java", "src/A.java");
        store.upsertCoChange("src/A.java", "src/Z.java");

        var fromA = store.topCoupled("src/A.java", 5);
        var fromZ = store.topCoupled("src/Z.java", 5);
        assertThat(fromA).hasSize(1);
        assertThat(fromA.get(0).count()).isEqualTo(2);
        assertThat(fromZ).hasSize(1);
        assertThat(fromZ.get(0).count()).isEqualTo(2);
    }

    @Test
    void coChangeUpsert_topCoupled_limitAndOrdering() {
        // A-B: 3 commits, A-C: 5 commits, A-D: 1 commit
        for (int i = 0; i < 3; i++) store.upsertCoChange("src/A.java", "src/B.java");
        for (int i = 0; i < 5; i++) store.upsertCoChange("src/A.java", "src/C.java");
        store.upsertCoChange("src/A.java", "src/D.java");

        var top2 = store.topCoupled("src/A.java", 2);
        assertThat(top2).hasSize(2);
        assertThat(top2.get(0).otherFile()).isEqualTo("src/C.java");
        assertThat(top2.get(0).count()).isEqualTo(5);
        assertThat(top2.get(1).otherFile()).isEqualTo("src/B.java");
        assertThat(top2.get(1).count()).isEqualTo(3);
    }

    @Test
    void topCoupled_returnsEmptyWhenNoData() {
        var result = store.topCoupled("nonexistent/File.java", 10);
        assertThat(result).isEmpty();
    }
```

- [ ] Run the tests — expect FAIL because the methods do not exist yet:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && \
  ./gradlew test --tests "com.codenavigator.graph.GraphStoreTest.coChangeUpsert_insertsNewPair"
```
Expected failure:
```
FAILED — cannot find symbol: method upsertCoChange(String,String)
```

- [ ] Add the `co_change` table to `GraphStore.initSchema()`. Insert after the last `CREATE INDEX` statement (currently line 84 `idx_nodes_file`):

```java
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS co_change (
                    file_a TEXT NOT NULL,
                    file_b TEXT NOT NULL,
                    count  INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (file_a, file_b),
                    CHECK (file_a < file_b)
                )""");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_co_change_a ON co_change(file_a)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_co_change_b ON co_change(file_b)");
```

- [ ] Add the `CoChangePair` record and the two public methods to `GraphStore.java`. Insert them after the `findTopFanOut` method (currently after line 506), before the `// ---- Aggregate queries ----` section:

```java
    // ---- Co-change coupling ----

    public record CoChangePair(String otherFile, int count) {}

    /**
     * Upserts a co-change observation between two files.
     * Always stores canonical ordering (file_a < file_b).
     */
    public void upsertCoChange(String fileX, String fileY) {
        String fileA = fileX.compareTo(fileY) <= 0 ? fileX : fileY;
        String fileB = fileX.compareTo(fileY) <= 0 ? fileY : fileX;
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO co_change (file_a, file_b, count) VALUES (?, ?, 1)
                ON CONFLICT(file_a, file_b) DO UPDATE SET count = count + 1""")) {
            ps.setString(1, fileA);
            ps.setString(2, fileB);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert co-change: " + fileX + " <-> " + fileY, e);
        }
    }

    /**
     * Returns top co-changed files for a given file, sorted by count descending.
     */
    public List<CoChangePair> topCoupled(String filePath, int limit) {
        String sql = """
            SELECT
                CASE WHEN file_a = ? THEN file_b ELSE file_a END AS other_file,
                count
            FROM co_change
            WHERE file_a = ? OR file_b = ?
            ORDER BY count DESC
            LIMIT ?""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, filePath);
            ps.setString(2, filePath);
            ps.setString(3, filePath);
            ps.setInt(4, limit);
            ResultSet rs = ps.executeQuery();
            List<CoChangePair> results = new ArrayList<>();
            while (rs.next()) {
                results.add(new CoChangePair(rs.getString("other_file"), rs.getInt("count")));
            }
            return results;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query top coupled for: " + filePath, e);
        }
    }
```

- [ ] Run all four new tests — expect PASS:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && \
  ./gradlew test --tests "com.codenavigator.graph.GraphStoreTest.coChangeUpsert*" \
                 --tests "com.codenavigator.graph.GraphStoreTest.topCoupled*"
```
Expected output:
```
4 tests completed, 0 failed
BUILD SUCCESSFUL
```

- [ ] Run the full test suite to confirm no regressions:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test
```
Expected: `BUILD SUCCESSFUL`, all pre-existing tests pass.

- [ ] Commit:
```bash
git -C /home/kamil/Documents/Project/My/tools/mcp/code-navigator \
  add src/main/java/com/codenavigator/graph/GraphStore.java \
      src/test/java/com/codenavigator/graph/GraphStoreTest.java && \
git -C /home/kamil/Documents/Project/My/tools/mcp/code-navigator \
  commit -m "feat(graph): add co_change table with upsert and topCoupled query"
```

---

### Task 3: GitHistoryAnalyzer with temp-repo test

**Files:**
- Create `src/test/java/com/codenavigator/git/GitHistoryAnalyzerTest.java`
- Create `src/main/java/com/codenavigator/git/GitHistoryAnalyzer.java`

- [ ] Create the test file `src/test/java/com/codenavigator/git/GitHistoryAnalyzerTest.java`:

```java
package com.codenavigator.git;

import com.codenavigator.graph.GraphStore;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class GitHistoryAnalyzerTest {

    @TempDir Path repoDir;
    @TempDir Path dbDir;

    private GraphStore store;
    private Git git;

    @BeforeEach
    void setUp() throws Exception {
        store = new GraphStore(dbDir.resolve("test.db"));
        git = Git.init().setDirectory(repoDir.toFile()).call();
        // Required for commits to work in a bare environment
        git.getRepository().getConfig().setString("user", null, "name", "Test");
        git.getRepository().getConfig().setString("user", null, "email", "test@test.com");
        git.getRepository().getConfig().save();
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
        git.close();
    }

    private void writeAndCommit(String commitMsg, String... filePaths) throws Exception {
        for (String fp : filePaths) {
            Path file = repoDir.resolve(fp);
            Files.createDirectories(file.getParent());
            Files.writeString(file, commitMsg + "\n");
            git.add().addFilepattern(fp).call();
        }
        git.commit().setMessage(commitMsg)
           .setAuthor("Test", "test@test.com")
           .setCommitter("Test", "test@test.com")
           .call();
    }

    @Test
    void analyze_countsCoChangePairsAcrossCommits() throws Exception {
        // Commit 1: A + B change together
        writeAndCommit("c1", "src/A.java", "src/B.java");
        // Commit 2: A + B + C change together
        writeAndCommit("c2", "src/A.java", "src/B.java", "src/C.java");
        // Commit 3: only C changes
        writeAndCommit("c3", "src/C.java");

        var analyzer = new GitHistoryAnalyzer(store);
        analyzer.analyze(repoDir, 500);

        // A-B pair appeared in 2 commits
        var coupledA = store.topCoupled("src/A.java", 10);
        assertThat(coupledA).isNotEmpty();
        var abPair = coupledA.stream().filter(p -> p.otherFile().equals("src/B.java")).findFirst();
        assertThat(abPair).isPresent();
        assertThat(abPair.get().count()).isEqualTo(2);

        // A-C pair appeared in 1 commit
        var acPair = coupledA.stream().filter(p -> p.otherFile().equals("src/C.java")).findFirst();
        assertThat(acPair).isPresent();
        assertThat(acPair.get().count()).isEqualTo(1);
    }

    @Test
    void analyze_respectsCommitLimit() throws Exception {
        // 5 commits: A+B in commits 4 and 5 (most recent), not in older ones
        writeAndCommit("old1", "src/X.java");
        writeAndCommit("old2", "src/X.java");
        writeAndCommit("old3", "src/X.java");
        writeAndCommit("recent1", "src/A.java", "src/B.java");
        writeAndCommit("recent2", "src/A.java", "src/B.java");

        var analyzer = new GitHistoryAnalyzer(store);
        // Limit to 2 commits — only the most recent 2 are walked
        analyzer.analyze(repoDir, 2);

        var coupled = store.topCoupled("src/A.java", 5);
        assertThat(coupled).hasSize(1);
        assertThat(coupled.get(0).otherFile()).isEqualTo("src/B.java");
        assertThat(coupled.get(0).count()).isEqualTo(2);
    }

    @Test
    void analyze_singleFileCommitProducesNoPairs() throws Exception {
        writeAndCommit("solo", "src/Solo.java");

        var analyzer = new GitHistoryAnalyzer(store);
        analyzer.analyze(repoDir, 500);

        var coupled = store.topCoupled("src/Solo.java", 10);
        assertThat(coupled).isEmpty();
    }

    @Test
    void analyze_nonGitDirectory_doesNotThrow(@TempDir Path nonGitDir) {
        var analyzer = new GitHistoryAnalyzer(store);
        assertThatNoException().isThrownBy(() -> analyzer.analyze(nonGitDir, 500));
    }
}
```

- [ ] Run the test — expect FAIL because `GitHistoryAnalyzer` does not exist yet:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && \
  ./gradlew test --tests "com.codenavigator.git.GitHistoryAnalyzerTest.analyze_countsCoChangePairsAcrossCommits"
```
Expected failure:
```
FAILED — error: cannot find symbol: class GitHistoryAnalyzer
```

- [ ] Create `src/main/java/com/codenavigator/git/GitHistoryAnalyzer.java`:

```java
package com.codenavigator.git;

import com.codenavigator.graph.GraphStore;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Walks git commit history to mine file co-change coupling.
 * For each commit, collects changed files; for every unique pair,
 * increments the co_change counter in GraphStore.
 */
public class GitHistoryAnalyzer {

    private final GraphStore store;

    public GitHistoryAnalyzer(GraphStore store) {
        this.store = store;
    }

    /**
     * Analyzes the git repository at {@code projectPath}, walking up to
     * {@code maxCommits} most-recent commits. Silently skips if the path
     * is not a git repository.
     */
    public void analyze(Path projectPath, int maxCommits) {
        Repository repository;
        try {
            repository = new FileRepositoryBuilder()
                    .findGitDir(projectPath.toFile())
                    .build();
        } catch (RepositoryNotFoundException | IllegalArgumentException e) {
            // Not a git repo — skip gracefully
            return;
        } catch (IOException e) {
            // Unreadable repo — skip gracefully
            return;
        }

        try (repository; Git git = new Git(repository)) {
            Iterable<RevCommit> commits = git.log().setMaxCount(maxCommits).call();
            try (RevWalk revWalk = new RevWalk(repository)) {
                for (RevCommit commit : commits) {
                    List<String> changedFiles = getChangedFiles(repository, revWalk, commit);
                    recordPairs(changedFiles);
                }
            }
        } catch (GitAPIException | IOException e) {
            // Best-effort: log nothing, caller continues normally
        }
    }

    private List<String> getChangedFiles(Repository repository, RevWalk revWalk, RevCommit commit)
            throws IOException {
        List<String> files = new ArrayList<>();

        if (commit.getParentCount() == 0) {
            // Initial commit: all files in the tree are "changed"
            try (var treeWalk = new org.eclipse.jgit.treewalk.TreeWalk(repository)) {
                treeWalk.addTree(commit.getTree());
                treeWalk.setRecursive(true);
                while (treeWalk.next()) {
                    files.add(treeWalk.getPathString());
                }
            }
            return files;
        }

        RevCommit parent = revWalk.parseCommit(commit.getParent(0).getId());

        try (ObjectReader reader = repository.newObjectReader()) {
            CanonicalTreeParser oldTree = new CanonicalTreeParser();
            oldTree.reset(reader, parent.getTree().getId());

            CanonicalTreeParser newTree = new CanonicalTreeParser();
            newTree.reset(reader, commit.getTree().getId());

            try (DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                df.setRepository(repository);
                df.setDetectRenames(true);
                List<DiffEntry> diffs = df.scan(oldTree, newTree);
                for (DiffEntry diff : diffs) {
                    if (diff.getChangeType() == DiffEntry.ChangeType.DELETE) {
                        files.add(diff.getOldPath());
                    } else {
                        files.add(diff.getNewPath());
                    }
                }
            }
        }
        return files;
    }

    private void recordPairs(List<String> files) {
        int n = files.size();
        if (n < 2) return;
        for (int i = 0; i < n - 1; i++) {
            for (int j = i + 1; j < n; j++) {
                store.upsertCoChange(files.get(i), files.get(j));
            }
        }
    }
}
```

- [ ] Run all four analyzer tests — expect PASS:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && \
  ./gradlew test --tests "com.codenavigator.git.GitHistoryAnalyzerTest"
```
Expected:
```
4 tests completed, 0 failed
BUILD SUCCESSFUL
```

- [ ] Run full suite to confirm no regressions:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test
```

- [ ] Commit:
```bash
git -C /home/kamil/Documents/Project/My/tools/mcp/code-navigator \
  add src/main/java/com/codenavigator/git/GitHistoryAnalyzer.java \
      src/test/java/com/codenavigator/git/GitHistoryAnalyzerTest.java && \
git -C /home/kamil/Documents/Project/My/tools/mcp/code-navigator \
  commit -m "feat(git): add GitHistoryAnalyzer to mine co-change pairs from JGit history"
```

---

### Task 4: Wire GitHistoryAnalyzer into ProjectIndexer.indexFull

**Files:**
- Modify `src/main/java/com/codenavigator/indexer/ProjectIndexer.java` (at end of `indexFull`, after `trackFiles` call; add import)

- [ ] Add the import at the top of `ProjectIndexer.java`, after the existing imports:

```java
import com.codenavigator.git.GitHistoryAnalyzer;
```

- [ ] Add the git analysis call at the very end of `indexFull`, after the `trackFiles(projectPath, javaFiles, tsFiles);` line (currently line 92):

```java
        // 7. Mine git co-change coupling (silently skipped if not a git repo)
        new GitHistoryAnalyzer(store).analyze(projectPath, 500);
```

The end of `indexFull` now reads:
```java
        // 6. Track indexed files
        trackFiles(projectPath, javaFiles, tsFiles);

        // 7. Mine git co-change coupling (silently skipped if not a git repo)
        new GitHistoryAnalyzer(store).analyze(projectPath, 500);
    }
```

- [ ] Build to confirm it compiles cleanly:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew shadowJar
```
Expected: `BUILD SUCCESSFUL`

- [ ] Run the full test suite (no new tests needed — non-git path is covered by `analyze_nonGitDirectory_doesNotThrow` from Task 3):
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test
```
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] Commit:
```bash
git -C /home/kamil/Documents/Project/My/tools/mcp/code-navigator \
  add src/main/java/com/codenavigator/indexer/ProjectIndexer.java && \
git -C /home/kamil/Documents/Project/My/tools/mcp/code-navigator \
  commit -m "feat(indexer): run git co-change analysis at end of full index, with non-git skip"
```

---

### Task 5: cg_coupling MCP tool

**Files:**
- Modify `src/test/java/com/codenavigator/mcp/CodeNavigatorMcpServerTest.java` (add tests before closing `}`)
- Modify `src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java` (register tool in `start()`, add `handleCgCoupling`)

- [ ] Add tests for `cg_coupling` to `CodeNavigatorMcpServerTest.java` (insert before the final `}`):

```java
    // ---- cg_coupling tests ----

    @Test
    void cgCoupling_returnsTopCoupledFiles() {
        // Seed co-change data directly via store
        store.upsertCoChange("Ctrl.java", "Cmd.java");
        store.upsertCoChange("Ctrl.java", "Cmd.java");
        store.upsertCoChange("Ctrl.java", "H.java");

        var result = mcpServer.handleCgCoupling(Map.of("symbol", "OrderController"));
        assertThat(result).contains("Cmd.java");
        assertThat(result).contains("2");
        assertThat(result).contains("H.java");
    }

    @Test
    void cgCoupling_byFilePath() {
        store.upsertCoChange("Ctrl.java", "Order.java");
        store.upsertCoChange("Ctrl.java", "Order.java");
        store.upsertCoChange("Ctrl.java", "Order.java");

        var result = mcpServer.handleCgCoupling(Map.of("file", "Ctrl.java"));
        assertThat(result).contains("Order.java");
        assertThat(result).contains("3");
    }

    @Test
    void cgCoupling_symbolNotFound_showsError() {
        var result = mcpServer.handleCgCoupling(Map.of("symbol", "NonExistentClass"));
        assertThat(result).contains("not found");
    }

    @Test
    void cgCoupling_noCouplingData_showsEmptyMessage() {
        // OrderController exists in graph but no co-change data
        var result = mcpServer.handleCgCoupling(Map.of("symbol", "OrderController"));
        assertThat(result).contains("No co-change data");
    }
```

- [ ] Run the tests — expect FAIL because `handleCgCoupling` does not exist:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && \
  ./gradlew test --tests "com.codenavigator.mcp.CodeNavigatorMcpServerTest.cgCoupling_returnsTopCoupledFiles"
```
Expected failure:
```
FAILED — cannot find symbol: method handleCgCoupling
```

- [ ] Register the `cg_coupling` tool in `CodeNavigatorMcpServer.start()`. Insert the `.toolCall(...)` block immediately after the `cg_hotspots` registration (after the closing `)` of that call, around line 157), before the `cg_dead` block:

```java
            .toolCall(
                Tool.builder()
                    .name("cg_coupling")
                    .description("Show files that historically change together with a given symbol or file path. Uses git co-change mining to surface coupling.")
                    .inputSchema(jsonSchema(
                        withProjectPath(Map.of(
                            "symbol", propString("Symbol name or qualified name (resolved to its file path)"),
                            "file",   propString("Relative file path (alternative to symbol)"),
                            "limit",  propInt("Max results to return (default 10)"))),
                        List.of()))
                    .build(),
                (exchange, request) -> textResult(handleCgCoupling(request.arguments()))
            )
```

- [ ] Add the `handleCgCoupling` handler method to `CodeNavigatorMcpServer.java`. Insert it after `handleCgHotspots` (after line 478):

```java
    String handleCgCoupling(Map<String, Object> args) {
        int limit = getIntArg(args, "limit", 10);

        String filePath = null;

        if (args.containsKey("symbol")) {
            String symbol = (String) args.get("symbol");
            String nodeId = resolveSymbol(symbol);
            if (nodeId == null) return "Symbol '" + symbol + "' not found.";
            var node = store.findNodeById(nodeId).orElseThrow();
            filePath = node.filePath();
        } else if (args.containsKey("file")) {
            filePath = (String) args.get("file");
        }

        if (filePath == null) return "Provide either 'symbol' or 'file'.";

        var coupled = store.topCoupled(filePath, limit);
        if (coupled.isEmpty()) return "No co-change data for: " + filePath;

        var sb = new StringBuilder();
        sb.append("## Co-change coupling for `").append(filePath).append("`\n\n");
        sb.append("Top ").append(coupled.size()).append(" historically co-changed file(s):\n\n");
        for (int i = 0; i < coupled.size(); i++) {
            var pair = coupled.get(i);
            sb.append(String.format(" %d. `%s` — %d commit(s)%n", i + 1, pair.otherFile(), pair.count()));
        }
        return sb.toString();
    }
```

- [ ] Run the four new tests — expect PASS:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && \
  ./gradlew test --tests "com.codenavigator.mcp.CodeNavigatorMcpServerTest.cgCoupling*"
```
Expected:
```
4 tests completed, 0 failed
BUILD SUCCESSFUL
```

- [ ] Run full suite:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test
```

- [ ] Commit:
```bash
git -C /home/kamil/Documents/Project/My/tools/mcp/code-navigator \
  add src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java \
      src/test/java/com/codenavigator/mcp/CodeNavigatorMcpServerTest.java && \
git -C /home/kamil/Documents/Project/My/tools/mcp/code-navigator \
  commit -m "feat(mcp): add cg_coupling tool to surface git co-change pairs"
```

---

### Task 6: Annotate cg_impact output with co-change counts

**Files:**
- Modify `src/test/java/com/codenavigator/mcp/CodeNavigatorMcpServerTest.java` (add annotation test before closing `}`)
- Modify `src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java` (update `handleCgImpact`)

- [ ] Add an annotation test to `CodeNavigatorMcpServerTest.java` (insert before the final `}`):

```java
    @Test
    void cgImpact_annotatesCoChangeCounts() {
        // OrderController (Ctrl.java) is the queried symbol.
        // Order (Order.java) is in the impact set.
        // Seed coupling between Ctrl.java and Order.java.
        store.upsertCoChange("Ctrl.java", "Order.java");
        store.upsertCoChange("Ctrl.java", "Order.java");

        var result = mcpServer.handleCgImpact(Map.of("symbol", "OrderController", "depth", 3));
        // Order.java should appear annotated with co-change count
        assertThat(result).contains("Order");
        assertThat(result).containsPattern("co-change.*2|2.*co-change");
    }
```

- [ ] Run the test — expect FAIL because the annotation does not exist yet:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && \
  ./gradlew test --tests "com.codenavigator.mcp.CodeNavigatorMcpServerTest.cgImpact_annotatesCoChangeCounts"
```
Expected failure:
```
FAILED — expected output to contain pattern "co-change.*2|2.*co-change"
```

- [ ] Replace the existing `handleCgImpact` method in `CodeNavigatorMcpServer.java` (currently lines 273–287) with the annotated version:

```java
    String handleCgImpact(Map<String, Object> args) {
        String symbol = (String) args.get("symbol");
        int depth = getIntArg(args, "depth", 2);
        String nodeId = resolveSymbol(symbol);
        if (nodeId == null) return "Symbol '" + symbol + "' not found.";

        var originNode = store.findNodeById(nodeId).orElseThrow();
        String originFile = originNode.filePath();

        // Pre-fetch co-change counts for the origin file (up to 200 entries as ranking signal)
        var coupled = store.topCoupled(originFile, 200);
        Map<String, Integer> coChangeByFile = new java.util.HashMap<>();
        for (var pair : coupled) {
            coChangeByFile.put(pair.otherFile(), pair.count());
        }

        var nodes = traversal.impact(nodeId, depth);
        var sb = new StringBuilder();
        sb.append("## Impact of ").append(symbol).append(" (depth ").append(depth).append(")\n\n");
        sb.append(nodes.size()).append(" affected node(s):\n\n");
        for (var node : nodes) {
            String line = formatNodeLine(node);
            int coCount = coChangeByFile.getOrDefault(node.filePath(), 0);
            if (coCount > 0) {
                line += " _(co-change: " + coCount + " commit(s))_";
            }
            sb.append(line).append("\n");
        }
        return sb.toString();
    }
```

- [ ] Run the annotation test — expect PASS:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && \
  ./gradlew test --tests "com.codenavigator.mcp.CodeNavigatorMcpServerTest.cgImpact_annotatesCoChangeCounts"
```
Expected:
```
1 test completed, 0 failed
BUILD SUCCESSFUL
```

- [ ] Run the full test suite to confirm the existing `cgImpact` test still passes and no regressions:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew test
```
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] Build the fat JAR:
```bash
cd /home/kamil/Documents/Project/My/tools/mcp/code-navigator && ./gradlew shadowJar
```
Expected: `BUILD SUCCESSFUL`

- [ ] Commit:
```bash
git -C /home/kamil/Documents/Project/My/tools/mcp/code-navigator \
  add src/main/java/com/codenavigator/mcp/CodeNavigatorMcpServer.java \
      src/test/java/com/codenavigator/mcp/CodeNavigatorMcpServerTest.java && \
git -C /home/kamil/Documents/Project/My/tools/mcp/code-navigator \
  commit -m "feat(mcp): annotate cg_impact output with co-change counts as ranking signal"
```

---

## Self-Review

| Spec item | Covered in task |
|-----------|-----------------|
| Add JGit dependency to `build.gradle` | Task 1 |
| `GitHistoryAnalyzer` walks last N commits (default 500), collects changed-file sets, increments pair counter | Task 3 |
| `co_change(file_a, file_b, count)` SQLite table, `file_a < file_b` canonical ordering, `PRIMARY KEY(file_a, file_b)` | Task 2 |
| `GraphStore` methods: `upsertCoChange`, `topCoupled` | Task 2 |
| Wire into `ProjectIndexer.indexFull` | Task 4 |
| Non-git graceful skip (`RepositoryNotFoundException` / missing `.git`) | Task 3 (`analyze_nonGitDirectory_doesNotThrow`), Task 3 implementation guard, and Task 4 wiring |
| New MCP tool `cg_coupling` with `handleCgCoupling` handler, registered via `Tool.builder()` pattern | Task 5 |
| `cg_impact` annotates affected nodes with co-change count when > 0 | Task 6 |
