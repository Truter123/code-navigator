package com.codenavigator.indexer;

import com.codenavigator.graph.*;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EdgeExtractor {

    private static final Pattern TABLE_REF = Pattern.compile(
        "\\b(?:FROM|JOIN|INTO|UPDATE)\\s+\"?([\\w.]+)\"?", Pattern.CASE_INSENSITIVE);

    private final Project tier;
    private final GraphStore store;
    private final List<Dependency> dependencies;
    private final Set<String> sqlTables;

    /** Backward-compatible constructor: no library dependency awareness. */
    public EdgeExtractor(Project tier, GraphStore store) {
        this(tier, store, List.of());
    }

    /** Full constructor with library dependency list for boundary detection. */
    public EdgeExtractor(Project tier, GraphStore store, List<Dependency> dependencies) {
        this(tier, store, dependencies, Set.of());
    }

    /**
     * Full constructor, additionally aware of the lowercase SQL table names the run has already
     * found, so {@code @Table}/{@code @Entity} annotations and FROM/JOIN/INTO/UPDATE literals can
     * be matched into {@link EdgeType#REFERENCES_TABLE} edges. Built once per run by
     * {@link ProjectIndexer} from every stored {@code SQL_TABLE} node, before the Java edge pass.
     */
    public EdgeExtractor(Project tier, GraphStore store, List<Dependency> dependencies, Set<String> sqlTables) {
        this.tier = tier;
        this.store = store;
        this.dependencies = dependencies;
        this.sqlTables = sqlTables;
    }

    public List<Edge> extract(CompilationUnit cu, Node sourceNode) {
        var edges = new ArrayList<Edge>();
        var packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(decl -> {
            var declName = decl.getNameAsString();
            var qualifiedName = packageName.isEmpty() ? declName : packageName + "." + declName;
            if (!qualifiedName.equals(sourceNode.qualifiedName())) return;

            // Project 1: always active
            extractInjects(decl, sourceNode, packageName, edges);
            extractCallsMethod(decl, sourceNode, packageName, edges);
            extractImplements(decl, sourceNode, packageName, edges);
            extractExtends(decl, sourceNode, packageName, edges);
            extractReturnsType(decl, sourceNode, packageName, edges);
            extractReferencesTable(decl, sourceNode, edges);

            // Project 2: Spring or DDD
            if (tier == Project.CRUD || tier == Project.DDD) {
                extractUpdatesView(decl, sourceNode, packageName, edges);
                extractReadsView(decl, sourceNode, packageName, edges);
            }

            // Project 3: DDD only
            if (tier == Project.DDD) {
                extractDispatchesCommand(decl, sourceNode, packageName, edges);
                extractDispatchesQuery(decl, sourceNode, packageName, edges);
                extractHandles(decl, sourceNode, packageName, edges);
                extractLoadsAggregate(decl, sourceNode, packageName, edges);
                extractEmitsEvent(decl, sourceNode, packageName, edges);
                extractAppliesEvent(decl, sourceNode, packageName, edges);
                extractProjectsEvent(decl, sourceNode, packageName, edges);
                extractListensTo(decl, sourceNode, packageName, edges);
            }
        });

        cu.findAll(RecordDeclaration.class).forEach(decl -> {
            var declName = decl.getNameAsString();
            var qualifiedName = packageName.isEmpty() ? declName : packageName + "." + declName;
            if (!qualifiedName.equals(sourceNode.qualifiedName())) return;
            extractReferencesTable(decl, sourceNode, edges);
        });

        cu.findAll(EnumDeclaration.class).forEach(decl -> {
            var declName = decl.getNameAsString();
            var qualifiedName = packageName.isEmpty() ? declName : packageName + "." + declName;
            if (!qualifiedName.equals(sourceNode.qualifiedName())) return;
            extractReferencesTable(decl, sourceNode, edges);
        });

        // Library boundary detection (all tiers, only when dependencies are supplied)
        if (!dependencies.isEmpty()) {
            extractUsesLibrary(cu, sourceNode, edges);
        }

        return edges;
    }

    // ── Table references (Java entities/queries -> SQL tables) ──

    /**
     * {@code @Table(name=...)}, {@code @Entity} (class name snake_cased), and every FROM/JOIN/
     * INTO/UPDATE literal in the type (fields, methods, annotation values, text blocks) become
     * candidate table names; only candidates present in the run's known SQL table set become
     * edges. Self-persisted like {@link #extractUsesLibrary}, so a caller that only reads the
     * returned list still sees the edge once (same id, so a second save by the pipeline is a
     * no-op replace).
     */
    private void extractReferencesTable(TypeDeclaration<?> decl, Node source, List<Edge> edges) {
        if (sqlTables.isEmpty()) return;

        var candidates = new LinkedHashSet<String>();
        tableCandidateFromAnnotations(decl).ifPresent(candidates::add);
        decl.findAll(StringLiteralExpr.class).forEach(sl -> collectTableRefs(sl.getValue(), candidates));
        decl.findAll(TextBlockLiteralExpr.class).forEach(tb -> collectTableRefs(tb.getValue(), candidates));

        var emitted = new HashSet<String>();
        for (String candidate : candidates) {
            var lower = candidate.toLowerCase();
            if (!sqlTables.contains(lower)) continue;
            if (!emitted.add(lower)) continue;
            var edge = new Edge(UUID.randomUUID().toString(),
                EdgeType.REFERENCES_TABLE, source.id(), "sql:" + lower);
            store.saveEdge(edge);
            edges.add(edge);
        }
    }

    private void collectTableRefs(String text, Set<String> candidates) {
        if (text == null) return;
        Matcher m = TABLE_REF.matcher(text);
        while (m.find()) {
            var raw = m.group(1).replace("\"", "");
            var dot = raw.lastIndexOf('.');
            candidates.add(dot >= 0 ? raw.substring(dot + 1) : raw);
        }
    }

    /**
     * {@code @Table(name = "x")} (with or without {@code schema}) names the table directly;
     * {@code @Entity} with no usable {@code @Table} name falls back to the snake_case of the
     * simple class name, stripping a trailing "Entity".
     */
    private Optional<String> tableCandidateFromAnnotations(TypeDeclaration<?> decl) {
        Optional<String> fromTable = decl.getAnnotations().stream()
            .filter(a -> a.getNameAsString().equals("Table"))
            .findFirst()
            .flatMap(this::tableNameMember);
        if (fromTable.isPresent()) return fromTable;

        boolean isEntity = decl.getAnnotations().stream()
            .anyMatch(a -> a.getNameAsString().equals("Entity"));
        if (!isEntity) return Optional.empty();

        var simpleName = decl.getNameAsString();
        if (simpleName.endsWith("Entity")) {
            simpleName = simpleName.substring(0, simpleName.length() - "Entity".length());
        }
        return Optional.of(snakeCase(simpleName));
    }

    private Optional<String> tableNameMember(AnnotationExpr annotation) {
        if (annotation instanceof NormalAnnotationExpr normal) {
            return normal.getPairs().stream()
                .filter(p -> p.getNameAsString().equals("name"))
                .findFirst()
                .filter(p -> p.getValue() instanceof StringLiteralExpr)
                .map(p -> ((StringLiteralExpr) p.getValue()).getValue());
        }
        if (annotation instanceof SingleMemberAnnotationExpr single
                && single.getMemberValue() instanceof StringLiteralExpr literal) {
            return Optional.of(literal.getValue());
        }
        return Optional.empty();
    }

    private String snakeCase(String name) {
        var s = name.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        s = s.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2");
        return s.toLowerCase();
    }

    // ── Library boundary ──

    private void extractUsesLibrary(CompilationUnit cu, Node source, List<Edge> edges) {
        cu.getImports().forEach(importDecl -> {
            if (importDecl.isAsterisk()) return;
            String importFqn = importDecl.getNameAsString();

            // Skip if this type is already an internal node
            if (store.findNodeById(importFqn).isPresent()) return;
            var simpleParts = importFqn.split("\\.");
            var simpleName = simpleParts[simpleParts.length - 1];
            if (!store.findNodesByName(simpleName).isEmpty()) return;

            // Find matching declared dependency
            dependencies.stream()
                .filter(dep -> importFqn.startsWith(dep.packagePrefix()))
                .findFirst()
                .ifPresent(dep -> mintLibraryNode(importFqn, dep, source, edges));
        });
    }

    private void mintLibraryNode(String importFqn, Dependency dep, Node source, List<Edge> edges) {
        // Idempotent: reuse existing LIBRARY node if already minted by another source
        if (store.findNodeById(importFqn).isEmpty()) {
            var simpleParts = importFqn.split("\\.");
            var simpleName = simpleParts[simpleParts.length - 1];
            var libraryNode = new Node(
                importFqn,
                NodeType.LIBRARY,
                simpleName,
                importFqn,
                "",
                0,
                dep.coordinate(),   // codeSnippet stores the artifact coordinate tag
                0L
            );
            store.saveNode(libraryNode);
        }
        // Persist the edge directly (extract() is also called standalone, e.g. in unit
        // tests, where the caller does not save the returned list) and add it to the
        // returned list for the indexing pipeline.
        var edge = new Edge(java.util.UUID.randomUUID().toString(),
            EdgeType.USES_LIBRARY, source.id(), importFqn);
        store.saveEdge(edge);
        edges.add(edge);
    }

    // ── Project 1 ──

    private void extractInjects(ClassOrInterfaceDeclaration decl, Node source,
                                String packageName, List<Edge> edges) {
        decl.findAll(ConstructorDeclaration.class).forEach(ctor ->
            ctor.getParameters().forEach(param -> {
                var typeName = param.getType().asString();
                resolveNode(typeName, packageName).ifPresent(target ->
                    edges.add(edge(EdgeType.INJECTS, source, target)));
            })
        );
    }

    private void extractImplements(ClassOrInterfaceDeclaration decl, Node source,
                                   String packageName, List<Edge> edges) {
        decl.getImplementedTypes().forEach(impl -> {
            var name = impl.getNameAsString();
            resolveNode(name, packageName).ifPresent(target ->
                edges.add(edge(EdgeType.IMPLEMENTS, source, target)));
        });
    }

    private void extractExtends(ClassOrInterfaceDeclaration decl, Node source,
                                String packageName, List<Edge> edges) {
        decl.getExtendedTypes().forEach(ext -> {
            var name = ext.getNameAsString();
            // Skip framework base classes
            if (isFrameworkClass(name)) return;
            resolveNode(name, packageName).ifPresent(target ->
                edges.add(edge(EdgeType.EXTENDS, source, target)));
        });
    }

    private void extractCallsMethod(ClassOrInterfaceDeclaration decl, Node source,
                                    String packageName, List<Edge> edges) {
        // Build field-type map: fieldName -> typeName
        var fieldTypes = new HashMap<String, String>();
        decl.findAll(FieldDeclaration.class).forEach(field -> {
            var typeName = field.getElementType().asString();
            field.getVariables().forEach(v ->
                fieldTypes.put(v.getNameAsString(), typeName));
        });

        // Walk method calls and resolve to graph nodes
        var seen = new HashSet<String>();
        decl.findAll(MethodCallExpr.class).forEach(mc -> {
            var scope = mc.getScope().orElse(null);
            if (scope == null) return;

            String fieldName = null;
            if (scope instanceof NameExpr ne) {
                fieldName = ne.getNameAsString();
            } else if (scope instanceof FieldAccessExpr fae) {
                fieldName = fae.getNameAsString();
            }
            if (fieldName == null) return;

            var typeName = fieldTypes.get(fieldName);
            if (typeName == null) return;

            resolveNode(typeName, packageName).ifPresent(target -> {
                if (!target.id().equals(source.id()) && seen.add(target.id())) {
                    edges.add(edge(EdgeType.CALLS_METHOD, source, target));
                }
            });
        });
    }

    private void extractReturnsType(ClassOrInterfaceDeclaration decl, Node source,
                                    String packageName, List<Edge> edges) {
        var seen = new HashSet<String>();
        decl.findAll(MethodDeclaration.class).forEach(method -> {
            var returnType = method.getType();
            if (returnType.isVoidType() || returnType.isPrimitiveType()) return;
            var typeName = returnType.asString();
            if (returnType.isClassOrInterfaceType()) {
                typeName = returnType.asClassOrInterfaceType().getNameAsString();
            }
            resolveNode(typeName, packageName).ifPresent(target -> {
                if (!target.id().equals(source.id()) && seen.add(target.id())) {
                    edges.add(edge(EdgeType.RETURNS_TYPE, source, target));
                }
            });
        });
    }

    private boolean isFrameworkClass(String name) {
        return name.equals("AggregateRoot") || name.equals("EventApplier")
                || name.equals("AbstractEntity") || name.equals("BaseEntity");
    }

    // ── Project 2: Spring ──

    private void extractUpdatesView(ClassOrInterfaceDeclaration decl, Node source,
                                    String packageName, List<Edge> edges) {
        if (source.type() != NodeType.PROJECTION_HANDLER) return;
        decl.findAll(MethodCallExpr.class).stream()
                .filter(mc -> mc.getNameAsString().equals("save"))
                .findFirst()
                .ifPresent(mc -> {
                    // Derive view name from handler name: OrderViewProjectionHandler -> OrderView
                    var handlerName = source.name();
                    var viewName = deriveViewName(handlerName);
                    if (viewName != null) {
                        resolveNode(viewName, packageName).ifPresent(target ->
                            edges.add(edge(EdgeType.UPDATES_VIEW, source, target)));
                    }
                });
    }

    private void extractReadsView(ClassOrInterfaceDeclaration decl, Node source,
                                  String packageName, List<Edge> edges) {
        if (source.type() != NodeType.QUERY_HANDLER) return;
        boolean hasFind = decl.findAll(MethodCallExpr.class).stream()
                .anyMatch(mc -> mc.getNameAsString().startsWith("find"));
        if (hasFind) {
            // Derive view name from handler name: GetOrderQueryHandler -> OrderView
            var handlerName = source.name();
            var viewName = deriveViewNameFromQueryHandler(handlerName);
            if (viewName != null) {
                resolveNode(viewName, packageName).ifPresent(target ->
                    edges.add(edge(EdgeType.READS_VIEW, source, target)));
            }
        }
    }

    private String deriveViewName(String handlerName) {
        // OrderViewProjectionHandler -> OrderView
        if (handlerName.endsWith("ProjectionHandler")) {
            var base = handlerName.substring(0, handlerName.length() - "ProjectionHandler".length());
            if (!base.endsWith("View")) {
                base = base + "View";
            }
            return base;
        }
        return null;
    }

    private String deriveViewNameFromQueryHandler(String handlerName) {
        // GetOrderQueryHandler -> OrderView
        if (handlerName.endsWith("QueryHandler")) {
            var base = handlerName.substring(0, handlerName.length() - "QueryHandler".length());
            if (base.startsWith("Get")) {
                base = base.substring(3);
            }
            return base + "View";
        }
        return null;
    }

    // ── Project 3: DDD ──

    private void extractDispatchesCommand(ClassOrInterfaceDeclaration decl, Node source,
                                          String packageName, List<Edge> edges) {
        decl.findAll(MethodCallExpr.class).stream()
                .filter(mc -> mc.getNameAsString().equals("dispatch"))
                .filter(mc -> mc.getScope().map(s -> s.toString().contains("commandBus")).orElse(false))
                .forEach(mc -> mc.getArguments().forEach(arg ->
                    arg.findFirst(ObjectCreationExpr.class).ifPresent(oce -> {
                        var typeName = oce.getType().getNameAsString();
                        resolveNode(typeName, packageName).ifPresent(target ->
                            edges.add(edge(EdgeType.DISPATCHES_COMMAND, source, target)));
                    })
                ));
    }

    private void extractDispatchesQuery(ClassOrInterfaceDeclaration decl, Node source,
                                        String packageName, List<Edge> edges) {
        decl.findAll(MethodCallExpr.class).stream()
                .filter(mc -> mc.getNameAsString().equals("dispatch"))
                .filter(mc -> mc.getScope().map(s -> s.toString().contains("queryBus")).orElse(false))
                .forEach(mc -> mc.getArguments().forEach(arg ->
                    arg.findFirst(ObjectCreationExpr.class).ifPresent(oce -> {
                        var typeName = oce.getType().getNameAsString();
                        resolveNode(typeName, packageName).ifPresent(target ->
                            edges.add(edge(EdgeType.DISPATCHES_QUERY, source, target)));
                    })
                ));
    }

    private void extractHandles(ClassOrInterfaceDeclaration decl, Node source,
                                String packageName, List<Edge> edges) {
        decl.getImplementedTypes().forEach(impl -> {
            var name = impl.getNameAsString();
            if (name.equals("CommandHandler") || name.equals("QueryHandler")) {
                var typeArgs = impl.getTypeArguments();
                if (typeArgs.isPresent() && !typeArgs.get().isEmpty()) {
                    var firstArg = typeArgs.get().get(0);
                    if (firstArg instanceof ClassOrInterfaceType cit) {
                        resolveNode(cit.getNameAsString(), packageName).ifPresent(target ->
                            edges.add(edge(EdgeType.HANDLES, source, target)));
                    }
                }
            }
        });
    }

    private void extractLoadsAggregate(ClassOrInterfaceDeclaration decl, Node source,
                                       String packageName, List<Edge> edges) {
        if (source.type() != NodeType.COMMAND_HANDLER) return;
        decl.findAll(MethodCallExpr.class).stream()
                .filter(mc -> mc.getNameAsString().equals("create")
                        || mc.getNameAsString().equals("findById"))
                .forEach(mc -> mc.getScope().ifPresent(scope -> {
                    // Try to resolve the scope to an aggregate
                    var scopeName = scope.toString();
                    // repository.findById -> try to find aggregate from repository name
                    store.getAllNodes().stream()
                            .filter(n -> n.type() == NodeType.AGGREGATE)
                            .filter(n -> scopeName.toLowerCase().contains(n.name().toLowerCase()))
                            .findFirst()
                            .ifPresent(target ->
                                edges.add(edge(EdgeType.LOADS_AGGREGATE, source, target)));
                }));
    }

    private void extractEmitsEvent(ClassOrInterfaceDeclaration decl, Node source,
                                   String packageName, List<Edge> edges) {
        decl.findAll(MethodCallExpr.class).stream()
                .filter(mc -> mc.getNameAsString().equals("applyEvent"))
                .forEach(mc -> mc.getArguments().forEach(arg ->
                    arg.findFirst(ObjectCreationExpr.class).ifPresent(oce -> {
                        var typeName = oce.getType().getNameAsString();
                        resolveNode(typeName, packageName).ifPresent(target ->
                            edges.add(edge(EdgeType.EMITS_EVENT, source, target)));
                    })
                ));
    }

    private void extractAppliesEvent(ClassOrInterfaceDeclaration decl, Node source,
                                     String packageName, List<Edge> edges) {
        decl.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("ApplyEvent")))
                .forEach(m -> {
                    if (!m.getParameters().isEmpty()) {
                        var paramType = m.getParameter(0).getType().asString();
                        resolveNode(paramType, packageName).ifPresent(target ->
                            edges.add(edge(EdgeType.APPLIES_EVENT, source, target)));
                    }
                });
    }

    private void extractProjectsEvent(ClassOrInterfaceDeclaration decl, Node source,
                                      String packageName, List<Edge> edges) {
        if (source.type() != NodeType.PROJECTION_HANDLER) return;
        extractEventHandlerEdges(decl, source, packageName, edges, EdgeType.PROJECTS_EVENT);
    }

    private void extractListensTo(ClassOrInterfaceDeclaration decl, Node source,
                                  String packageName, List<Edge> edges) {
        if (source.type() != NodeType.EVENT_LISTENER) return;
        extractEventHandlerEdges(decl, source, packageName, edges, EdgeType.LISTENS_TO);
    }

    private void extractEventHandlerEdges(ClassOrInterfaceDeclaration decl, Node source,
                                          String packageName, List<Edge> edges, EdgeType edgeType) {
        decl.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("EventHandler")))
                .forEach(m -> {
                    if (!m.getParameters().isEmpty()) {
                        var paramType = m.getParameter(0).getType().asString();
                        resolveNode(paramType, packageName).ifPresent(target ->
                            edges.add(edge(edgeType, source, target)));
                    }
                });
    }

    // ── Node resolution ──

    private Optional<Node> resolveNode(String simpleName, String packageName) {
        // 1. Try same package
        if (!packageName.isEmpty()) {
            var fqn = packageName + "." + simpleName;
            var found = store.findNodeById(fqn);
            if (found.isPresent()) return found;
        }

        // 2. Search all nodes by simple name
        var matches = store.findNodesByName(simpleName);
        if (matches.size() == 1) {
            return Optional.of(matches.get(0));
        }

        // 3. If 0 or 2+ matches, skip
        return Optional.empty();
    }

    private Edge edge(EdgeType type, Node source, Node target) {
        return new Edge(UUID.randomUUID().toString(), type, source.id(), target.id());
    }
}
