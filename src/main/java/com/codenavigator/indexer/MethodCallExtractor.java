package com.codenavigator.indexer;

import com.codenavigator.graph.*;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves {@code method -> method} CALLS edges.
 *
 * <p>Only calls whose receiver type is known from the source text are emitted: an unqualified call
 * (implicit {@code this}), an explicit {@code this.x()}, or a call through a field, constructor
 * parameter or local variable whose declared type resolves to an indexed node. Anything whose
 * receiver comes from an expression — a chained call, a stream lambda, a collection element — is
 * counted as unresolved and dropped.
 *
 * <p>Dropping is deliberate. A guessed edge produces a confidently wrong answer, which for an agent
 * consuming this graph is worse than a missing one; the resolution rate is reported at index time
 * so the gap stays visible instead of being papered over.
 */
public class MethodCallExtractor {

    private final GraphStore store;

    /**
     * Resolution indexes, built once from the store.
     *
     * <p>Resolving a receiver used to mean a {@code findNodesByName} query per call site — tens of
     * thousands of round trips for a mid-size repo, which dominated indexing time. Every lookup
     * below is now an in-memory map hit.
     */
    private final Map<String, List<String>> methodIdsByClass = new LinkedHashMap<>();
    private final Map<String, List<String>> typeIdsBySimpleName = new HashMap<>();
    private final Set<String> knownTypeIds = new HashSet<>();

    /**
     * Call-site counters for the indexer's summary line.
     *
     * <p>Split deliberately. Most call sites in any codebase target types this graph does not
     * contain — {@code list.add()}, {@code sb.append()}, {@code Optional.map()} — and counting
     * those as failures buries the number that matters: once the receiver is known to be an
     * indexed type, how often can a specific method be named.
     */
    private int callSitesSeen;
    private int receiverExternal;
    private int receiverDataType;
    private int receiverBehavioural;
    private int callSitesResolved;
    private int accessorMiss;

    /**
     * Unresolved (receiver, method, arity) tallies, for diagnosing call-graph gaps. Printed only
     * when CODE_NAVIGATOR_DEBUG_CALLS is set — guessing at what fails to bind wastes far more time
     * than reading the top offenders.
     */
    private final Map<String, Integer> unresolvedByReceiver = new HashMap<>();

    /** The most frequent unresolved call shapes, highest count first. */
    public List<Map.Entry<String, Integer>> topUnresolved(int limit) {
        return unresolvedByReceiver.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(limit)
            .toList();
    }

    public MethodCallExtractor(GraphStore store) {
        this.store = store;
        for (Node n : store.getAllNodes()) {
            if (n.type() == NodeType.METHOD) {
                methodIdsByClass.computeIfAbsent(MethodIds.declaringClass(n.id()), k -> new ArrayList<>())
                    .add(n.id());
            } else {
                knownTypeIds.add(n.id());
                typeIdsBySimpleName.computeIfAbsent(n.name(), k -> new ArrayList<>()).add(n.id());
            }
        }
    }

    public int callSitesSeen() { return callSitesSeen; }
    public int callSitesResolved() { return callSitesResolved; }

    /** A call whose receiver is known but whose method was not found on the receiver itself. */
    private record PendingCall(String callerId, String receiverClass, String name, int argCount) {}

    private final List<PendingCall> pending = new ArrayList<>();
    private String currentCallerId;
    private Map<String, String> currentStaticImports = Map.of();
    private List<String> currentStaticWildcards = List.of();

    /** Receiver type not knowable from source, or known but not indexed (JDK, framework). */
    public int receiverExternal() { return receiverExternal; }

    /**
     * Receiver is an indexed record, command, query, event or enum. These carry no METHOD nodes by
     * design — {@code command.orderId()} is a field read, not behaviour — so a call into one is out
     * of scope rather than a miss.
     */
    public int receiverDataType() { return receiverDataType; }

    /** Receiver is an indexed type that declares methods: the calls this resolver is judged on. */
    public int receiverBehavioural() { return receiverBehavioural; }

    /** Calls to accessors generated by an annotation processor (Lombok): field access, not behaviour. */
    public int accessorMiss() { return accessorMiss; }

