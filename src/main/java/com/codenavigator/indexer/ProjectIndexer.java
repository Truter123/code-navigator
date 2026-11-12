package com.codenavigator.indexer;

import com.codenavigator.graph.*;
import com.codenavigator.git.GitHistoryAnalyzer;
import com.codenavigator.git.IndexFreshness;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

public class ProjectIndexer {

    private final GraphStore store;
    private final ProjectDetector projectDetector;
    private final TypeScriptIndexer tsIndexer;
    private final GroovyIndexer groovyIndexer;
    private final DartIndexer dartIndexer;
    private final MethodExtractor methodExtractor;
    private final TypeScriptMethodExtractor tsMethodExtractor;
    private final DartMethodExtractor dartMethodExtractor;
    private final DependencyParser dependencyParser;
    private final List<LanguageIndexer> extraIndexers = List.of(new ShellIndexer(), new SqlIndexer(), new PythonIndexer());

    public ProjectIndexer(GraphStore store) {
        this.store = store;
        this.projectDetector = new ProjectDetector();
        this.tsIndexer = new TypeScriptIndexer();
        this.groovyIndexer = new GroovyIndexer();
        this.dartIndexer = new DartIndexer();
        this.methodExtractor = new MethodExtractor();
        this.tsMethodExtractor = new TypeScriptMethodExtractor();
        this.dartMethodExtractor = new DartMethodExtractor();
        this.dependencyParser = new DependencyParser();
    }

    public void indexFull(Path projectPath) {
        configureParser();

        // One transaction: a run killed half-way (the hook's timeout) rolls back to the index
        // that was there, instead of leaving one with its edges cleared and not yet rebuilt.
        store.batch(() -> buildGraph(projectPath));

        // 7. Mine git co-change coupling (silently skipped if not a git repo). upsertCoChange
        // increments an existing count, so without clearing first a second indexFull doubles
        // every pair count.
        store.deleteAllCoChange();
        var gitAnalyzer = new GitHistoryAnalyzer(store);
        gitAnalyzer.analyze(projectPath, 500);
        if (gitAnalyzer.skippedBulkCommits() > 0) {
            System.err.printf("Co-change: skipped %d bulk commit(s) touching more than 50 files.%n",
                gitAnalyzer.skippedBulkCommits());
        }

        // 8. Remember which commit this index matches, so answers can say when it falls behind.
        IndexFreshness.record(store, projectPath);
    }

    /** Steps 0 to 6 of a full index: clear, nodes, methods and edges, file tracking. */
    private void buildGraph(Path projectPath) {
        // 0. Start from empty. Edge ids are random UUIDs and method rows are autoincrement, so
        // INSERT OR REPLACE cannot dedupe either: without this, a second `init` over an existing
        // database doubles every edge (25,823 -> 52,088 on nlp) and every traversal silently
        // reports each neighbour twice. Nodes are keyed by id and so replace cleanly; a node
        // whose file has since been deleted or moved is pruned below.
        store.deleteAllEdges();
        store.deleteAllMethods();
        store.deleteAllEntryPoints();
        store.deleteAllReturnClasses();
        store.deleteAllFileShapes();
        // Nodes too: they are keyed by id, so a node whose id this jar no longer produces (the flat
        // `pkg.Inner` of a nested type, before `pkg.Outer.Inner`) would otherwise live on forever
        // next to its replacement. Every language re-saves its nodes below, inside this transaction.
        store.deleteAllNodes();
        store.batch(() -> {
            pruneDeletedFiles(projectPath);
            pruneNestedWorktreeNodes(projectPath);
        });

        // 1. Detect project type
        Project project = projectDetector.detect(projectPath);
        store.setConfig("tier", project.name());

        // 1b. Parse declared dependencies for library boundary indexing
        List<Dependency> dependencies = dependencyParser.parse(projectPath);

        // Persist declared dependencies for the cg_deps tool
        store.setConfig("dependencies", dependenciesJson(dependencies));

        var nodeExtractor = new NodeExtractor(project);

        // 2. Find all files
        List<Path> javaFiles = findJavaFiles(projectPath);
        List<Path> tsFiles = findTsFiles(projectPath);
        List<Path> groovyFiles = findGroovyFiles(projectPath);
        List<Path> dartFiles = findDartFiles(projectPath);
        Map<LanguageIndexer, List<Path>> extraFilesByIndexer = collectExtraFiles(projectPath);

        // 3. Phase 1: Extract nodes from Java files
        store.batch(() -> saveJavaNodes(javaFiles, projectPath, nodeExtractor));

        // 4. Phase 1b: Extract nodes from TS files
        store.batch(() -> {
            for (Path file : tsFiles) {
                var nodes = tsIndexer.indexFile(file);
                for (Node node : nodes) {
                    store.saveNode(node);
                }
            }
        });

        // 4b. Phase 1d: Extract nodes from Groovy files (CI/CD scripts; no edges)
        for (Path file : groovyFiles) {
            var nodes = groovyIndexer.indexFile(file);
            for (Node node : nodes) {
                store.saveNode(node);
            }
        }

        // 4c. Phase 1e: Extract nodes from Dart/Flutter files
        store.batch(() -> {
            for (Path file : dartFiles) {
                var nodes = dartIndexer.indexFile(file);
                for (Node node : nodes) {
                    store.saveNode(node);
                }
            }
        });

        // 4d. Phase 1f: Extract nodes from extra-language files (shell today; SQL/Python later)
        store.batch(() -> {
            for (var entry : extraFilesByIndexer.entrySet()) {
                entry.getKey().prepare(entry.getValue(), projectPath);
                for (Path file : entry.getValue()) {
                    for (Node node : entry.getKey().indexFile(file, projectPath)) {
                        store.saveNode(node);
                    }
                }
            }
        });

        // 5. Phase 2: Methods, then edges (same rebuild indexIncremental re-runs on any change)
        rebuildEdgesAndMethods(javaFiles, tsFiles, dartFiles, extraFilesByIndexer, projectPath, project, dependencies);

        // 6. Track indexed files
        trackFiles(projectPath, javaFiles, tsFiles, groovyFiles, dartFiles, extraFilesByIndexer);

        // 6b. Stamp the node id scheme this index was built with, in the same transaction.
        store.setConfig(INDEX_FORMAT_KEY, INDEX_FORMAT);
    }

