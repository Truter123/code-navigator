package com.codenavigator.indexer;

import com.codenavigator.graph.MethodIds;
import com.codenavigator.graph.Node;
import com.codenavigator.graph.NodeType;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The part of a Java file other files can see, as one SHA-256.
 *
 * <p>A later sync compares the stored hash with the hash of the edited file: equal means only
 * bodies, comments, fields or layout changed, so no other file resolves differently and only this
 * file needs re-extraction. Unequal means a name, a signature, an import or a stereotype moved and
 * the dependants cannot be trusted.
 *
 * <p>Held, in a canonical sorted text form: the package and every import; per type its node id,
 * node type (a stereotype that may depend on the body, so it is read from the extracted node and
 * never re-derived), name, qualified name, kind, {@code extends}/{@code implements} as written,
 * type parameter names, annotation names and whether it is an entry point; per method and
 * constructor its METHOD id, declared return type, parameter types as written, annotation names,
 * access and {@code static}. Left out on purpose: field types and names (they never cross files),
 * line numbers, snippets, comments, javadoc and bodies.
 */
public final class JavaFileShape {

    private JavaFileShape() {}

    /**
     * @param typeNodes the type nodes the node extractor produced for this file (METHOD nodes are skipped)
     * @param methods the methods the method extractor produced for this file
     * @param entryPointIds ids of the file's types stored as entry points
     */
    public static String compute(CompilationUnit cu, List<Node> typeNodes,
                                 List<MethodExtractor.ExtractedMethod> methods, Set<String> entryPointIds) {
        var lines = new ArrayList<String>();
        String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
        lines.add("package " + pkg);
        cu.getImports().forEach(imp -> lines.add("import " + (imp.isStatic() ? "static " : "")
            + imp.getNameAsString() + (imp.isAsterisk() ? ".*" : "")));

        var typeDecls = new HashMap<String, TypeDeclaration<?>>();
        var callables = new HashMap<String, CallableDeclaration<?>>();
        for (Object o : cu.findAll(TypeDeclaration.class)) {
            var decl = (TypeDeclaration<?>) o;
            String qualified = JavaTypeResolver.typeId(pkg, decl);
            typeDecls.putIfAbsent(qualified, decl);
            decl.getMethods().forEach(m -> callables.putIfAbsent(
                MethodIds.of(qualified, m.getNameAsString(), simplifiedParams(m)), m));
            decl.getConstructors().forEach(c -> callables.putIfAbsent(
                MethodIds.of(qualified, MethodIds.INIT, simplifiedParams(c)), c));
        }

        for (Node n : typeNodes) {
            if (n.type() == NodeType.METHOD) continue;
            var decl = typeDecls.get(n.id());
            String kind = "";
            List<String> extended = List.of();
            List<String> implemented = List.of();
            List<String> typeParams = List.of();
            if (decl instanceof ClassOrInterfaceDeclaration c) {
                kind = c.isInterface() ? "interface" : "class";
                extended = written(c.getExtendedTypes());
                implemented = written(c.getImplementedTypes());
                typeParams = c.getTypeParameters().stream().map(tp -> tp.getNameAsString()).toList();
            } else if (decl instanceof RecordDeclaration r) {
                kind = "record";
                implemented = written(r.getImplementedTypes());
                typeParams = r.getTypeParameters().stream().map(tp -> tp.getNameAsString()).toList();
            } else if (decl instanceof EnumDeclaration e) {
                kind = "enum";
                implemented = written(e.getImplementedTypes());
            }
            lines.add("type|" + n.id() + "|" + n.type() + "|" + n.name() + "|" + n.qualifiedName()
                + "|" + kind + "|extends=" + String.join(",", extended)
                + "|implements=" + String.join(",", implemented)
                + "|typeParams=" + String.join(",", typeParams)
                + "|annotations=" + annotationNames(decl == null ? List.of() : decl.getAnnotations())
                + "|entry=" + entryPointIds.contains(n.id()));
        }

        for (var em : methods) {
            if (em.node() == null) continue;
            String id = em.node().id();
            var callable = callables.get(id);
            if (callable == null) {                      // the implicit <init>() of an initializer block
                lines.add("method|" + id);
                continue;
            }
            String ret = callable instanceof MethodDeclaration m ? m.getTypeAsString() : "";
            boolean isStatic = callable instanceof MethodDeclaration m && m.isStatic();
            String params = callable.getParameters().stream()
                .map(p -> p.getTypeAsString() + (p.isVarArgs() ? "..." : ""))
                .collect(Collectors.joining(","));
            lines.add("method|" + id + "|return=" + ret + "|params=" + params
                + "|annotations=" + annotationNames(callable.getAnnotations())
                + "|access=" + callable.getAccessSpecifier().asString() + "|static=" + isStatic);
        }

        lines.sort(null);
        return sha256(String.join("\n", lines));
    }

    private static List<String> simplifiedParams(CallableDeclaration<?> c) {
        return c.getParameters().stream().map(p -> MethodIds.simplifyType(p.getTypeAsString())).toList();
    }

    private static List<String> written(List<ClassOrInterfaceType> types) {
        return types.stream().map(ClassOrInterfaceType::asString).sorted().toList();
    }

    private static String annotationNames(List<AnnotationExpr> annotations) {
        return annotations.stream().map(AnnotationExpr::getNameAsString).sorted()
            .collect(Collectors.joining(","));
    }

    private static String sha256(String text) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