    /** True when any method node was indexed; nothing to do for a project without one. */
    public boolean hasMethods() { return !methodIdsByClass.isEmpty(); }

    /** Extract CALLS edges for every method declared in {@code cu}. */
    public List<Edge> extract(CompilationUnit cu) {
        var edges = new ArrayList<Edge>();
        var packageName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");

        // Static imports: an unqualified assertThat(...) belongs to AssertJ, not to the enclosing
        // test class. Without this every statically imported call is charged against the caller's
        // own type and shows up as an unresolved internal call.
        var staticImportByName = new HashMap<String, String>();
        var staticWildcardTypes = new ArrayList<String>();
        cu.getImports().forEach(imp -> {
            if (!imp.isStatic()) return;
            String name = imp.getNameAsString();
            if (imp.isAsterisk()) {
                staticWildcardTypes.add(name);
                return;
            }
            int lastDot = name.lastIndexOf('.');
            if (lastDot > 0) {
                staticImportByName.put(name.substring(lastDot + 1), name.substring(0, lastDot));
            }
        });
        this.currentStaticImports = staticImportByName;
        this.currentStaticWildcards = staticWildcardTypes;

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(decl -> {
            String ownerFqn = packageName.isEmpty() ? decl.getNameAsString()
                : packageName + "." + decl.getNameAsString();
            if (!methodIdsByClass.containsKey(ownerFqn)) return;

            // Receiver name -> declared type, for fields (visible to every method in the class).
            var fieldTypes = new HashMap<String, String>();
            decl.findAll(FieldDeclaration.class).forEach(field -> {
                String typeName = field.getElementType().asString();
                field.getVariables().forEach(v -> fieldTypes.put(v.getNameAsString(), typeName));
            });

            decl.getMethods().forEach(caller -> {
                String callerId = MethodIds.of(ownerFqn, caller.getNameAsString(),
                    caller.getParameters().stream()
                        .map(p -> MethodIds.simplifyType(p.getTypeAsString()))
                        .toList());
                if (!methodIdsByClass.getOrDefault(ownerFqn, List.of()).contains(callerId)) return;
                currentCallerId = callerId;

                // Parameters and locals shadow fields within this method body.
                var scopeTypes = new HashMap<>(fieldTypes);
                caller.getParameters().forEach((Parameter p) ->
                    scopeTypes.put(p.getNameAsString(), p.getTypeAsString()));
                caller.findAll(VariableDeclarator.class).forEach(v ->
                    scopeTypes.put(v.getNameAsString(), v.getType().asString()));

                var seen = new HashSet<String>();
                caller.findAll(MethodCallExpr.class).forEach(call -> {
                    callSitesSeen++;
                    var targets = resolveTarget(call, ownerFqn, packageName, scopeTypes);
                    if (!targets.isEmpty()) callSitesResolved++;
                    for (String targetId : targets) {
                        if (targetId.equals(callerId)) continue;        // direct recursion
                        if (!seen.add(targetId)) continue;
                        edges.add(new Edge(UUID.randomUUID().toString(),
                            EdgeType.CALLS, callerId, targetId));
                    }
                });
            });
        });

        return edges;
    }

    /** Resolve one call site to method node ids, or empty when the receiver type is unknown. */
    private List<String> resolveTarget(MethodCallExpr call, String ownerFqn, String packageName,
                                       Map<String, String> scopeTypes) {
        String receiverClass = receiverClassOf(call, ownerFqn, packageName, scopeTypes).orElse(null);
        if (receiverClass == null) {
            receiverExternal++;
            return List.of();
        }

        var candidates = methodIdsByClass.get(receiverClass);
        if (candidates == null || candidates.isEmpty()) {
            receiverDataType++;
            return List.of();
        }

        receiverBehavioural++;
        var direct = pickOverloads(candidates, call.getNameAsString(), call.getArguments().size());
        if (!direct.isEmpty()) return direct;

        // The name may be declared on a supertype (repo.save() where save() lives on the
        // interface). Supertype edges are written by the class-level pass running over the same
        // files, so they are not all present yet — park the call and retry in resolvePending().
        pending.add(new PendingCall(currentCallerId, receiverClass,
            call.getNameAsString(), call.getArguments().size()));
        return List.of();
    }

