package com.codenavigator.indexer;

import com.codenavigator.graph.Node;
import com.codenavigator.graph.NodeType;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The one place a type name written in a Java file becomes the id of an indexed type node, and the
 * one place the id of a declared type is built ({@link #typeId(String, TypeDeclaration)}). Built
 * once from the stored nodes; both {@link MethodCallExtractor} and {@link EdgeExtractor} resolve
 * through it, so a call edge and a class-level edge never disagree about what {@code Foo} means in
 * a file.
 *
 * <p>A nested type's id is outer-qualified at every depth: {@code pkg.Outer.Inner}.
 *
 * <p>Order for a simple name, following Java scoping (Java Language Specification 6.4.1: a member
 * type shadows an import, and a single-type import shadows a type of the same package declared in
 * another file):
 * <ol>
 *   <li>member types in scope: the enclosing type's own member, then the members it inherits
 *       from its supertypes (written {@code extends}/{@code implements}, level by level; two
 *       different candidates at one level resolve to nothing), then the same two on each enclosing
 *       level outward, then a top-level type of this file; failing that, the one type of this
 *       file with that simple name (a sibling's member type). Supertypes of a type declared in
 *       another file are read by parsing that file on demand ({@link #withSourceRoot}), so the
 *       rule goes as deep as the hierarchy is indexed Java; with no source root only supertypes
 *       declared in the current file are known;</li>
 *   <li>an explicit {@code import a.b.C;} of that simple name: the imported id when indexed,
 *       otherwise nothing (an imported library type must not bind to a same-named type of the
 *       package);</li>
 *   <li>the same package;</li>
 *   <li>the unique indexed type of that simple name;</li>
 *   <li>a unique hit through a wildcard {@code import a.b.*;};</li>
 *   <li>the unique {@code .java} candidate when the other matches are other languages. A name
 *       shared between a Java aggregate and a TypeScript/Dart model (CLAUDE.md, Symbol
 *       resolution) is recovered this way; two Java candidates with no import resolve to none.</li>
 * </ol>
 * A dotted name ({@code Outer.Inner}, {@code pkg.Outer.Inner}) is tried as an exact id, then as its
 * first segment resolved by the order above plus the rest, then by its last segment alone.
 * LIBRARY, SHELL_COMMAND and METHOD nodes are never candidates: library nodes are minted during the
 * edge pass, so counting one would make a name resolve differently depending on file order.
 */
public final class JavaTypeResolver {

    /** Package, single-type imports (simple name to fqn), wildcard imports and the ids of types declared in a file. */
    public record FileContext(String packageName, Map<String, String> imports, List<String> wildcards,
                              Set<String> declaredTypes, Map<String, List<String>> supertypes) {

        public FileContext(String packageName, Map<String, String> imports, List<String> wildcards,
                           Set<String> declaredTypes) {
            this(packageName, imports, wildcards, declaredTypes, Map.of());
        }

        public static FileContext of(CompilationUnit cu) {
            var packageName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
            var imports = new HashMap<String, String>();
            var wildcards = new ArrayList<String>();
            cu.getImports().forEach(imp -> {
                if (imp.isStatic()) return;
                String name = imp.getNameAsString();
                if (imp.isAsterisk()) {
                    wildcards.add(name);
                    return;
                }
                int lastDot = name.lastIndexOf('.');
                if (lastDot > 0) imports.put(name.substring(lastDot + 1), name);
            });
            var declared = new HashSet<String>();
            var supertypes = new HashMap<String, List<String>>();
            cu.findAll(TypeDeclaration.class).forEach(t -> {
                String id = typeId(packageName, t);
                declared.add(id);
                var written = new ArrayList<String>();
                if (t instanceof ClassOrInterfaceDeclaration c) {
                    c.getExtendedTypes().forEach(x -> written.add(x.getNameWithScope()));
                    c.getImplementedTypes().forEach(x -> written.add(x.getNameWithScope()));
                } else if (t instanceof RecordDeclaration r) {
                    r.getImplementedTypes().forEach(x -> written.add(x.getNameWithScope()));
                } else if (t instanceof EnumDeclaration e) {
                    e.getImplementedTypes().forEach(x -> written.add(x.getNameWithScope()));
                }
                supertypes.put(id, written);
            });
            return new FileContext(packageName, imports, wildcards, declared, supertypes);
        }
    }

    private final Map<String, List<String>> typeIdsBySimpleName = new HashMap<>();
    private final Set<String> knownTypeIds = new HashSet<>();
    /** Source file per type id, to tell a Java candidate from another language's colliding name. */
    private final Map<String, String> typeFilePathById = new HashMap<>();

    private static final String AMBIGUOUS = "\0ambiguous";
    private Path sourceRoot;
    /** Parsed on demand: the context of a file that declares a supertype of the file being read. */
    private final Map<String, Optional<FileContext>> contextByPath = new HashMap<>();
    private final Map<String, List<String>> superIdsByType = new HashMap<>();
    private final Set<String> superInProgress = new HashSet<>();

    /** Project root the stored (relative) file paths hang off; enables supertypes in other files. */
    public JavaTypeResolver withSourceRoot(Path root) {
        this.sourceRoot = root;
        return this;
    }

    public JavaTypeResolver(Collection<Node> allNodes) {
        for (Node n : allNodes) {
            if (n.type() == NodeType.LIBRARY || n.type() == NodeType.SHELL_COMMAND
                || n.type() == NodeType.METHOD) continue;
            knownTypeIds.add(n.id());
            typeIdsBySimpleName.computeIfAbsent(n.name(), k -> new ArrayList<>()).add(n.id());
            typeFilePathById.put(n.id(), n.filePath());
        }
    }

    public boolean isKnown(String typeId) {
        return knownTypeIds.contains(typeId);
    }

    /** The id a top-level type of this simple name has in a package. */
    static String typeId(String packageName, String simpleName) {
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    /**
     * The id of a declared type: the package, then every enclosing type declaration outermost
     * first, then the type. The one place that formula lives; every extractor uses it.
     */
    public static String typeId(String packageName, TypeDeclaration<?> decl) {
        var names = new ArrayList<String>();
        names.add(decl.getNameAsString());
        var parent = decl.getParentNode();
        while (parent.isPresent()) {
            if (parent.get() instanceof TypeDeclaration<?> outer) names.add(0, outer.getNameAsString());
            parent = parent.get().getParentNode();
        }
        return typeId(packageName, String.join(".", names));
    }

    public Optional<String> resolve(String written, FileContext ctx) {
        return resolve(written, ctx, null);
    }

    /**
     * @param written     the type as written, generic arguments and array brackets allowed
     * @param enclosingId id of the type the reference is written in; null when unknown
     */
    public Optional<String> resolve(String written, FileContext ctx, String enclosingId) {
        String text = clean(written);
        if (text.isEmpty()) return Optional.empty();
        int dot = text.indexOf('.');
        if (dot < 0) return resolveSimple(text, ctx, enclosingId);

        if (knownTypeIds.contains(text)) return Optional.of(text);
        var head = resolveSimple(text.substring(0, dot), ctx, enclosingId);
        if (head.isPresent()) {
            String id = head.get() + text.substring(dot);
            if (knownTypeIds.contains(id)) return Optional.of(id);
        }
        return resolveSimple(text.substring(text.lastIndexOf('.') + 1), ctx, enclosingId);
    }

    /** Generic arguments, array brackets, varargs dots and whitespace removed. */
    static String clean(String written) {
        if (written == null) return "";
        var sb = new StringBuilder();
        int depth = 0;
        for (char c : written.toCharArray()) {
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (depth == 0 && !Character.isWhitespace(c)) sb.append(c);
        }
        String s = sb.toString().replace("[]", "");
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private Optional<String> resolveSimple(String simpleName, FileContext ctx, String enclosingId) {
        String member = memberTypeInScope(simpleName, ctx, enclosingId);
        if (AMBIGUOUS.equals(member)) return Optional.empty();
        if (member != null) return Optional.of(member);

        String imported = ctx.imports().get(simpleName);
        if (imported != null) {
            return knownTypeIds.contains(imported) ? Optional.of(imported) : Optional.empty();
        }

        if (!ctx.packageName().isEmpty()) {
            String fqn = typeId(ctx.packageName(), simpleName);
            if (knownTypeIds.contains(fqn)) return Optional.of(fqn);
        }

        var matches = typeIdsBySimpleName.getOrDefault(simpleName, List.of());
        if (matches.size() == 1) return Optional.of(matches.get(0));
        if (matches.isEmpty()) return Optional.empty();

        var wildcardMatches = ctx.wildcards().stream()
            .map(pkg -> pkg + "." + simpleName)
            .filter(knownTypeIds::contains)
            .distinct()
            .toList();
        if (wildcardMatches.size() == 1) return Optional.of(wildcardMatches.get(0));

        var javaMatches = matches.stream().filter(this::isJavaType).toList();
        if (javaMatches.size() == 1) return Optional.of(javaMatches.get(0));

        return Optional.empty();
    }

    /** A type declared in this file that the name can mean from where it is written, or null. */
    private String memberTypeInScope(String simpleName, FileContext ctx, String enclosingId) {
        String pkg = ctx.packageName();
        String base = pkg.isEmpty() ? "" : pkg + ".";
        if (enclosingId != null && enclosingId.startsWith(base)) {
            String cur = enclosingId;
            while (cur.length() > base.length()) {
                String candidate = cur + "." + simpleName;
                if (ctx.declaredTypes().contains(candidate) && knownTypeIds.contains(candidate)) return candidate;
                String inherited = inheritedMember(cur, simpleName, ctx);
                if (inherited != null) return inherited;
                int d = cur.lastIndexOf('.');
                if (d < base.length()) break;
                cur = cur.substring(0, d);
            }
        }
        String top = typeId(pkg, simpleName);
        if (ctx.declaredTypes().contains(top) && knownTypeIds.contains(top)) return top;

        String only = null;
        int count = 0;
        for (String id : ctx.declaredTypes()) {
            if (id.substring(id.lastIndexOf('.') + 1).equals(simpleName)) { only = id; count++; }
        }
        return count == 1 && knownTypeIds.contains(only) ? only : null;
    }

    /** Member type of that name inherited by {@code typeId}: AMBIGUOUS, an id, or null. */
    private String inheritedMember(String typeId, String simpleName, FileContext ctx) {
        var visited = new HashSet<String>();
        visited.add(typeId);
        List<String> level = superIds(typeId, ctx);
        while (!level.isEmpty()) {
            var found = new LinkedHashSet<String>();
            var next = new ArrayList<String>();
            for (String sid : level) {
                if (!visited.add(sid)) continue;
                String candidate = sid + "." + simpleName;
                if (knownTypeIds.contains(candidate)) found.add(candidate);
                next.addAll(superIds(sid, ctx));
            }
            if (found.size() == 1) return found.iterator().next();
            if (found.size() > 1) return AMBIGUOUS;
            level = next;
        }
        return null;
    }

    /** The indexed supertypes a type writes, resolved in the scope enclosing it; cycles give nothing. */
    private List<String> superIds(String typeId, FileContext current) {
        var cached = superIdsByType.get(typeId);
        if (cached != null) return cached;
        if (!superInProgress.add(typeId)) return List.of();
        try {
            FileContext ctx = current.supertypes().containsKey(typeId) ? current : contextDeclaring(typeId);
            if (ctx == null) return List.of();
            String base = ctx.packageName().isEmpty() ? "" : ctx.packageName() + ".";
            int d = typeId.lastIndexOf('.');
            String outer = d >= base.length() && d > 0 ? typeId.substring(0, d) : null;
            var ids = new ArrayList<String>();
            for (String written : ctx.supertypes().getOrDefault(typeId, List.of())) {
                resolve(written, ctx, outer).ifPresent(id -> {
                    if (!id.equals(typeId) && !ids.contains(id)) ids.add(id);
                });
            }
            superIdsByType.put(typeId, ids);
            return ids;
        } finally {
            superInProgress.remove(typeId);
        }
    }

    private FileContext contextDeclaring(String typeId) {
        String path = typeFilePathById.get(typeId);
        if (path == null || sourceRoot == null || !path.endsWith(".java")) return null;
        return contextByPath.computeIfAbsent(path, p -> {
            try {
                return Optional.of(FileContext.of(StaticJavaParser.parse(sourceRoot.resolve(p))));
            } catch (Exception | StackOverflowError e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    private boolean isJavaType(String typeId) {
        String path = typeFilePathById.get(typeId);
        return path != null && path.endsWith(".java");
    }
}
