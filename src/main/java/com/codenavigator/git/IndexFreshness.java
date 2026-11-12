package com.codenavigator.git;

import com.codenavigator.cli.ProjectPaths;
import com.codenavigator.graph.GraphStore;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether the index is behind the code. {@code init} and {@code sync} store the git HEAD sha
 * ({@link #HEAD_KEY}) and the time ({@link #SYNCED_AT_KEY}) in {@code project_config}; a call compares
 * the stored sha with the current HEAD and looks for the {@code .dirty} flag file. No source file is
 * hashed or statted.
 *
 * <p>Caching: HEAD is resolved on every call (a few small file reads); only the commit count, the
 * one part that walks history, is cached, keyed on the (stored sha, HEAD sha) pair. A new commit or a
 * sync changes the key, so the cache never serves a stale count and needs no expiry.
 *
 * <p>Unknown is not stale: not a git repo, no stored sha (an index made by an older jar) or any
 * failure while checking yields {@link Optional#empty()}.
 */
public final class IndexFreshness {

    public static final String HEAD_KEY = "index.head";
    public static final String SYNCED_AT_KEY = "index.syncedAt";
    static final int COUNT_CAP = 100;

    /** Commits behind: 0 none, {@code >0} a count (capped), {@code -1} HEAD is not a descendant of the stored sha. */
    public record State(int behind, boolean dirty) {
        public boolean current() { return behind == 0 && !dirty; }

        private String behindText(String tail) {
            if (behind < 0) return "HEAD changed since sync";
            return (behind >= COUNT_CAP ? COUNT_CAP + "+" : String.valueOf(behind)) + " commit(s) behind" + tail;
        }

        /** The {@code cg_map} value after {@code - **Index:** }. */
        public String mapValue() {
            if (current()) return "current";
            var sb = new StringBuilder();
            if (behind != 0) sb.append(behindText(behind < 0 ? "" : " HEAD"));
            if (dirty) sb.append(behind != 0 ? ", " : "").append("dirty flag set");
            return sb.toString();
        }

        /** The line every other tool puts first when stale. */
        public String staleLine() {
            var sb = new StringBuilder("Index stale: ");
            if (behind != 0) sb.append(behindText(""));
            if (dirty) sb.append(behind != 0 ? ", " : "").append("dirty flag set");
            return sb.append(", run sync.").toString();
        }
    }

    private static final Map<String, Integer> COUNTS = new ConcurrentHashMap<>();

    private IndexFreshness() {}

    /** Store HEAD and the time at the end of a successful index. A project that is not a git repo stores nothing. */
    public static void record(GraphStore store, Path root) {
        String head = headSha(root);
        if (head == null) return;
        store.setConfig(HEAD_KEY, head);
        store.setConfig(SYNCED_AT_KEY, Instant.now().toString());
    }

    public static Optional<State> check(GraphStore store, Path root) {
        try {
            String stored = store.getConfig(HEAD_KEY);
            if (stored == null) return Optional.empty();
            try (Repository repo = open(root)) {
                if (repo == null) return Optional.empty();
                ObjectId headId = repo.resolve("HEAD");
                if (headId == null) return Optional.empty();
                boolean dirty = Files.exists(ProjectPaths.dirtyFile(root));
                int behind = stored.equals(headId.name()) ? 0
                    : COUNTS.computeIfAbsent(stored + ":" + headId.name(), k -> countBehind(repo, stored, headId));
                return Optional.of(new State(behind, dirty));
            }
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static int countBehind(Repository repo, String stored, ObjectId headId) {
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit head = walk.parseCommit(headId);
            RevCommit base = walk.parseCommit(ObjectId.fromString(stored));
            if (!walk.isMergedInto(base, head)) return -1;
            walk.reset();
            walk.markStart(head);
            walk.markUninteresting(base);
            int n = 0;
            while (walk.next() != null && n < COUNT_CAP) n++;
            return n;
        } catch (Exception e) {
            return -1;   // object missing: HEAD moved somewhere the stored sha is not part of
        }
    }

    private static String headSha(Path root) {
        try (Repository repo = open(root)) {
            if (repo == null) return null;
            ObjectId id = repo.resolve("HEAD");
            return id == null ? null : id.name();
        } catch (Exception e) {
            return null;
        }
    }

    private static Repository open(Path root) {
        try {
            var builder = new FileRepositoryBuilder().findGitDir(root.toFile());
            return builder.getGitDir() == null ? null : builder.build();
        } catch (Exception e) {
            return null;
        }
    }
}