    /** Work out which indexed class the call's receiver refers to. */
    private Optional<String> receiverClassOf(MethodCallExpr call, String ownerFqn, String packageName,
                                             Map<String, String> scopeTypes) {
        var scope = call.getScope().orElse(null);

        if (scope instanceof ThisExpr) return Optional.of(ownerFqn);

        if (scope == null) {
            // Unqualified. The enclosing class wins when it declares the name; otherwise a static
            // import is the likelier owner (assertThat, mock, when), and only then fall back to
            // the enclosing type so inherited methods still reach the hierarchy walk.
            var own = methodIdsByClass.get(ownerFqn);
            if (own != null && own.stream().anyMatch(id -> call.getNameAsString().equals(MethodIds.methodName(id)))) {
                return Optional.of(ownerFqn);
            }
            String importedFrom = currentStaticImports.get(call.getNameAsString());
            if (importedFrom != null) {
                return knownTypeIds.contains(importedFrom) ? Optional.of(importedFrom) : Optional.empty();
            }
            for (String wildcard : currentStaticWildcards) {
                if (methodIdsByClass.containsKey(wildcard)) return Optional.of(wildcard);
            }
            // A wildcard static import from a type this graph does not hold (assertj, mockito)
            // means the call leaves the project rather than staying inside the caller.
            if (!currentStaticWildcards.isEmpty()) return Optional.empty();
            return Optional.of(ownerFqn);
        }

        String receiverName = null;
        if (scope instanceof NameExpr ne) {
            receiverName = ne.getNameAsString();
        } else if (scope instanceof FieldAccessExpr fae && fae.getScope() instanceof ThisExpr) {
            receiverName = fae.getNameAsString();
        }
        if (receiverName == null) return Optional.empty();

        String declaredType = scopeTypes.get(receiverName);
        if (declaredType != null) {
            return resolveTypeToNodeId(MethodIds.simplifyType(declaredType), packageName);
        }

        // Not a field, parameter or local, so the receiver is a type name: a static call such as
        // SalesOrderReads.of(...). Resolves only when that type is itself indexed.
        return resolveTypeToNodeId(receiverName, packageName);
    }

    /**
     * Pick the overloads matching {@code name} and {@code argCount}.
     *
     * <p>Returns every same-arity candidate rather than one. {@code ProductionOrderId.of(String)}
     * and {@code of(UUID)} cannot be told apart without evaluating the argument's type, but the
     * receiver and the method name are both certain — so linking to each is an over-approximation
     * of which signature runs, not a guess about what is being called. Dropping the call instead
     * loses the caller entirely, which is the worse error: "who calls this?" would answer nobody.
     */
    private List<String> pickOverloads(List<String> candidates, String name, int argCount) {
        var byName = candidates.stream()
            .filter(id -> name.equals(MethodIds.methodName(id)))
            .toList();
        if (byName.size() <= 1) return byName;

        var byArity = byName.stream().filter(id -> arityOf(id) == argCount).toList();
        return byArity.isEmpty() ? List.of() : byArity;
    }

    private static int arityOf(String methodId) {
        int open = methodId.indexOf('(');
        int close = methodId.lastIndexOf(')');
        if (open < 0 || close <= open) return -1;
        String params = methodId.substring(open + 1, close);
        return params.isBlank() ? 0 : params.split(",").length;
    }

