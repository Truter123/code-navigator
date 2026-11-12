package com.codenavigator.indexer;

import com.codenavigator.graph.*;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.expr.ThisExpr;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
    private final JavaTypeResolver resolver;

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
        var allNodes = store.getAllNodes();
        this.resolver = new JavaTypeResolver(allNodes);
        for (Node n : allNodes) {
            // LIBRARY nodes are minted by the edge pass and SHELL_COMMAND nodes are not Java types:
            // a full run builds this before the first exists and a partial run builds it after, so
            // neither may reach the maps or the two runs would resolve against different graphs.
            if (n.type() == NodeType.LIBRARY || n.type() == NodeType.SHELL_COMMAND) continue;
            if (n.type() == NodeType.METHOD) {
                methodIdsByClass.computeIfAbsent(MethodIds.declaringClass(n.id()), k -> new ArrayList<>())
                    .add(n.id());
            }
        }
    }

    public int callSitesSeen() { return callSitesSeen; }
    public int callSitesResolved() { return callSitesResolved; }

    /** A call whose receiver is known but whose method was not found on the receiver itself. */
    private record PendingCall(String callerId, String receiverClass, String name, int argCount) {}

    private final List<PendingCall> pending = new ArrayList<>();

    /**
     * One call in a chain ({@code a.b().c()}). The first link of a chain carries the class its
     * receiver resolved to; every later link takes its class from the previous link's declared
     * return type, which is only known once every file has been seen, so chains resolve in
     * {@link #resolvePending}. Links are shared between the calls of one chain, so a builder chain
     * of N calls is N links, resolved once each.
     */
    private static final class Link {
        final Link parent;
        final String baseClass;
        final String name;
        final int argCount;
        Link(Link parent, String baseClass, String name, int argCount) {
            this.parent = parent; this.baseClass = baseClass; this.name = name; this.argCount = argCount;
        }
    }

    private record PendingChain(String callerId, Link link, boolean count) {}

    /** Outcome of resolving one link; {@code returnClass} is null unless the link bound to one method. */
    private record Resolved(List<String> targets, String returnClass, boolean dataType, boolean parentBound,
                            String parentLabel) {}

    private final List<PendingChain> pendingChains = new ArrayList<>();
    /** Id of the type being walked: where a written type name is looked up first. */
    private String currentOwner;
    private final Map<String, JavaTypeResolver.FileContext> fileCtxByClass = new HashMap<>();
    /** Declared return type (simple or qualified name) per method id; absent for void, primitives,
     *  arrays, parameterised types and type variables, none of which can name an indexed type. */
    private final Map<String, String> returnTypeByMethodId = new HashMap<>();
    /** Per body: the chain link of each call, or empty when its chain cannot start. */
    private Map<MethodCallExpr, Optional<Link>> currentChainLinks = new IdentityHashMap<>();
    private String currentCallerId;
    private Map<String, String> currentStaticImports = Map.of();
    private List<String> currentStaticWildcards = List.of();
    private JavaTypeResolver.FileContext currentCtx = new JavaTypeResolver.FileContext("", Map.of(), List.of(), java.util.Set.of());

    /** Receiver type not knowable from source, or known but not indexed (JDK, framework). */
    public int receiverExternal() { return receiverExternal; }

    /**
     * Receiver is an indexed record, command, query, event or enum. These carry no METHOD nodes by
     * design — {@code command.orderId()} is a field read, not behaviour — so a call into one is out
     * of scope rather than a miss.
     */
    public int receiverDataType() { return receiverDataType; }

    /** Lets the resolver read the supertypes declared in files other than the one being extracted. */
    public MethodCallExtractor withSourceRoot(java.nio.file.Path root) {
        resolver.withSourceRoot(root);
        return this;
    }

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

        // Type imports and the types this file declares: the receiver-type disambiguators for a
        // simple name that collides across languages of the same indexed project (an `Andon`
        // aggregate and an Angular `Andon` model both indexed) or across Java packages.
        var fileCtx = JavaTypeResolver.FileContext.of(cu);
        this.currentCtx = fileCtx;

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(decl -> {
            String ownerFqn = JavaTypeResolver.typeId(packageName, decl);
            if (!methodIdsByClass.containsKey(ownerFqn)) return;
            this.currentOwner = ownerFqn;

            fileCtxByClass.put(ownerFqn, fileCtx);

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
                String returnType = returnTypeName(caller, decl);
                if (returnType != null) returnTypeByMethodId.put(callerId, returnType);
                walkBody(caller, List.of(callerId), caller.getParameters(), fieldTypes, ownerFqn,
                    packageName, decl, edges);
            });

            // Constructors: the caller is the constructor's own <init> node.
            var known = methodIdsByClass.getOrDefault(ownerFqn, List.of());
            var ctorIds = new ArrayList<String>();
            decl.getConstructors().forEach(ctor -> {
                String ctorId = MethodIds.of(ownerFqn, MethodIds.INIT,
                    ctor.getParameters().stream()
                        .map(p -> MethodIds.simplifyType(p.getTypeAsString()))
                        .toList());
                if (!known.contains(ctorId)) return;
                ctorIds.add(ctorId);
                walkBody(ctor, List.of(ctorId), ctor.getParameters(), fieldTypes, ownerFqn,
                    packageName, decl, edges);
            });

            // Instance initializer blocks run as part of every constructor; with none declared,
            // they belong to the implicit <init>().
            if (ctorIds.isEmpty()) {
                String implicit = MethodIds.of(ownerFqn, MethodIds.INIT, List.of());
                if (known.contains(implicit)) ctorIds.add(implicit);
            }
            if (!ctorIds.isEmpty()) {
                decl.getMembers().forEach(member -> {
                    if (member instanceof InitializerDeclaration init && !init.isStatic()) {
                        walkBody(init, ctorIds, List.<Parameter>of(), fieldTypes, ownerFqn,
                            packageName, decl, edges);
                    }
                });
            }
        });

        return edges;
    }

    private static String returnTypeName(MethodDeclaration m, ClassOrInterfaceDeclaration decl) {
        var type = m.getType();
        if (!type.isClassOrInterfaceType()) return null;           // void, primitive, array
        var ct = type.asClassOrInterfaceType();
        if (ct.getTypeArguments().isPresent()) return null;        // List<Foo>: the callee is List's
        String name = ct.getNameWithScope();
        if (name.equals("var")) return null;
        if (m.getTypeParameters().stream().anyMatch(tp -> tp.getNameAsString().equals(name))
            || decl.getTypeParameters().stream().anyMatch(tp -> tp.getNameAsString().equals(name))) {
            return null;                                           // a type variable
        }
        return name;
    }

    /** Walk one method, constructor or initializer body, emitting CALLS from every caller id. */
    private void walkBody(com.github.javaparser.ast.Node body, List<String> callerIds,
                          List<Parameter> params, Map<String, String> fieldTypes, String ownerFqn,
                          String packageName, ClassOrInterfaceDeclaration decl, List<Edge> edges) {
        // Parameters and locals shadow fields within this body.
        var scopeTypes = new HashMap<>(fieldTypes);
        params.forEach((Parameter p) -> scopeTypes.put(p.getNameAsString(), p.getTypeAsString()));
        body.findAll(VariableDeclarator.class).forEach(v ->
            scopeTypes.put(v.getNameAsString(), v.getType().asString()));

        currentChainLinks = new IdentityHashMap<>();

        // Call sites are counted once, however many constructors an initializer block feeds.
        for (int i = 0; i < callerIds.size(); i++) {
            String callerId = callerIds.get(i);
            boolean count = i == 0;
            currentCallerId = callerId;
            var seen = new HashSet<String>();
            java.util.function.Consumer<List<String>> link = targets -> {
                for (String targetId : targets) {
                    if (targetId.equals(callerId)) continue;        // direct recursion
                    if (!seen.add(targetId)) continue;
                    edges.add(new Edge(UUID.randomUUID().toString(),
                        EdgeType.CALLS, callerId, targetId));
                }
            };
            body.findAll(MethodCallExpr.class).forEach(call -> {
                if (count) callSitesSeen++;
                var targets = resolveTarget(call, ownerFqn, packageName, scopeTypes, count);
                if (count && !targets.isEmpty()) callSitesResolved++;
                link.accept(targets);
            });
            body.findAll(MethodReferenceExpr.class).forEach(ref ->
                link.accept(resolveReference(ref, ownerFqn, packageName, scopeTypes, count)));
            body.findAll(ObjectCreationExpr.class).forEach(oce ->
                link.accept(resolveNew(oce, packageName, count)));
            body.findAll(ExplicitConstructorInvocationStmt.class).forEach(stmt ->
                link.accept(resolveExplicit(stmt, ownerFqn, packageName, decl, count)));
        }
    }

    private List<String> initsOf(String classId) {
        return methodIdsByClass.getOrDefault(classId, List.of()).stream()
            .filter(MethodIds::isConstructor).toList();
    }

    /** {@code new X(a, b)}: every {@code <init>} of X with two parameters. */
    private List<String> resolveNew(ObjectCreationExpr oce, String packageName, boolean count) {
        if (count) callSitesSeen++;
        String typeClass = resolveTypeToNodeId(
            oce.getType().getNameWithScope(), packageName).orElse(null);
        return bindConstructor(typeClass, oce.getArguments().size(), count,
            "new:" + oce.getType().getNameAsString());
    }

    /** {@code this(...)} / {@code super(...)}: the matching-arity constructor of own or super class. */
    private List<String> resolveExplicit(ExplicitConstructorInvocationStmt stmt, String ownerFqn,
                                         String packageName, ClassOrInterfaceDeclaration decl,
                                         boolean count) {
        if (count) callSitesSeen++;
        String target = ownerFqn;
        if (!stmt.isThis()) {
            target = decl.getExtendedTypes().isEmpty() ? null
                : resolveTypeToNodeId(
                    decl.getExtendedTypes(0).getNameWithScope(), packageName).orElse(null);
        }
        return bindConstructor(target, stmt.getArguments().size(), count,
            stmt.isThis() ? "this(...)" : "super(...)");
    }

    private List<String> bindConstructor(String classId, int argCount, boolean count, String shape) {
        if (classId == null) {
            if (count) receiverExternal++;
            return List.of();
        }
        var inits = initsOf(classId);
        if (inits.isEmpty()) {
            if (count) receiverDataType++;
            return List.of();
        }
        if (count) receiverBehavioural++;
        var hits = inits.stream().filter(id -> arityOf(id) == argCount).toList();
        if (hits.isEmpty()) {
            if (count) unresolvedByReceiver.merge("ctor-arity:" + shape + "/" + argCount, 1, Integer::sum);
            return List.of();
        }
        if (count) callSitesResolved++;
        return hits;
    }

    /** True when the candidates hold something other than constructors. */
    private static boolean hasRealMethods(List<String> candidates) {
        return candidates != null && candidates.stream().anyMatch(id -> !MethodIds.isConstructor(id));
    }

    /** Arity of a method reference is not knowable from source: every overload of the name links. */
    private static final int ANY_ARITY = -1;

    /**
     * Resolve {@code Foo::bar}, {@code this::bar}, {@code super::bar} or {@code field::bar} to the
     * same-name methods on the scope's type. {@code Foo::new} is a constructor reference, left to
     * the constructor work and tallied under {@code methodref-new}. An unresolvable scope is counted
     * under {@code methodref-scope}.
     */
    private List<String> resolveReference(MethodReferenceExpr ref, String ownerFqn, String packageName,
                                          Map<String, String> scopeTypes, boolean count) {
        String name = ref.getIdentifier();
        var scope = ref.getScope();
        if (count) callSitesSeen++;
        if ("new".equals(name)) {
            // Foo::new: every constructor of Foo (arity is not knowable from the reference).
            String typeClass = scope instanceof TypeExpr te
                ? resolveTypeToNodeId(te.getType().asString(), packageName)
                    .orElse(null) : null;
            if (typeClass == null) {
                if (count) {
                    receiverExternal++;
                    unresolvedByReceiver.merge("methodref-new:" + scope, 1, Integer::sum);
                }
                return List.of();
            }
            var inits = initsOf(typeClass);
            if (inits.isEmpty()) {
                if (count) receiverDataType++;
                return List.of();
            }
            if (count) { receiverBehavioural++; callSitesResolved++; }
            return inits;
        }

        String receiverClass = null;
        boolean viaSuper = false;
        if (scope instanceof ThisExpr) {
            receiverClass = ownerFqn;
        } else if (scope instanceof SuperExpr) {
            receiverClass = ownerFqn;
            viaSuper = true;
        } else if (scope instanceof TypeExpr te) {
            // JavaParser reads a bare `name::m` as a type expression, so a field, parameter or
            // local of that name shadows a type of the same name.
            String typeText = te.getType().asString();
            String declaredType = scopeTypes.get(typeText);
            receiverClass = resolveTypeToNodeId(
                declaredType != null ? declaredType : typeText, packageName)
                .orElse(null);
        } else {
            String receiverName = null;
            if (scope instanceof NameExpr ne) {
                receiverName = ne.getNameAsString();
            } else if (scope instanceof FieldAccessExpr fae && fae.getScope() instanceof ThisExpr) {
                receiverName = fae.getNameAsString();
            }
            if (receiverName != null) {
                String declaredType = scopeTypes.get(receiverName);
                receiverClass = resolveTypeToNodeId(
                    declaredType != null ? declaredType : receiverName, packageName)
                    .orElse(null);
            }
        }

        if (receiverClass == null) {
            if (count) {
                receiverExternal++;
                unresolvedByReceiver.merge("methodref-scope:" + scope + "::" + name, 1, Integer::sum);
            }
            return List.of();
        }
        var candidates = methodIdsByClass.get(receiverClass);
        if (!hasRealMethods(candidates)) {
            if (count) receiverDataType++;
            return List.of();
        }
        if (count) receiverBehavioural++;
        if (!viaSuper) {
            var direct = pickOverloads(candidates, name, ANY_ARITY);
            if (!direct.isEmpty()) {
                if (count) callSitesResolved++;
                return direct;
            }
        }
        pending.add(new PendingCall(currentCallerId, receiverClass, name, ANY_ARITY));
        return List.of();
    }

    /** Resolve one call site to method node ids, or empty when the receiver type is unknown. */
    private List<String> resolveTarget(MethodCallExpr call, String ownerFqn, String packageName,
                                       Map<String, String> scopeTypes, boolean count) {
        if (call.getScope().orElse(null) instanceof MethodCallExpr) {
            var link = chainLink(call, ownerFqn, packageName, scopeTypes);
            if (link == null) {
                if (count) receiverExternal++;
                return List.of();
            }
            pendingChains.add(new PendingChain(currentCallerId, link, count));
            return List.of();
        }
        String receiverClass = receiverClassOf(call, ownerFqn, packageName, scopeTypes).orElse(null);
        if (receiverClass == null) {
            if (count) receiverExternal++;
            return List.of();
        }

        var candidates = methodIdsByClass.get(receiverClass);
        if (!hasRealMethods(candidates)) {
            if (count) receiverDataType++;
            return List.of();
        }

        if (count) receiverBehavioural++;
        var direct = pickOverloads(candidates, call.getNameAsString(), call.getArguments().size());
        if (!direct.isEmpty()) return direct;

        // The name may be declared on a supertype (repo.save() where save() lives on the
        // interface). Supertype edges are written by the class-level pass running over the same
        // files, so they are not all present yet — park the call and retry in resolvePending().
        pending.add(new PendingCall(currentCallerId, receiverClass,
            call.getNameAsString(), call.getArguments().size()));
        return List.of();
    }

    /**
     * The chain link for {@code call} (whose scope is a call), or null when the innermost receiver
     * is unknown. Walks down to the innermost call iteratively and memoises every link per body.
     */
    private Link chainLink(MethodCallExpr call, String ownerFqn, String packageName,
                           Map<String, String> scopeTypes) {
        var pendingCalls = new ArrayList<MethodCallExpr>();
        Optional<Link> parent = Optional.empty();
        boolean parentKnown = false;
        MethodCallExpr c = call;
        while (true) {
            var memo = currentChainLinks.get(c);
            if (memo != null) { parent = memo; parentKnown = true; break; }
            pendingCalls.add(c);
            if (c.getScope().orElse(null) instanceof MethodCallExpr inner) c = inner;
            else break;
        }
        if (!parentKnown) {
            // pendingCalls ends with the innermost call, whose receiver is not a call.
            var innermost = pendingCalls.remove(pendingCalls.size() - 1);
            String base = receiverClassOf(innermost, ownerFqn, packageName, scopeTypes).orElse(null);
            parent = base == null ? Optional.empty()
                : Optional.of(new Link(null, base, innermost.getNameAsString(),
                    innermost.getArguments().size()));
            currentChainLinks.put(innermost, parent);
        }
        for (int i = pendingCalls.size() - 1; i >= 0; i--) {
            var cur = pendingCalls.get(i);
            var p = parent;
            parent = p.map(l -> new Link(l, null, cur.getNameAsString(), cur.getArguments().size()));
            currentChainLinks.put(cur, parent);
        }
        return parent.orElse(null);
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
                return resolver.isKnown(importedFrom) ? Optional.of(importedFrom) : Optional.empty();
            }
            for (String wildcard : currentStaticWildcards) {
                if (methodIdsByClass.containsKey(wildcard)) return Optional.of(wildcard);
            }
            // A wildcard static import from a type this graph does not hold (assertj, mockito)
            // means the call leaves the project rather than staying inside the caller.
            if (!currentStaticWildcards.isEmpty()) return Optional.empty();
            return Optional.of(ownerFqn);
        }

        // new Foo().bar(): the type is written out, so it is certain.
        if (scope instanceof ObjectCreationExpr oce && oce.getAnonymousClassBody().isEmpty()) {
            return resolveTypeToNodeId(oce.getType().getNameWithScope(), packageName);
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
            return resolveTypeToNodeId(declaredType, packageName);
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
        if (byName.size() <= 1 || argCount == ANY_ARITY) return byName;

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

    /**
     * Resolve a simple type name written in the current file to an indexed node id, through the
     * shared {@link JavaTypeResolver} (its javadoc has the order and the reason for it).
     */
    private Optional<String> resolveTypeToNodeId(String simpleName, String packageName) {
        return resolver.resolve(simpleName, currentCtx, currentOwner);
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
        if (pending.isEmpty() && pendingChains.isEmpty()) return List.of();

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

        var resolved = new IdentityHashMap<Link, Resolved>();
        for (PendingChain chain : pendingChains) {
            var r = resolveLink(chain.link(), superTypes, resolved);
            boolean count = chain.count();
            if (!r.targets().isEmpty()) {
                if (count) { receiverBehavioural++; callSitesResolved++; }
                for (String targetId : r.targets()) {
                    if (targetId.equals(chain.callerId())) continue;
                    if (!seen.add(chain.callerId() + ">" + targetId)) continue;
                    edges.add(new Edge(UUID.randomUUID().toString(),
                        EdgeType.CALLS, chain.callerId(), targetId));
                }
            } else if (!count) {
                continue;
            } else if (r.dataType()) {
                receiverDataType++;
            } else if (r.parentLabel() != null) {
                receiverExternal++;
                unresolvedByReceiver.merge(r.parentLabel(), 1, Integer::sum);
            } else if (!r.parentBound()) {
                receiverExternal++;
            } else {
                receiverBehavioural++;
                String name = chain.link().name;
                if (isGeneratedAccessor(name)) accessorMiss++;
                else unresolvedByReceiver.merge("chain-miss:" + name + "/" + chain.link().argCount,
                    1, Integer::sum);
            }
        }
        pendingChains.clear();
        return edges;
    }

    /**
     * Resolve a chain link: its receiver class is the base class (first link) or the previous
     * link's return type, which counts only when that link bound to exactly one indexed method.
     * Iterative over the parent chain, memoised per link.
     */
    private Resolved resolveLink(Link link, Map<String, List<String>> superTypes,
                                 Map<Link, Resolved> memo) {
        var chain = new ArrayList<Link>();
        for (Link l = link; l != null && !memo.containsKey(l); l = l.parent) chain.add(l);
        for (int i = chain.size() - 1; i >= 0; i--) {
            Link l = chain.get(i);
            Resolved parentResult = l.parent == null ? null : memo.get(l.parent);
            String receiverClass = l.parent == null ? l.baseClass : parentResult.returnClass();
            Resolved r;
            if (receiverClass == null) {
                String label = null;
                boolean parentBound = parentResult != null && !parentResult.targets().isEmpty();
                if (parentBound) {
                    String via = parentResult.targets().size() == 1 ? parentResult.targets().get(0)
                        : parentResult.targets().size() + " overloads";
                    label = "chain-return:" + via + ">" + l.name;
                }
                r = new Resolved(List.of(), null, false, parentBound, label);
            } else {
                var candidates = methodIdsByClass.get(receiverClass);
                if (!hasRealMethods(candidates)) {
                    r = new Resolved(List.of(), null, true, true, null);
                } else {
                    var targets = pickOverloads(candidates, l.name, l.argCount);
                    if (targets.isEmpty()) {
                        targets = findInHierarchy(new PendingCall(null, receiverClass, l.name, l.argCount),
                            superTypes, new HashSet<>());
                    }
                    String returnClass = targets.size() == 1 ? returnClassOf(targets.get(0)) : null;
                    r = new Resolved(targets, returnClass, false, true, null);
                }
            }
            memo.put(l, r);
        }
        return memo.get(link);
    }

    /** The indexed class a method's declared return type names, resolved in its declaring file. */
    private String returnClassOf(String methodId) {
        var ctx = fileCtxByClass.get(MethodIds.declaringClass(methodId));
        // A class parsed in this run answers from this run; any other class was parsed by an
        // earlier run, whose answer was stored next to the graph.
        if (ctx == null) return store.findReturnClass(methodId).orElse(null);
        return resolveReturnClass(methodId, ctx);
    }

    private String resolveReturnClass(String methodId, JavaTypeResolver.FileContext ctx) {
        String type = returnTypeByMethodId.get(methodId);
        if (type == null) return null;
        return resolver.resolve(type, ctx, MethodIds.declaringClass(methodId)).orElse(null);
    }

    /**
     * Store the return class of every method parsed in this run, replacing the rows of those
     * classes. Call once every file of the run has been seen: a return type may name a class
     * declared in a file parsed later, which is why this is not done in {@link #extract}.
     */
    public void persistReturnClasses() {
        for (String classId : fileCtxByClass.keySet()) store.deleteReturnClassesOfClass(classId);
        for (String methodId : returnTypeByMethodId.keySet()) {
            var ctx = fileCtxByClass.get(MethodIds.declaringClass(methodId));
            if (ctx == null) continue;
            String classId = resolveReturnClass(methodId, ctx);
            if (classId != null) store.saveReturnClass(methodId, classId);
        }
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
        return deriveOverrides(null);
    }

    /**
     * Same, limited to the methods of the given classes (null: every class). An incremental sync
     * re-derives only the classes of the files it re-extracted; the rest keep their stored edges.
     */
    public List<Edge> deriveOverrides(Set<String> onlyClasses) {
        var edges = new ArrayList<Edge>();
        var methodsByClass = methodIdsByClass;

        Set<EdgeType> superTypeEdges = Set.of(EdgeType.IMPLEMENTS, EdgeType.EXTENDS);
        for (var entry : methodsByClass.entrySet()) {
            String classFqn = entry.getKey();
            if (onlyClasses != null && !onlyClasses.contains(classFqn)) continue;
            for (Edge superEdge : store.findEdgesFrom(classFqn)) {
                if (!superTypeEdges.contains(superEdge.type())) continue;
                var superMethods = methodsByClass.get(superEdge.targetId());
                if (superMethods == null) continue;

                for (String methodId : entry.getValue()) {
                    if (MethodIds.isConstructor(methodId)) continue;   // a constructor never overrides
                    String name = MethodIds.methodName(methodId);
                    int arity = arityOf(methodId);
                    // Same-name, same-arity overloads: the lowest id, never the lowest row, so a
                    // node replaced in place gives the edge a fresh index would.
                    superMethods.stream()
                        .filter(sid -> name.equals(MethodIds.methodName(sid)) && arityOf(sid) == arity)
                        .min(java.util.Comparator.naturalOrder())
                        .ifPresent(sid -> edges.add(new Edge(UUID.randomUUID().toString(),
                            EdgeType.OVERRIDES, methodId, sid)));
                }
            }
        }
        return edges;
    }
}