    /**
     * Version of the node id scheme. 2: a nested Java type is {@code <package>.<Outer>.<Inner>}
     * (1, with no marker, was {@code <package>.<Inner>}). Bump it whenever ids change shape; the
     * next sync then rebuilds the whole index instead of mixing the two schemes.
     */
    static final String INDEX_FORMAT_KEY = "index.format";
    static final String INDEX_FORMAT = "2";

    /** What a sync did: the fast path (and how many Java files it re-extracted) or a full edge rebuild and why. */
    public record SyncResult(boolean fastPath, int filesReextracted, int javaFilesTotal, String fallbackReason) {
        /** True when something changed but the full edge rebuild had to run. */
        public boolean fullRebuild() { return !fastPath && fallbackReason != null; }

        /** The one line sync prints, or null when nothing changed. */
        public String line() {
            if (fastPath) return "Sync: fast path, re-extracted " + filesReextracted + " of " + javaFilesTotal + " Java file(s).";
            if (fallbackReason != null) return "Sync: full edge rebuild (" + fallbackReason + ").";
            return null;
        }
    }

    private java.util.function.Supplier<String> fullSyncFlag = () -> System.getenv("CODE_NAVIGATOR_FULL_SYNC");

    private Runnable afterEdgesCleared = () -> { };

    /** Runs right after a rebuild cleared the edges (tests stop the run there, as a kill would). */
    ProjectIndexer withAfterEdgesCleared(Runnable probe) {
        this.afterEdgesCleared = probe;
        return this;
    }

    /** Replace where the CODE_NAVIGATOR_FULL_SYNC value is read from (tests inject it instead of touching the environment). */
    public ProjectIndexer withFullSyncFlag(java.util.function.Supplier<String> flag) {
        this.fullSyncFlag = flag;
        return this;
    }

    /** One changed Java file, parsed and extracted fresh, before anything is written. */
    private record Prepared(Path file, String filePath, com.github.javaparser.ast.CompilationUnit cu,
                            List<Node> typeNodes, List<MethodExtractor.ExtractedMethod> extracted,
                            long lastModified, String shape) {}

    /** The outcome of classifying a change set: prepared files for the fast path, else the reason (null: no change). */
    private record Decision(List<Prepared> prepared, String reason) {}

    public SyncResult indexIncremental(Path projectPath) {
        configureParser();

        // 0. An index made with another id scheme (or before the marker existed) is rebuilt whole:
        // the incremental paths would keep its old nodes next to the new ones.
        if (!INDEX_FORMAT.equals(store.getConfig(INDEX_FORMAT_KEY))) {
            indexFull(projectPath);
            return new SyncResult(false, 0, findJavaFiles(projectPath).size(), "index format changed");
        }

        // 1. Read project type from config
        String projectStr = store.getConfig("tier");
        Project project = projectStr != null ? Project.valueOf(projectStr) : projectDetector.detect(projectPath);

        List<Dependency> dependencies = dependencyParser.parse(projectPath);

        var nodeExtractor = new NodeExtractor(project);

        // 2. Find all files
        List<Path> javaFiles = findJavaFiles(projectPath);
        List<Path> tsFiles = findTsFiles(projectPath);
        List<Path> groovyFiles = findGroovyFiles(projectPath);
        List<Path> dartFiles = findDartFiles(projectPath);
        Map<LanguageIndexer, List<Path>> extraFilesByIndexer = collectExtraFiles(projectPath);

        // 3. Decide, before anything is written: the fast path re-extracts only the changed Java
        // files and is taken only when no edge owned by another file can change (see classify).
        var decision = classify(projectPath, project, nodeExtractor, dependencies,
            javaFiles, tsFiles, groovyFiles, dartFiles, extraFilesByIndexer);

        SyncResult result;
        if (decision.prepared() != null) {
            fastSync(projectPath, project, nodeExtractor, dependencies, decision.prepared());
            result = new SyncResult(true, decision.prepared().size(), javaFiles.size(), null);
        } else {
            boolean any = fullSync(projectPath, project, nodeExtractor, dependencies,
                javaFiles, tsFiles, groovyFiles, dartFiles, extraFilesByIndexer);
            String reason = decision.reason();
            if (reason == null && any) reason = "other change";
            result = new SyncResult(false, 0, javaFiles.size(), reason);
        }

        IndexFreshness.record(store, projectPath);
        return result;
    }

    /** Today's incremental path: re-index changed files, then rebuild every edge and method. True if anything changed. */
    private boolean fullSync(Path projectPath, Project project, NodeExtractor nodeExtractor,
                             List<Dependency> dependencies, List<Path> javaFiles, List<Path> tsFiles,
                             List<Path> groovyFiles, List<Path> dartFiles,
                             Map<LanguageIndexer, List<Path>> extraFilesByIndexer) {
        // One transaction for the whole sync. Unbatched, every deleted and saved row was its own
        // fsync, and a sync of 1,684 changed files on nlp never finished inside the hook's 600 s
        // timeout. And a run killed between "edges cleared" and "edges rebuilt" must roll back to
        // the index that was there, not leave one that answers "nobody calls this" for everything.
        boolean[] changed = {false};
        store.batch(() -> {
            store.setConfig("tier", project.name());

            // The declared dependencies are part of the graph (USES_LIBRARY edges, cg_deps). A
            // build-file-only edit changed nothing else and so triggered no work; and the stored
            // list was written by indexFull alone, so after one dependency edit every later sync
            // saw "build file changed" and never took the fast path again.
            String declared = dependenciesJson(dependencies);
            if (!declared.equals(store.getConfig("dependencies"))) {
                store.setConfig("dependencies", declared);
                changed[0] = true;
            }

            changed[0] |= reindexChangedJavaFiles(javaFiles, projectPath, nodeExtractor);
            changed[0] |= reindexChangedTsFiles(tsFiles);
            changed[0] |= reindexChangedGroovyFiles(groovyFiles);
            changed[0] |= reindexChangedDartFiles(dartFiles);
            changed[0] |= reindexChangedExtraFiles(extraFilesByIndexer, projectPath);
            changed[0] |= pruneDeletedFiles(projectPath);

            // Full methods + edge re-extraction if anything changed, mirroring indexFull exactly
            // so a sync never leaves the graph in a state a full index couldn't also produce.
            if (changed[0]) {
                rebuildEdgesAndMethods(javaFiles, tsFiles, dartFiles, extraFilesByIndexer, projectPath, project, dependencies);
            }

            // Update tracked files (one row per file in the project)
            trackFiles(projectPath, javaFiles, tsFiles, groovyFiles, dartFiles, extraFilesByIndexer);
        });
        return changed[0];
    }