    /** Same-package first, then a unique simple-name match; ambiguity resolves to none. */
    private Optional<String> resolveTypeToNodeId(String simpleName, String packageName) {
        if (!packageName.isEmpty()) {
            String fqn = packageName + "." + simpleName;
            if (knownTypeIds.contains(fqn)) return Optional.of(fqn);
        }
        var matches = typeIdsBySimpleName.getOrDefault(simpleName, List.of());
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    /**
     * Retry the calls parked during {@link #extract}, now that every IMPLEMENTS/EXTENDS edge has
     * been written, by walking the receiver's supertypes.
     *
     * <p>{@code repo.save(order)} where {@code save} is declared on the repository interface rather
     * than on the implementation is the common shape; without this the call graph loses most of its
     * edges through interfaces.
     */
    public List<Edge> resolvePending() {
        if (pending.isEmpty()) return List.of();

        var superTypes = new HashMap<String, List<String>>();
        for (Edge e : store.getAllEdges()) {
            if (e.type() == EdgeType.IMPLEMENTS || e.type() == EdgeType.EXTENDS) {
                superTypes.computeIfAbsent(e.sourceId(), k -> new ArrayList<>()).add(e.targetId());
            }
        }

        var edges = new ArrayList<Edge>();
        var seen = new HashSet<String>();
        for (PendingCall call : pending) {
            var targets = findInHierarchy(call, superTypes, new HashSet<>());
            if (targets.isEmpty()) {
                if (isGeneratedAccessor(call.name())) accessorMiss++;
                else unresolvedByReceiver.merge(
                    call.receiverClass() + "#" + call.name() + "/" + call.argCount(), 1, Integer::sum);
                continue;
            }
            callSitesResolved++;
            for (String targetId : targets) {
                if (targetId.equals(call.callerId())) continue;
                if (!seen.add(call.callerId() + ">" + targetId)) continue;
                edges.add(new Edge(UUID.randomUUID().toString(),
                    EdgeType.CALLS, call.callerId(), targetId));
            }
        }
        pending.clear();
        return edges;
    }

    /**
     * True for a call the source never declares because an annotation processor generates it.
     *
     * <p>Lombok's {@code @Getter}/{@code @Data}/{@code @Builder} produce accessors that JavaParser
     * cannot see, so {@code view.getStatus()} finds a receiver but no method. That is field access,
     * not a resolution failure — synthesising a node per generated accessor would add thousands of
     * data-shaped nodes for the same reason record components are excluded.
     */
    private static boolean isGeneratedAccessor(String name) {
        return name.startsWith("get") || name.startsWith("set") || name.startsWith("is")
            || name.equals("builder") || name.equals("toBuilder") || name.equals("build");
    }

    /** Breadth-first walk up the supertype chain looking for matching methods. */
    private List<String> findInHierarchy(PendingCall call, Map<String, List<String>> superTypes,
                                         Set<String> visited) {
        var queue = new ArrayDeque<>(superTypes.getOrDefault(call.receiverClass(), List.of()));
        visited.add(call.receiverClass());

        while (!queue.isEmpty()) {
            String type = queue.poll();
            if (!visited.add(type)) continue;

            var candidates = methodIdsByClass.get(type);
            if (candidates != null && !candidates.isEmpty()) {
                var hits = pickOverloads(candidates, call.name(), call.argCount());
                if (!hits.isEmpty()) return hits;
            }
            queue.addAll(superTypes.getOrDefault(type, List.of()));
        }
        return List.of();
    }

    /**
     * Derive OVERRIDES edges from the supertypes already in the graph: for each method on a class
     * with an IMPLEMENTS/EXTENDS edge, link it to the same-name, same-arity method on the
     * supertype. Runs after every class has been indexed and its supertype edges saved.
     */
    public List<Edge> deriveOverrides() {
        var edges = new ArrayList<Edge>();
        var methodsByClass = methodIdsByClass;

        Set<EdgeType> superTypeEdges = Set.of(EdgeType.IMPLEMENTS, EdgeType.EXTENDS);
        for (var entry : methodsByClass.entrySet()) {
            String classFqn = entry.getKey();
            for (Edge superEdge : store.findEdgesFrom(classFqn)) {
                if (!superTypeEdges.contains(superEdge.type())) continue;
                var superMethods = methodsByClass.get(superEdge.targetId());
                if (superMethods == null) continue;

                for (String methodId : entry.getValue()) {
                    String name = MethodIds.methodName(methodId);
                    int arity = arityOf(methodId);
                    superMethods.stream()
                        .filter(sid -> name.equals(MethodIds.methodName(sid)) && arityOf(sid) == arity)
                        .findFirst()
                        .ifPresent(sid -> edges.add(new Edge(UUID.randomUUID().toString(),
                            EdgeType.OVERRIDES, methodId, sid)));
                }
            }
        }
        return edges;
    }
}
