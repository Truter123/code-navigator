package com.codenavigator.indexer;

import com.codenavigator.graph.*;
import com.codenavigator.git.GitHistoryAnalyzer;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

public class ProjectIndexer {

    private final GraphStore store;
    private final ProjectDetector projectDetector;
    private final TypeScriptIndexer tsIndexer;
    private final GroovyIndexer groovyIndexer;
    private final MethodExtractor methodExtractor;
    private final TypeScriptMethodExtractor tsMethodExtractor;
    private final DependencyParser dependencyParser;
    private final com.codenavigator.embedding.EmbeddingProvider embeddingProvider;

    public ProjectIndexer(GraphStore store) {
        this(store, new com.codenavigator.embedding.NoopEmbeddingProvider());
    }

    public ProjectIndexer(GraphStore store, com.codenavigator.embedding.EmbeddingProvider embeddingProvider) {
        this.store = store;
        this.embeddingProvider = embeddingProvider;
        this.projectDetector = new ProjectDetector();
        this.tsIndexer = new TypeScriptIndexer();
        this.groovyIndexer = new GroovyIndexer();
        this.methodExtractor = new MethodExtractor();
        this.tsMethodExtractor = new TypeScriptMethodExtractor();
        this.dependencyParser = new DependencyParser();
    }