    /**
     * Decide fast path or full rebuild without writing to the store. The fast path needs: only
     * {@code .java} files changed (at most 30% of them), no file added or removed in any language,
     * the same declared dependencies, and for each changed file a fresh declared shape equal to the
     * stored one (so the file's node ids are the same and no edge owned by another file can change).
     * Anything else is today's full rebuild, which is always correct.
     */
    private Decision classify(Path projectPath, Project project, NodeExtractor nodeExtractor,
                              List<Dependency> dependencies, List<Path> javaFiles, List<Path> tsFiles,
                              List<Path> groovyFiles, List<Path> dartFiles,
                              Map<LanguageIndexer, List<Path>> extraFilesByIndexer) {
        var tracked = new java.util.HashSet<>(store.getAllIndexedFiles());
        var all = new ArrayList<Path>(javaFiles);
        all.addAll(tsFiles);
        all.addAll(groovyFiles);
        all.addAll(dartFiles);
        extraFilesByIndexer.values().forEach(all::addAll);

        var changedJava = new ArrayList<Path>();
        boolean newFile = false, otherChanged = false, deleted = false;
        for (Path f : all) {
            String key = trackKey(projectPath, f);
            if (!tracked.contains(key)) { newFile = true; continue; }
            if (changedOnDisk(f, key)) {
                if (f.toString().endsWith(".java")) changedJava.add(f); else otherChanged = true;
            }
        }
        for (String t : tracked) {
            Path p = Path.of(t);
            if (!p.isAbsolute()) p = projectPath.resolve(t);
            if (!Files.exists(p) || inNestedWorktree(projectPath, p)) { deleted = true; break; }
        }

        boolean buildFileChanged = !dependenciesJson(dependencies).equals(store.getConfig("dependencies"));
        if (changedJava.isEmpty() && !newFile && !otherChanged && !deleted) {
            return new Decision(null, buildFileChanged ? "build file changed" : null);
        }
        if (fullSyncFlag.get() != null && !fullSyncFlag.get().isBlank() && !fullSyncFlag.get().equals("0")) {
            return new Decision(null, "forced");
        }
        if (newFile) return new Decision(null, "new file");
        if (deleted) return new Decision(null, "deleted file");
        if (otherChanged) return new Decision(null, "non-Java change");
        if (changedJava.size() * 100 > javaFiles.size() * 30) return new Decision(null, "over 30% of files changed");
        if (buildFileChanged) return new Decision(null, "build file changed");
        for (Path f : changedJava) {
            if (store.findFileShape(projectPath.relativize(f).toString()).isEmpty()) {
                return new Decision(null, "no stored shape");
            }
        }

        var prepared = new ArrayList<Prepared>();
        for (Path f : changedJava) {
            String filePath = projectPath.relativize(f).toString();
            String name = f.getFileName().toString();
            try {
                var cu = StaticJavaParser.parse(f);
                long lastModified = Files.getLastModifiedTime(f).toMillis();
                var typeNodes = new ArrayList<Node>();
                for (Node node : nodeExtractor.extract(cu, filePath)) {
                    typeNodes.add(new Node(node.id(), node.type(), node.name(), node.qualifiedName(),
                        node.filePath(), node.lineNumber(), node.codeSnippet(), lastModified));
                }
                var extracted = methodExtractor.extract(cu, typeNodes, filePath, lastModified);
                var typeIds = new java.util.HashSet<String>();
                for (Node n : typeNodes) typeIds.add(n.id());
                var entryPointIds = new java.util.HashSet<String>(nodeExtractor.entryPointIds(cu));
                entryPointIds.retainAll(typeIds);
                String shape = JavaFileShape.compute(cu, typeNodes, extracted, entryPointIds);
                if (!shape.equals(store.findFileShape(filePath).orElse(null))) {
                    return new Decision(null, "shape changed: " + name);
                }
                var freshIds = new java.util.HashSet<>(typeIds);
                for (var m : extracted) if (m.node() != null) freshIds.add(m.node().id());
                var storedIds = new java.util.HashSet<String>();
                for (Node n : store.findNodesByFilePath(filePath)) storedIds.add(n.id());
                if (!freshIds.equals(storedIds)) return new Decision(null, "shape changed: " + name);
                prepared.add(new Prepared(f, filePath, cu, typeNodes, extracted, lastModified, shape));
            } catch (Exception e) {
                return new Decision(null, "parse error: " + name);
            }
        }
        return new Decision(prepared, null);
    }

    /**
     * Re-extract only the prepared files. Each file's own edges and method rows are deleted and
     * re-emitted; edges from other files into it stay, because its declared shape is unchanged.
     * No TypeScript, Dart, Groovy, Python, SQL or shell pass and no co-change mining runs here.
     */
    private void fastSync(Path projectPath, Project project, NodeExtractor nodeExtractor,
                          List<Dependency> dependencies, List<Prepared> prepared) {
        store.batch(() -> {
            for (Prepared p : prepared) {
                store.deleteEdgesOwnedByFile(p.filePath());
                for (Node n : p.typeNodes()) store.deleteMethodsByNodeId(n.id());
            }
            for (Prepared p : prepared) {
                for (Node n : p.typeNodes()) store.saveNode(n);
                markEntryPoints(nodeExtractor, p.cu(), p.typeNodes());
                saveMethods(p.extracted());
                store.saveFileShape(p.filePath(), p.shape());
                trackFile(projectPath, p.file());
            }

            var known = buildKnownNames();
            var edgeExtractor = new EdgeExtractor(project, store, dependencies, known.sqlTables()).withSourceRoot(projectPath);
            var callExtractor = new MethodCallExtractor(store).withSourceRoot(projectPath);
            var classIds = new java.util.HashSet<String>();
            for (Prepared p : prepared) {
                for (Node n : p.typeNodes()) classIds.add(n.id());
                for (Node sourceNode : store.findNodesByFilePath(p.filePath())) {
                    for (Edge edge : edgeExtractor.extract(p.cu(), sourceNode)) {
                        // USES_LIBRARY is persisted by the extractor when it mints the node.
                        if (edge.type() == EdgeType.USES_LIBRARY) continue;
                        store.saveEdge(edge);
                    }
                }
                if (callExtractor.hasMethods()) {
                    for (Edge edge : callExtractor.extract(p.cu())) store.saveEdge(edge);
                }
            }
            if (!callExtractor.hasMethods()) return;
            for (Edge edge : callExtractor.resolvePending()) store.saveEdge(edge);
            for (Edge edge : callExtractor.deriveOverrides(classIds)) store.saveEdge(edge);
            callExtractor.persistReturnClasses();
        });
    }

