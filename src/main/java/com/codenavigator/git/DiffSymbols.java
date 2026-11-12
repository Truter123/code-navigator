package com.codenavigator.git;

import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.Node;
import com.codenavigator.graph.NodeType;
import com.codenavigator.indexer.MethodExtractor;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.util.io.NullOutputStream;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a git diff into the graph nodes it changed.
 *
 * <p>{@code diffArg} {@code "true"}: the working tree (staged, unstaged and untracked-but-not-ignored)
 * against HEAD. Any other value is a ref (branch, tag, sha, {@code HEAD~3}): that ref's tree against the
 * working tree, so committed and uncommitted work since the ref both count.
 *
 * <p>A changed {@code .java} file is parsed fresh and each hunk's new-side lines are mapped to the
 * methods and constructors whose span overlaps it; the line numbers stored in the index are never used,
 * since the index can be older than the file. A hunk outside every method selects the enclosing type.
 * A method id the index has for the file but the fresh parse lacks is changed too ({@code removed}); one
 * the parse has but the index lacks is only counted. Other indexed languages are file level (every
 * non-METHOD node of the file). Renames are not detected: a rename is a delete of the old path plus an add
 * of the new one. At most {@link #MAX_PARSED} Java files are parsed per call; the rest are file level.
 */
public final class DiffSymbols {

    public static final int MAX_PARSED = 200;
    public static final String MODIFIED = "modified";
    public static final String REMOVED = "removed";
    private static final String NAVIGATORS_DIR = "navigators/";

    public enum Status { OK, NOT_A_GIT_REPO, UNKNOWN_REF, NO_CHANGES }

    /**
     * @param nodeIds           changed nodes present in the index: methods first (file order), then types; no duplicates
     * @param reasons           node id to {@code modified} or {@code removed}
     * @param changedFiles      changed files inside the project root
     * @param filesWithoutNode  changed files the index has no node for
     * @param newMethods        methods in a changed file that the index does not have
     * @param ref               what was compared against: {@code HEAD} or the ref given
     */
    public record Result(Status status, List<String> nodeIds, Map<String, String> reasons,
                         int changedFiles, int filesWithoutNode, int newMethods, String ref) {
        static Result of(Status status, String ref) {
            return new Result(status, List.of(), Map.of(), 0, 0, 0, ref);
        }
    }

    private DiffSymbols() {}

    public static Result collect(Path projectRoot, GraphStore store, String diffArg) {
        boolean head = diffArg == null || diffArg.isBlank() || diffArg.equalsIgnoreCase("true");
        String ref = head ? "HEAD" : diffArg.trim();
        try {
            var builder = new FileRepositoryBuilder().findGitDir(projectRoot.toFile());
            if (builder.getGitDir() == null) return Result.of(Status.NOT_A_GIT_REPO, ref);
            try (Repository repo = builder.build()) {
                if (repo.getWorkTree() == null) return Result.of(Status.NOT_A_GIT_REPO, ref);
                return collect(repo, projectRoot, store, ref, head);
            }
        } catch (Exception e) {
            return Result.of(Status.NOT_A_GIT_REPO, ref);
        }
    }

    private static Result collect(Repository repo, Path projectRoot, GraphStore store,
                                  String ref, boolean head) throws Exception {
        ObjectId tree;
        try {
            tree = repo.resolve(ref + "^{tree}");
        } catch (Exception e) {
            tree = null;
        }
        if (tree == null && !head) return Result.of(Status.UNKNOWN_REF, ref);

        Path work = repo.getWorkTree().toPath().toRealPath();
        Path root = projectRoot.toRealPath();
        String prefix = work.equals(root) ? "" : work.relativize(root).toString().replace('\\', '/') + "/";

        List<DiffEntry> entries;
        try (ObjectReader reader = repo.newObjectReader();
             DiffFormatter df = new DiffFormatter(NullOutputStream.INSTANCE)) {
            df.setRepository(repo);
            AbstractTreeIterator oldTree = new EmptyTreeIterator();
            if (tree != null) {
                var parser = new CanonicalTreeParser();
                parser.reset(reader, tree);
                oldTree = parser;
            }
            entries = new ArrayList<>(df.scan(oldTree, new FileTreeIterator(repo)));
            entries = keepProject(repo, entries, prefix);
            if (entries.isEmpty()) return Result.of(Status.NO_CHANGES, ref);
            return map(df, entries, root, prefix, store, ref);
        }
    }

    /** Drop entries outside the project root, the index directory, and added files git ignores. */
    private static List<DiffEntry> keepProject(Repository repo, List<DiffEntry> all, String prefix) throws Exception {
        List<DiffEntry> inside = new ArrayList<>();
        boolean needUntracked = false;
        for (DiffEntry e : all) {
            String p = e.getChangeType() == DiffEntry.ChangeType.DELETE ? e.getOldPath() : e.getNewPath();
            if (!p.startsWith(prefix)) continue;
            if (p.substring(prefix.length()).startsWith(NAVIGATORS_DIR)) continue;
            inside.add(e);
            if (e.getChangeType() == DiffEntry.ChangeType.ADD) needUntracked = true;
        }
        if (!needUntracked) return inside;
        Set<String> untracked;
        try (Git git = Git.wrap(repo)) {
            untracked = git.status().call().getUntracked();
        }
        var cache = repo.readDirCache();
        List<DiffEntry> kept = new ArrayList<>();
        for (DiffEntry e : inside) {
            if (e.getChangeType() == DiffEntry.ChangeType.ADD
                && cache.findEntry(e.getNewPath()) < 0 && !untracked.contains(e.getNewPath())) continue;
            kept.add(e);
        }
        return kept;
    }

    private static Result map(DiffFormatter df, List<DiffEntry> entries, Path root, String prefix,
                              GraphStore store, String ref) {
        var methods = new LinkedHashSet<String>();
        var types = new LinkedHashSet<String>();
        var reasons = new LinkedHashMap<String, String>();
        int files = 0, noNode = 0, newMethods = 0, parsed = 0;
        com.codenavigator.indexer.ProjectIndexer.configureParser();

        for (DiffEntry e : entries) {
            files++;
            boolean deleted = e.getChangeType() == DiffEntry.ChangeType.DELETE;
            String rel = (deleted ? e.getOldPath() : e.getNewPath()).substring(prefix.length());
            List<Node> indexed = store.findNodesByFilePath(rel);
            if (indexed.isEmpty()) { noNode++; continue; }

            if (deleted) {
                for (Node n : indexed) add(n, methods, types, reasons, REMOVED);
                continue;
            }
            if (rel.endsWith(".java") && parsed < MAX_PARSED) {
                parsed++;
                int added = mapJava(df, e, root.resolve(rel), rel, indexed, methods, types, reasons);
                if (added >= 0) { newMethods += added; continue; }
            }
            for (Node n : indexed) if (n.type() != NodeType.METHOD) add(n, methods, types, reasons, MODIFIED);
        }

        var ids = new ArrayList<String>(methods);
        ids.addAll(types);
        return new Result(Status.OK, ids, reasons, files, noNode, newMethods, ref);
    }

    private static void add(Node n, Set<String> methods, Set<String> types,
                            Map<String, String> reasons, String reason) {
        (n.type() == NodeType.METHOD ? methods : types).add(n.id());
        reasons.putIfAbsent(n.id(), reason);
    }

    /** Returns the count of new methods, or -1 when the file could not be mapped (caller goes file level). */
    private static int mapJava(DiffFormatter df, DiffEntry entry, Path file, String rel, List<Node> indexed,
                               Set<String> methods, Set<String> types, Map<String, String> reasons) {
        try {
            List<Edit> edits = df.toFileHeader(entry).toEditList();
            CompilationUnit cu = StaticJavaParser.parse(file);
            var extracted = new MethodExtractor().extract(cu, indexed, rel, Files.getLastModifiedTime(file).toMillis())
                .stream().filter(m -> m.node() != null).toList();

            Set<String> indexedMethodIds = new LinkedHashSet<>();
            Set<String> typeIds = new LinkedHashSet<>();
            for (Node n : indexed) (n.type() == NodeType.METHOD ? indexedMethodIds : typeIds).add(n.id());
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");

            var hit = new LinkedHashMap<String, Integer>();   // method id -> begin line
            var hitTypes = new LinkedHashSet<String>();
            for (Edit ed : edits) {
                int from = ed.getBeginB() + 1, to = ed.getEndB();   // 1-based inclusive new-side lines
                boolean deletion = to < from;
                if (deletion) to = from;
                boolean allCovered = true;
                // A deletion sits between two lines: it is inside a method only when the same
                // method holds both neighbours; deleting at a boundary selects the type.
                int lo = deletion ? ed.getBeginB() : from;
                int hi = deletion ? ed.getBeginB() + 1 : to;
                if (deletion) {
                    boolean covered = false;
                    for (var m : extracted) {
                        if (lo >= m.node().lineNumber() && hi <= Math.max(m.endLine(), m.node().lineNumber())) {
                            hit.put(m.node().id(), m.node().lineNumber());
                            covered = true;
                        }
                    }
                    allCovered = covered;
                } else {
                    for (int line = lo; line <= hi; line++) {
                        boolean covered = false;
                        for (var m : extracted) {
                            if (line >= m.node().lineNumber() && line <= Math.max(m.endLine(), m.node().lineNumber())) {
                                hit.put(m.node().id(), m.node().lineNumber());
                                covered = true;
                            }
                        }
                        if (!covered) allCovered = false;
                    }
                }
                if (!allCovered) {
                    String enclosing = enclosingType(cu, pkg, from, typeIds);
                    if (enclosing != null) hitTypes.add(enclosing); else hitTypes.addAll(typeIds);
                }
            }

            Set<String> parsedIds = new LinkedHashSet<>();
            int added = 0;
            for (var m : extracted) {
                parsedIds.add(m.node().id());
                if (!indexedMethodIds.contains(m.node().id())) added++;
            }
            hit.entrySet().stream().sorted(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .filter(indexedMethodIds::contains).forEach(id -> {
                    methods.add(id);
                    reasons.putIfAbsent(id, MODIFIED);
                });
            for (String id : indexedMethodIds) if (!parsedIds.contains(id)) {
                methods.add(id);
                reasons.put(id, REMOVED);
            }
            for (String t : hitTypes) { types.add(t); reasons.putIfAbsent(t, MODIFIED); }
            return added;
        } catch (Exception | StackOverflowError e) {
            return -1;
        }
    }

    /** Innermost type declaration holding {@code line}, as an indexed id; null when none (imports, package). */
    private static String enclosingType(CompilationUnit cu, String pkg, int line, Set<String> typeIds) {
        String best = null;
        int bestSpan = Integer.MAX_VALUE;
        for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
            if (t.getBegin().isEmpty() || t.getEnd().isEmpty()) continue;
            int b = t.getBegin().get().line, e = t.getEnd().get().line;
            if (line < b || line > e) continue;
            String id = com.codenavigator.indexer.JavaTypeResolver.typeId(pkg, t);
            if (typeIds.contains(id) && e - b < bestSpan) { best = id; bestSpan = e - b; }
        }
        return best;
    }
}