    public void indexFull(Path projectPath) {
        configureParser();

        // 0. Start from empty. Edge ids are random UUIDs and method rows are autoincrement, so
        // INSERT OR REPLACE cannot dedupe either: without this, a second `init` over an existing
        // database doubles every edge (25,823 -> 52,088 on nlp) and every traversal silently
        // reports each neighbour twice. Nodes are keyed by id and so replace cleanly, though a
        // node whose file has since been deleted survives until indexIncremental prunes it.
        store.deleteAllEdges();
        store.deleteAllMethods();

        // 1. Detect project type
        Project project = projectDetector.detect(projectPath);
        store.setConfig("tier", project.name());

        // 1b. Parse declared dependencies for library boundary indexing
        List<Dependency> dependencies = dependencyParser.parse(projectPath);

        // Persist declared dependencies for the cg_deps tool
        String depsJson = dependencies.stream()
            .map(d -> "\"" + d.coordinate() + ":" + d.version() + "\"")
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        store.setConfig("dependencies", depsJson);

        var nodeExtractor = new NodeExtractor(project);
        var edgeExtractor = new EdgeExtractor(project, store, dependencies);

        // 2. Find all files
        List<Path> javaFiles = findJavaFiles(projectPath);
        List<Path> tsFiles = findTsFiles(projectPath);
        List<Path> groovyFiles = findGroovyFiles(projectPath);

        // 3. Phase 1: Extract nodes from Java files
        store.batch(() -> {
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
                    embedNode(nodeWithTime);
                }
            } catch (Exception e) {
                // Skip unparseable files
            }
        }
        });

        // Phase 1c: Extract methods from Java files (signature rows + METHOD nodes)
        store.batch(() -> {
        for (Path file : javaFiles) {
            try {
                var cu = StaticJavaParser.parse(file);
                var filePath = projectPath.relativize(file).toString();
                long lastModified = Files.getLastModifiedTime(file).toMillis();
                var fileNodes = store.findNodesByFilePath(filePath);
                saveMethods(methodExtractor.extract(cu, fileNodes, filePath, lastModified));
            } catch (Exception e) {
                // Skip unparseable files
            }
        }
        });

        // 4. Phase 1b: Extract nodes from TS files, then their methods
        var tsMethods = new ArrayList<TypeScriptMethodExtractor.TsMethod>();
        store.batch(() -> {
            for (Path file : tsFiles) {
                var nodes = tsIndexer.indexFile(file);
                for (Node node : nodes) {
                    store.saveNode(node);
                    embedNode(node);
                }
                tsMethods.addAll(saveTsMethods(file, nodes));
            }
        });

        // 4b. Phase 1d: Extract nodes from Groovy files (CI/CD scripts; no edges)
        for (Path file : groovyFiles) {
            var nodes = groovyIndexer.indexFile(file);
            for (Node node : nodes) {
                store.saveNode(node);
                embedNode(node);
            }
        }

        // 5. Phase 2: Extract edges
        extractAndSaveEdges(javaFiles, projectPath, edgeExtractor);
        extractFeEdges();
        extractTsCallEdges(tsMethods);

        // 6. Track indexed files
        trackFiles(projectPath, javaFiles, tsFiles, groovyFiles);

        // 7. Mine git co-change coupling (silently skipped if not a git repo)
        var gitAnalyzer = new GitHistoryAnalyzer(store);
        gitAnalyzer.analyze(projectPath, 500);
        if (gitAnalyzer.skippedBulkCommits() > 0) {
            System.err.printf("Co-change: skipped %d bulk commit(s) touching more than 50 files.%n",
                gitAnalyzer.skippedBulkCommits());
        }
    }

    public void indexIncremental(Path projectPath) {
        configureParser();

        // 1. Read project type from config
        String projectStr = store.getConfig("tier");
        Project project = projectStr != null ? Project.valueOf(projectStr) : projectDetector.detect(projectPath);
        store.setConfig("tier", project.name());

        List<Dependency> dependencies = dependencyParser.parse(projectPath);

        var nodeExtractor = new NodeExtractor(project);
        var edgeExtractor = new EdgeExtractor(project, store, dependencies);

        // 2. Find all files
        List<Path> javaFiles = findJavaFiles(projectPath);
        List<Path> tsFiles = findTsFiles(projectPath);
        List<Path> groovyFiles = findGroovyFiles(projectPath);

        // 3. Re-index changed files
        boolean anyChanged = reindexChangedJavaFiles(javaFiles, projectPath, nodeExtractor);
        anyChanged |= reindexChangedTsFiles(tsFiles);
        anyChanged |= reindexChangedGroovyFiles(groovyFiles);

        // 4. Full edge re-extraction if anything changed
        if (anyChanged) {
            store.deleteAllEdges();
            extractAndSaveEdges(javaFiles, projectPath, edgeExtractor);
            extractFeEdges();
        }

        // 5. Update tracked files
        trackFiles(projectPath, javaFiles, tsFiles, groovyFiles);
    }

    // ---- Shared extraction helpers ----

    /**
     * Persist a file's extracted methods: the signature row always, and for real methods (not
     * record components) the METHOD node plus the DECLARES_METHOD edge that binds it to its class.
     */
    private void saveMethods(List<MethodExtractor.ExtractedMethod> methods) {
        for (var method : methods) {
            store.saveMethod(method.record());
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
     * Edge pass: class-level edges and method -> method CALLS from the same parse of each file,
     * then OVERRIDES derived once the supertype edges exist.
     */
    private void extractAndSaveEdges(List<Path> javaFiles, Path projectPath, EdgeExtractor edgeExtractor) {
        // Built here, after phase 1c, so every class and method node is already in the graph —
        // resolving a call means looking the receiver's methods up.
        var callExtractor = new MethodCallExtractor(store);

        store.batch(() -> {
            for (Path file : javaFiles) {
                try {
                    var cu = StaticJavaParser.parse(file);
                    var filePath = projectPath.relativize(file).toString();
                    var fileNodes = store.findNodesByFilePath(filePath);
                    for (Node sourceNode : fileNodes) {
                        for (Edge edge : edgeExtractor.extract(cu, sourceNode)) {
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

    private boolean reindexChangedJavaFiles(List<Path> javaFiles, Path projectPath, NodeExtractor nodeExtractor) {
        boolean anyChanged = false;
        for (Path file : javaFiles) {
            var filePath = projectPath.relativize(file).toString();
            try {
                if (!isFileChanged(file, filePath)) continue;
                anyChanged = true;
                store.deleteNodesByFilePath(filePath);
                long currentModified = Files.getLastModifiedTime(file).toMillis();
                var cu = StaticJavaParser.parse(file);
                var nodes = nodeExtractor.extract(cu, filePath);
                for (Node node : nodes) {
                    var nodeWithTime = new Node(node.id(), node.type(), node.name(),
                            node.qualifiedName(), node.filePath(), node.lineNumber(),
                            node.codeSnippet(), currentModified);
                    store.saveNode(nodeWithTime);
                    embedNode(nodeWithTime);
                }

                // Re-extract methods for this file's nodes
                var updatedNodes = store.findNodesByFilePath(filePath);
                saveMethods(methodExtractor.extract(cu, updatedNodes, filePath, currentModified));
            } catch (Exception e) {
                // Skip
            }
        }
        return anyChanged;
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
                    embedNode(node);
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
                    embedNode(node);
                }
            } catch (Exception e) {
                // Skip
            }
        }
        return anyChanged;
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

    private List<Path> findFiles(Path projectPath, Predicate<Path> filter) {
        var files = new ArrayList<Path>();
        try {
            Files.walkFileTree(projectPath, new SimpleFileVisitor<>() {
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
        return files;
    }

    private boolean isExcluded(Path file) {
        String path = file.toString();
        return path.contains("build") || path.contains(".gradle") || path.contains("node_modules");
    }

    // ---- File tracking ----

    private void trackFiles(Path projectPath, List<Path> javaFiles, List<Path> tsFiles,
                            List<Path> groovyFiles) {
        for (Path file : javaFiles) {
            trackFile(projectPath, file);
        }
        for (Path file : tsFiles) {
            trackFile(projectPath, file);
        }
        for (Path file : groovyFiles) {
            trackFile(projectPath, file);
        }
    }

    private void trackFile(Path projectPath, Path file) {
        try {
            String filePath = file.toString().endsWith(".java")
                    ? projectPath.relativize(file).toString()
                    : file.toString();
            long lastModified = Files.getLastModifiedTime(file).toMillis();
            String checksum = computeChecksum(file);
            store.saveIndexedFile(filePath, lastModified, checksum);
        } catch (IOException e) {
            // Skip
        }
    }

    // ---- Utilities ----

    private static void configureParser() {
        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
    }

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
