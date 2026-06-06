package com.codenavigator.git;

import com.codenavigator.graph.GraphStore;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
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
