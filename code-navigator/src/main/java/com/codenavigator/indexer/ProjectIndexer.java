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
    private final MethodExtractor methodExtractor;
    private final DependencyParser dependencyParser;

    public ProjectIndexer(GraphStore store) {
        this.store = store;
        this.projectDetector = new ProjectDetector();
        this.tsIndexer = new TypeScriptIndexer();
        this.methodExtractor = new MethodExtractor();
        this.dependencyParser = new DependencyParser();
    }

    public void indexFull(Path projectPath) {
        configureParser();

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

        // 3. Phase 1: Extract nodes from Java files
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
            } catch (Exception e) {
                // Skip unparseable files
            }
        }

        // Phase 1c: Extract methods from Java files
        for (Path file : javaFiles) {
            try {
                var cu = StaticJavaParser.parse(file);
                var filePath = projectPath.relativize(file).toString();
                var fileNodes = store.findNodesByFilePath(filePath);
                var methods = methodExtractor.extract(cu, fileNodes);
                for (var method : methods) {
                    store.saveMethod(method);
                }
            } catch (Exception e) {
                // Skip unparseable files
            }
        }

        // 4. Phase 1b: Extract nodes from TS files
        for (Path file : tsFiles) {
            var nodes = tsIndexer.indexFile(file);
            for (Node node : nodes) {
                store.saveNode(node);
            }
        }

        // 5. Phase 2: Extract edges
        extractAndSaveEdges(javaFiles, projectPath, edgeExtractor);
        extractFeEdges();

        // 6. Track indexed files
        trackFiles(projectPath, javaFiles, tsFiles);

        // 7. Mine git co-change coupling (silently skipped if not a git repo)
        new GitHistoryAnalyzer(store).analyze(projectPath, 500);
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

        // 3. Re-index changed files
        boolean anyChanged = reindexChangedJavaFiles(javaFiles, projectPath, nodeExtractor);
        anyChanged |= reindexChangedTsFiles(tsFiles);

        // 4. Full edge re-extraction if anything changed
        if (anyChanged) {
            store.deleteAllEdges();
            extractAndSaveEdges(javaFiles, projectPath, edgeExtractor);
            extractFeEdges();
        }

        // 5. Update tracked files
        trackFiles(projectPath, javaFiles, tsFiles);
    }

    // ---- Shared extraction helpers ----

    private void extractAndSaveEdges(List<Path> javaFiles, Path projectPath, EdgeExtractor edgeExtractor) {
        for (Path file : javaFiles) {
            try {
                var cu = StaticJavaParser.parse(file);
                var filePath = projectPath.relativize(file).toString();
                var fileNodes = store.findNodesByFilePath(filePath);
                for (Node sourceNode : fileNodes) {
                    var edges = edgeExtractor.extract(cu, sourceNode);
                    for (Edge edge : edges) {
                        store.saveEdge(edge);
                    }
                }
            } catch (Exception e) {
                // Skip unparseable files
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
                }

                // Re-extract methods for this file's nodes
                var updatedNodes = store.findNodesByFilePath(filePath);
                var methods = methodExtractor.extract(cu, updatedNodes);
                for (var method : methods) {
                    store.saveMethod(method);
                }
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
                    && !name.contains("node_modules");
        });
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

    private void trackFiles(Path projectPath, List<Path> javaFiles, List<Path> tsFiles) {
        for (Path file : javaFiles) {
            trackFile(projectPath, file);
        }
        for (Path file : tsFiles) {
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
