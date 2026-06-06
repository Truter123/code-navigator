package com.codenavigator.git;

import com.codenavigator.graph.GraphStore;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