    private static String dependenciesJson(List<Dependency> dependencies) {
        return dependencies.stream()
            .map(d -> "\"" + d.coordinate() + ":" + d.version() + "\"")
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    /** Same test as isFileChanged, but writes nothing: the decision must precede every store write. */
    private boolean changedOnDisk(Path file, String key) {
        try {
            long current = Files.getLastModifiedTime(file).toMillis();
            if (current == store.getIndexedFileModifiedTime(key)) return false;
            String indexed = store.getIndexedFileChecksum(key);
            return indexed.isEmpty() || !indexed.equals(computeChecksum(file));
        } catch (IOException e) {
            return true;
        }
    }

    private static String trackKey(Path projectPath, Path file) {
        String name = file.toString();
        return name.endsWith(".java") || name.endsWith(".sh") || name.endsWith(".sql") || name.endsWith(".py")
                ? projectPath.relativize(file).toString()
                : name;
    }

    /**
     * Re-derive every method (signature rows + METHOD nodes) and every edge from scratch, exactly
     * as indexFull does. Edge ids are random UUIDs and method rows autoincrement, so neither
     * dedupes on insert: without clearing both tables first, a rebuild after a sync would double
     * every DECLARES_METHOD edge and append duplicate method signature rows. Called from indexFull
     * and from indexIncremental whenever anything changed, so a sync can never diverge from what a
     * full index would produce.
     *
     * <p>{@link KnownNames} (the SQL table set for {@link EdgeType#REFERENCES_TABLE}) is built once
     * here, from every {@code SQL_TABLE} node already in the store, before the Java edge pass —
     * both {@link #indexFull} and {@link #indexIncremental} save extra-language nodes (SQL included)
     * before calling this method, so the table set is always complete by this point.</p>
     */
    private void rebuildEdgesAndMethods(List<Path> javaFiles, List<Path> tsFiles, List<Path> dartFiles,
                                        Map<LanguageIndexer, List<Path>> extraFilesByIndexer,
                                        Path projectPath, Project project, List<Dependency> dependencies) {
        store.deleteAllEdges();
        store.deleteAllMethods();
        afterEdgesCleared.run();
        // The shape of a file that no longer parses must not outlive its nodes; return classes follow the methods.
        store.deleteAllFileShapes();
        store.deleteAllReturnClasses();
        // LIBRARY nodes are minted by the edge pass below; one left from an earlier run would make
        // this run see a different graph than the first did. METHOD nodes are re-minted below
        // too: a method of a colliding type id belongs to whichever file owns the type now.
        store.batch(() -> {
            for (Node n : store.findNodesByType(NodeType.LIBRARY)) store.deleteNode(n.id());
            for (Node n : store.findNodesByType(NodeType.METHOD)) store.deleteNode(n.id());
        });

        var known = buildKnownNames();
        var edgeExtractor = new EdgeExtractor(project, store, dependencies, known.sqlTables()).withSourceRoot(projectPath);

        // Methods from Java files (signature rows + METHOD nodes)
        store.batch(() -> {
        for (Path file : javaFiles) {
            try {
                var cu = StaticJavaParser.parse(file);
                var filePath = projectPath.relativize(file).toString();
                long lastModified = Files.getLastModifiedTime(file).toMillis();
                var fileNodes = store.findNodesByFilePath(filePath);
                var extracted = methodExtractor.extract(cu, fileNodes, filePath, lastModified);
                saveMethods(extracted);
                saveFileShape(cu, filePath, fileNodes, extracted);
            } catch (Exception e) {
                // Skip unparseable files
            }
        }
        });

        // Methods from TS files
        var tsMethods = new ArrayList<TypeScriptMethodExtractor.TsMethod>();
        store.batch(() -> {
            for (Path file : tsFiles) {
                var nodes = store.findNodesByFilePath(file.toString());
                tsMethods.addAll(saveTsMethods(file, nodes));
            }
        });

        // Methods from Dart files
        var dartMethods = new ArrayList<DartMethodExtractor.DartMethod>();
        store.batch(() -> {
            for (Path file : dartFiles) {
                var nodes = store.findNodesByFilePath(file.toString());
                dartMethods.addAll(saveDartMethods(file, nodes));
            }
        });

        extractAndSaveEdges(javaFiles, projectPath, edgeExtractor);
        extractFeEdges();
        extractTsCallEdges(tsMethods);
        extractDartEdges(dartFiles, dartMethods);
        extractExtraLanguageEdges(extraFilesByIndexer, projectPath, known);
    }

    /** The lowercase SQL table names every extra-language pass (Java, Python, shell) resolves against. */
    private KnownNames buildKnownNames() {
        var sqlTables = new java.util.HashSet<String>();
        for (Node n : store.findNodesByType(NodeType.SQL_TABLE)) {
            sqlTables.add(n.name());
        }
        return new KnownNames(sqlTables);
    }

    // ---- Shared extraction helpers ----

    /** Store the shape hash of one Java file, from what is stored about its types (entry points included). */
    private void saveFileShape(com.github.javaparser.ast.CompilationUnit cu, String filePath, List<Node> fileNodes,
                               List<MethodExtractor.ExtractedMethod> extracted) {
        var typeNodes = fileNodes.stream().filter(n -> n.type() != NodeType.METHOD).toList();
        var entryPointIds = new java.util.HashSet<String>();
        for (Node n : typeNodes) if (store.isEntryPoint(n.id())) entryPointIds.add(n.id());
        store.saveFileShape(filePath, JavaFileShape.compute(cu, typeNodes, extracted, entryPointIds));
    }

    /**
     * Persist a file's extracted methods: the signature row always, and for real methods (not
     * record components) the METHOD node plus the DECLARES_METHOD edge that binds it to its class.
     */
    private void saveMethods(List<MethodExtractor.ExtractedMethod> methods) {
        for (var method : methods) {
            if (method.record() != null) store.saveMethod(method.record());
            if (method.node() == null) continue;       // record component: data, not behaviour
            store.saveNode(method.node());
            store.saveEdge(new Edge(UUID.randomUUID().toString(), EdgeType.DECLARES_METHOD,
                method.declaringClassId(), method.node().id()));
        }
    }

    /**
     * Persist METHOD nodes and DECLARES_METHOD edges for one TypeScript file, returning what was
     * found so the call pass can resolve against it once every class is in the graph.
     */
    private List<TypeScriptMethodExtractor.TsMethod> saveTsMethods(Path file, List<Node> classNodes) {
        String content;
        long lastModified;
        try {
            content = Files.readString(file);
            lastModified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return List.of();
        }

        var found = tsMethodExtractor.extract(content, classNodes, file.toString(), lastModified);
        for (var method : found) {
            store.saveNode(method.node());
            store.saveEdge(new Edge(UUID.randomUUID().toString(), EdgeType.DECLARES_METHOD,
                method.ownerName(), method.node().id()));
        }
        return found;
    }

    /** Resolve TypeScript method -> method calls, after every TS class and method node exists. */
    private void extractTsCallEdges(List<TypeScriptMethodExtractor.TsMethod> tsMethods) {
        if (tsMethods.isEmpty()) return;

        var knownTypes = new java.util.HashSet<String>();
        for (Node n : store.getAllNodes()) {
            if (n.type() != NodeType.METHOD) knownTypes.add(n.name());
        }

        var edges = tsMethodExtractor.resolveCalls(tsMethods, knownTypes);
        store.batch(() -> {
            for (Edge edge : edges) store.saveEdge(edge);
        });

        int seen = tsMethodExtractor.callSitesSeen();
        int resolved = tsMethodExtractor.callSitesResolved();
        int external = tsMethodExtractor.receiverExternal();
        int judged = seen - external;
        System.err.printf("TypeScript call graph: %d method(s); %d/%d calls into project types "
                + "bound (%.0f%%), %d into framework types.%n",
            tsMethods.size(), resolved, judged,
            judged <= 0 ? 100.0 : (100.0 * resolved / judged), external);
    }

    /**
     * Persist METHOD nodes and DECLARES_METHOD edges for one Dart file, returning what was found so
     * the call pass can resolve against it once every class is in the graph.
     */
    private List<DartMethodExtractor.DartMethod> saveDartMethods(Path file, List<Node> classNodes) {
        String content;
        long lastModified;
        try {
            content = Files.readString(file);
            lastModified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return List.of();
        }

        var found = dartMethodExtractor.extract(content, classNodes, file.toString(), lastModified);
        for (var method : found) {
            store.saveNode(method.node());
            store.saveEdge(new Edge(UUID.randomUUID().toString(), EdgeType.DECLARES_METHOD,
                method.ownerName(), method.node().id()));
        }
        return found;
    }

    /**
     * Structural (EXTENDS/IMPLEMENTS) and call-graph edges for Dart, once every Dart class and
     * method node exists.
     */
    private void extractDartEdges(List<Path> dartFiles, List<DartMethodExtractor.DartMethod> dartMethods) {
        var knownTypes = new java.util.HashSet<String>();
        for (Node n : store.getAllNodes()) {
            if (n.type() != NodeType.METHOD) knownTypes.add(n.name());
        }

        store.batch(() -> {
            for (Path file : dartFiles) {
                for (Edge edge : dartIndexer.classEdges(file, knownTypes)) {
                    store.saveEdge(edge);
                }
            }
        });

        if (dartMethods.isEmpty()) return;

        var edges = dartMethodExtractor.resolveCalls(dartMethods, knownTypes);
        store.batch(() -> {
            for (Edge edge : edges) store.saveEdge(edge);
        });

        int seen = dartMethodExtractor.callSitesSeen();
        int resolved = dartMethodExtractor.callSitesResolved();
        int external = dartMethodExtractor.receiverExternal();
        int judged = seen - external;
        System.err.printf("Dart call graph: %d method(s); %d/%d calls into project types "
                + "bound (%.0f%%), %d into framework/SDK types.%n",
            dartMethods.size(), resolved, judged,
            judged <= 0 ? 100.0 : (100.0 * resolved / judged), external);
    }

    /**
     * Methods and edges for every extra-language file (shell today), once every extra-language
     * top-level node exists. Mirrors {@link #extractAndSaveEdges}: methods first so their nodes
     * exist, then {@code fileNodes} is re-read from the store so edge extraction sees both.
     */
    private void extractExtraLanguageEdges(Map<LanguageIndexer, List<Path>> extraFilesByIndexer,
                                           Path projectPath, KnownNames known) {
        for (var entry : extraFilesByIndexer.entrySet()) {
            LanguageIndexer indexer = entry.getKey();
            for (Path file : entry.getValue()) {
                String filePath = projectPath.relativize(file).toString();
                var methodNodes = indexer.extractMethods(file, store.findNodesByFilePath(filePath));
                store.batch(() -> {
                    for (Node node : methodNodes) store.saveNode(node);
                });

                var edges = indexer.extractEdges(file, store.findNodesByFilePath(filePath), known);
                store.batch(() -> {
                    for (Edge edge : edges) {
                        mintShellCommandNodeIfNeeded(edge);
                        store.saveEdge(edge);
                    }
                });
            }
        }
    }

    /**
     * A RUNS edge's target is a {@link NodeType#SHELL_COMMAND}, created lazily and shared across
     * scripts the first time any script is seen running it — the same pattern {@code
     * EdgeExtractor} uses to mint a {@code LIBRARY} node for {@code USES_LIBRARY}.
     */
    private void mintShellCommandNodeIfNeeded(Edge edge) {
        if (edge.type() != EdgeType.RUNS) return;
        if (store.findNodeById(edge.targetId()).isPresent()) return;
        store.saveNode(new Node(edge.targetId(), NodeType.SHELL_COMMAND, edge.targetId(),
            edge.targetId(), "", 0, "", 0L));
    }

    /**
     * Edge pass: class-level edges and method -> method CALLS from the same parse of each file,
     * then OVERRIDES derived once the supertype edges exist.
     */
    private void extractAndSaveEdges(List<Path> javaFiles, Path projectPath, EdgeExtractor edgeExtractor) {
        // Built here, after phase 1c, so every class and method node is already in the graph —
        // resolving a call means looking the receiver's methods up.
        var callExtractor = new MethodCallExtractor(store).withSourceRoot(projectPath);

        store.batch(() -> {
            for (Path file : javaFiles) {
                try {
                    var cu = StaticJavaParser.parse(file);
                    var filePath = projectPath.relativize(file).toString();
                    var fileNodes = store.findNodesByFilePath(filePath);
                    for (Node sourceNode : fileNodes) {
                        for (Edge edge : edgeExtractor.extract(cu, sourceNode)) {
                            // USES_LIBRARY is persisted by the extractor when it mints the node.
                            if (edge.type() == EdgeType.USES_LIBRARY) continue;
                            store.saveEdge(edge);
                        }
                    }
                    if (callExtractor.hasMethods()) {
                        for (Edge edge : callExtractor.extract(cu)) {
                            store.saveEdge(edge);
                        }
                    }
                } catch (Exception e) {
                    // Skip unparseable files
                }
            }
        });

        if (!callExtractor.hasMethods()) return;

        store.batch(() -> {
            // Supertype edges are complete now, so parked calls through an interface can bind.
            for (Edge edge : callExtractor.resolvePending()) {
                store.saveEdge(edge);
            }
            for (Edge edge : callExtractor.deriveOverrides()) {
                store.saveEdge(edge);
            }
            // Every file of the run has been seen, so a return type naming a later file resolves.
            callExtractor.persistReturnClasses();
        });

        int seen = callExtractor.callSitesSeen();
        int resolved = callExtractor.callSitesResolved();
        int accessors = callExtractor.accessorMiss();
        // Judged only on calls that could name a method: generated accessors and data-type reads
        // are field access, and external receivers are out of scope. An unresolved call that is
        // none of those is a real hole in the call graph, so it stays visible.
        int judged = callExtractor.receiverBehavioural() - accessors;
        System.err.printf("Method call graph: %d/%d calls bound (%.0f%%).%n"
                + "  %d call sites total: %d external, %d data-type reads, %d generated accessors.%n",
            resolved, judged, judged <= 0 ? 100.0 : (100.0 * resolved / judged),
            seen, callExtractor.receiverExternal(), callExtractor.receiverDataType(), accessors);

        if (System.getenv("CODE_NAVIGATOR_DEBUG_CALLS") != null) {
            System.err.println("  Top unresolved call shapes:");
            for (var entry : callExtractor.topUnresolved(25)) {
                System.err.printf("    %6d  %s%n", entry.getValue(), entry.getKey());
            }
        }
    }

    private void markEntryPoints(NodeExtractor nodeExtractor, com.github.javaparser.ast.CompilationUnit cu, List<Node> nodes) {
        var entryPoints = nodeExtractor.entryPointIds(cu);
        for (Node node : nodes) store.setEntryPoint(node.id(), entryPoints.contains(node.id()));
    }

    /**
     * Phase 1 for Java: every file's type nodes and entry points, in the order of {@code javaFiles}
     * (sorted by path). A nested type's id carries its outer types ({@code com.acme.CommandBus.Chain}),
     * so only two top-level types of one fqn can still share a node; the later file in path order owns
     * it. Saving in a fixed order keeps that choice the same on every run and every copy of the tree.
     */
    private void saveJavaNodes(List<Path> javaFiles, Path projectPath, NodeExtractor nodeExtractor) {
        for (Path file : javaFiles) {
            try {
                var cu = StaticJavaParser.parse(file);
                var filePath = projectPath.relativize(file).toString();
                var nodes = nodeExtractor.extract(cu, filePath);
                for (Node node : nodes) {
                    long lastModified = Files.getLastModifiedTime(file).toMillis();
                    var nodeWithTime = new Node(node.id(), node.type(), node.name(),
                            node.qualifiedName(), node.filePath(), node.lineNumber(),
                            node.codeSnippet(), lastModified);
                    store.saveNode(nodeWithTime);
                }
                markEntryPoints(nodeExtractor, cu, nodes);
            } catch (Exception e) {
                // Skip unparseable files
            }
        }
    }

    /**
     * When any Java file changed, drop its nodes and save every file's nodes again in path order,
     * as indexFull does: a changed file that owned a colliding id may hand it to another file, and
     * one that did not must not take it. A file that no longer parses keeps no nodes, as in indexFull.
     */
    private boolean reindexChangedJavaFiles(List<Path> javaFiles, Path projectPath, NodeExtractor nodeExtractor) {
        var changed = new ArrayList<String>();
        for (Path file : javaFiles) {
            var filePath = projectPath.relativize(file).toString();
            try {
                if (isFileChanged(file, filePath)) changed.add(filePath);
            } catch (Exception e) {
                // Skip
            }
        }
        if (changed.isEmpty()) return false;
        for (String filePath : changed) store.deleteNodesByFilePath(filePath);
        saveJavaNodes(javaFiles, projectPath, nodeExtractor);
        return true;
    }

    private boolean reindexChangedTsFiles(List<Path> tsFiles) {
        boolean anyChanged = false;
        for (Path file : tsFiles) {
            var filePath = file.toString();
            try {
                if (!isFileChanged(file, filePath)) continue;
                anyChanged = true;
                store.deleteNodesByFilePath(filePath);
                var nodes = tsIndexer.indexFile(file);
                for (Node node : nodes) {
                    store.saveNode(node);
                }
                saveTsMethods(file, nodes);
            } catch (Exception e) {
                // Skip
            }
        }
        return anyChanged;
    }

    private boolean reindexChangedGroovyFiles(List<Path> groovyFiles) {
        boolean anyChanged = false;
        for (Path file : groovyFiles) {
            var filePath = file.toString();
            try {
                if (!isFileChanged(file, filePath)) continue;
                anyChanged = true;
                store.deleteNodesByFilePath(filePath);
                var nodes = groovyIndexer.indexFile(file);
                for (Node node : nodes) {
                    store.saveNode(node);
                }
            } catch (Exception e) {
                // Skip
            }
        }
        return anyChanged;
    }

    private boolean reindexChangedDartFiles(List<Path> dartFiles) {
        boolean anyChanged = false;
        for (Path file : dartFiles) {
            var filePath = file.toString();
            try {
                if (!isFileChanged(file, filePath)) continue;
                anyChanged = true;
                store.deleteNodesByFilePath(filePath);
                var nodes = dartIndexer.indexFile(file);
                for (Node node : nodes) {
                    store.saveNode(node);
                }
                saveDartMethods(file, nodes);
            } catch (Exception e) {
                // Skip
            }
        }
        return anyChanged;
    }

    /**
     * Re-index changed files for every extra-language indexer: delete this file's nodes, re-run
     * {@code indexFile}, re-save. Methods and edges are not extracted here — like Java/TS/Dart,
     * {@link #rebuildEdgesAndMethods} re-derives all of them from scratch for every extra-language
     * file whenever anything changed, mirroring {@link #reindexChangedDartFiles}.
     */
    private boolean reindexChangedExtraFiles(Map<LanguageIndexer, List<Path>> extraFilesByIndexer, Path projectPath) {
        boolean anyChanged = false;
        for (var entry : extraFilesByIndexer.entrySet()) {
            LanguageIndexer indexer = entry.getKey();
            List<Path> files = entry.getValue();
            // Whole-history indexers (SQL) need the model rebuilt from every file, every run,
            // before indexFile/extractEdges can answer for any one of them.
            indexer.prepare(files, projectPath);

            if (indexer.rebuildAllOnChange()) {
                if (anyExtraFileChangedOrDeleted(indexer, files, projectPath)) {
                    for (NodeType type : indexer.ownedTypes()) {
                        for (Node node : store.findNodesByType(type)) {
                            store.deleteNode(node.id());
                        }
                    }
                    for (Path file : files) {
                        for (Node node : indexer.indexFile(file, projectPath)) {
                            store.saveNode(node);
                        }
                    }
                    anyChanged = true;
                }
                continue;
            }

            for (Path file : files) {
                var filePath = projectPath.relativize(file).toString();
                try {
                    if (!isFileChanged(file, filePath)) continue;
                    anyChanged = true;
                    store.deleteNodesByFilePath(filePath);
                    for (Node node : indexer.indexFile(file, projectPath)) {
                        store.saveNode(node);
                    }
                } catch (Exception e) {
                    // Skip
                }
            }
        }
        return anyChanged;
    }

    /**
     * True if any of {@code files} changed content, or a previously tracked file this indexer
     * matches no longer exists — either forces a {@link LanguageIndexer#rebuildAllOnChange}
     * indexer to redo its whole-history scan.
     */
    private boolean anyExtraFileChangedOrDeleted(LanguageIndexer indexer, List<Path> files, Path projectPath) {
        for (Path file : files) {
            var filePath = projectPath.relativize(file).toString();
            try {
                if (isFileChanged(file, filePath)) return true;
            } catch (IOException e) {
                return true;
            }
        }
        for (String tracked : store.getAllIndexedFiles()) {
            Path p = Path.of(tracked);
            if (!p.isAbsolute()) p = projectPath.resolve(tracked);
            if (indexer.matches(p) && !Files.exists(p)) return true;
        }
        return false;
    }

    /**
     * Drop nodes and tracking rows for files that were indexed earlier but no longer exist on disk,
     * or that an older jar indexed inside a nested worktree (see inNestedWorktree).
     * Tracked paths are relative for Java files and absolute for TS/Groovy files (see trackFile),
     * and older indexes stored "./"-prefixed relative paths; resolve non-absolute ones against
     * projectPath. Returns true if anything was pruned, so callers re-extract edges.
     */
    private boolean pruneDeletedFiles(Path projectPath) {
        boolean anyPruned = false;
        for (String tracked : store.getAllIndexedFiles()) {
            Path p = Path.of(tracked);
            if (!p.isAbsolute()) p = projectPath.resolve(tracked);
            if (!Files.exists(p) || inNestedWorktree(projectPath, p)) {
                store.deleteNodesByFilePath(tracked);
                store.removeIndexedFile(tracked);
                anyPruned = true;
            }
        }
        return anyPruned;
    }

    /**
     * Check if a file has changed by comparing timestamp and content hash.
     * Updates the timestamp in indexed_files if only the timestamp changed (same content).
     * Returns true if the file content actually changed and needs re-indexing.
     */
    private boolean isFileChanged(Path file, String filePath) throws IOException {
        long currentModified = Files.getLastModifiedTime(file).toMillis();
        long indexedModified = store.getIndexedFileModifiedTime(filePath);
        if (currentModified == indexedModified) return false;

        String currentChecksum = computeChecksum(file);
        String indexedChecksum = store.getIndexedFileChecksum(filePath);
        if (!indexedChecksum.isEmpty() && indexedChecksum.equals(currentChecksum)) {
            // Content unchanged, just update timestamp
            store.saveIndexedFile(filePath, currentModified, currentChecksum);
            return false;
        }
        return true;
    }

    // ---- Frontend edge extraction ----

    private void extractFeEdges() {
        // CALLS_API: FE_SERVICE -> CONTROLLER
        var services = store.findNodesByType(NodeType.FE_SERVICE);
        var controllers = store.findNodesByType(NodeType.CONTROLLER);

        for (Node service : services) {
            String snippet = service.codeSnippet();
            if (snippet == null) continue;

            for (Node controller : controllers) {
                String controllerBase = controller.name();
                if (controllerBase.endsWith("Controller")) {
                    controllerBase = controllerBase.substring(0, controllerBase.length() - "Controller".length());
                }
                String urlSegment = "/" + controllerBase.toLowerCase();
                String urlSegmentPlural = urlSegment + "s";

                if (snippet.contains(urlSegment) || snippet.contains(urlSegmentPlural)) {
                    store.saveEdge(new Edge(UUID.randomUUID().toString(),
                            EdgeType.CALLS_API, service.id(), controller.id()));
                }
            }
        }

        // USES_SERVICE: FE_COMPONENT -> FE_SERVICE
        var components = store.findNodesByType(NodeType.FE_COMPONENT);
        for (Node component : components) {
            String filePath = component.filePath();
            if (filePath == null) continue;

            try {
                String content = Files.readString(Path.of(filePath));
                for (Node service : services) {
                    if (content.contains("inject(" + service.name() + ")")) {
                        store.saveEdge(new Edge(UUID.randomUUID().toString(),
                                EdgeType.USES_SERVICE, component.id(), service.id()));
                    }
                }
            } catch (IOException e) {
                // Skip unreadable files
            }
        }
    }

    // ---- File scanning ----

    private List<Path> findJavaFiles(Path projectPath) {
        return findFiles(projectPath, file ->
                file.toString().endsWith(".java") && !isExcluded(file));
    }

    private List<Path> findTsFiles(Path projectPath) {
        return findFiles(projectPath, file -> {
            String name = file.toString();
            return name.endsWith(".ts")
                    && !name.endsWith(".spec.ts")
                    && !name.endsWith(".d.ts")
                    && !name.endsWith(".module.ts")
                    && !name.contains("node_modules");
        });
    }

    private List<Path> findGroovyFiles(Path projectPath) {
        return findFiles(projectPath, file ->
                file.toString().endsWith(".groovy") && !isExcluded(file));
    }

    private List<Path> findDartFiles(Path projectPath) {
        return findFiles(projectPath, file -> {
            String name = file.toString();
            return name.endsWith(".dart")
                    && !name.endsWith(".g.dart")
                    && !name.endsWith(".freezed.dart")
                    && !name.contains("/l10n/") // flutter gen-l10n output
                    && !name.contains(".dart_tool")
                    && !isExcluded(file);
        });
    }

    /** Every {@link LanguageIndexer}'s files for this run, discovered once and reused across phases. */
    private Map<LanguageIndexer, List<Path>> collectExtraFiles(Path projectPath) {
        var byIndexer = new LinkedHashMap<LanguageIndexer, List<Path>>();
        for (LanguageIndexer indexer : extraIndexers) {
            byIndexer.put(indexer, findExtraFiles(projectPath, indexer));
        }
        return byIndexer;
    }

    private List<Path> findExtraFiles(Path projectPath, LanguageIndexer indexer) {
        return findFiles(projectPath, indexer::matches);
    }

    private List<Path> findFiles(Path projectPath, Predicate<Path> filter) {
        var files = new ArrayList<Path>();
        try {
            Files.walkFileTree(projectPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return inNestedWorktree(projectPath, dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (filter.test(file)) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // Return what we have
        }
        // Directory order differs between copies of one tree; a fixed order keeps every run, and
        // the choice among files that declare the same node id, the same.
        files.sort(null);
        return files;
    }

    /**
     * True for {@code <root>/.claude/worktrees} and everything below it. Each git worktree there is
     * a full copy of the project with the same node ids: the nlp root held 76 of them, 207,898 of
     * its 213,717 indexed files, and neither `init` nor `sync` finished inside the hook's 15 minutes.
     * A worktree is indexed as its own root when a session opens it.
     */
    static boolean inNestedWorktree(Path projectPath, Path path) {
        return path.startsWith(projectPath.resolve(".claude").resolve("worktrees"));
    }

    /**
     * Nodes whose file sits in a nested worktree but has no tracking row: a run killed half-way
     * (the hook's timeout) stores nodes before it tracks their file, so the file prune misses them.
     * 2,159 such nodes stayed in the nlp root after a clean re-init.
     */
    private void pruneNestedWorktreeNodes(Path projectPath) {
        for (Node node : store.getAllNodes()) {
            String filePath = node.filePath();
            if (filePath == null || !filePath.contains("worktrees")) continue;
            Path p = Path.of(filePath);
            if (!p.isAbsolute()) p = projectPath.resolve(filePath);
            if (inNestedWorktree(projectPath, p)) store.deleteNode(node.id());
        }
    }

    private boolean isExcluded(Path file) {
        String path = file.toString();
        return path.contains("build") || path.contains(".gradle") || path.contains("node_modules")
                || path.contains(".dart_tool");
    }

    // ---- File tracking ----

    private void trackFiles(Path projectPath, List<Path> javaFiles, List<Path> tsFiles,
                            List<Path> groovyFiles, List<Path> dartFiles,
                            Map<LanguageIndexer, List<Path>> extraFilesByIndexer) {
        for (Path file : javaFiles) {
            trackFile(projectPath, file);
        }
        for (Path file : tsFiles) {
            trackFile(projectPath, file);
        }
        for (Path file : groovyFiles) {
            trackFile(projectPath, file);
        }
        for (Path file : dartFiles) {
            trackFile(projectPath, file);
        }
        for (List<Path> files : extraFilesByIndexer.values()) {
            for (Path file : files) {
                trackFile(projectPath, file);
            }
        }
    }

    private void trackFile(Path projectPath, Path file) {
        try {
            String filePath = trackKey(projectPath, file);
            long lastModified = Files.getLastModifiedTime(file).toMillis();
            String checksum = computeChecksum(file);
            store.saveIndexedFile(filePath, lastModified, checksum);
        } catch (IOException e) {
            // Skip
        }
    }

    // ---- Utilities ----

    /** The one place the parser language level is set; DiffSymbols calls it too so the two cannot drift. */
    public static void configureParser() {
        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
    }

    private String computeChecksum(Path file) {
        try {
            byte[] content = Files.readAllBytes(file);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content);
            return HexFormat.of().formatHex(hash);
        } catch (IOException | NoSuchAlgorithmException e) {
            return "";
        }
    }
}
