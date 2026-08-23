package com.codenavigator.indexer;

import com.codenavigator.graph.GraphStore.MethodRecord;
import com.codenavigator.graph.MethodIds;
import com.codenavigator.graph.Node;
import com.codenavigator.graph.NodeType;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class MethodExtractor {

    /**
     * Node types whose members are data, not behaviour. Records and enums describe their shape
     * through fields, domain events and commands are payloads, and library types are external —
     * minting METHOD nodes for them multiplies the graph without answering a question anyone asks.
     * Every other class-like type gets methods, including AGGREGATE, which the original inclusion
     * list omitted and which is where the business rules actually live.
     */
    private static final Set<NodeType> DATA_TYPES = Set.of(
        NodeType.RECORD, NodeType.ENUM, NodeType.DOMAIN_EVENT,
        NodeType.COMMAND, NodeType.QUERY, NodeType.LIBRARY,
        NodeType.FE_MODEL, NodeType.FE_ENUM, NodeType.FE_CONSTANT, NodeType.METHOD
    );

    /**
     * One extracted method: the signature row for the {@code methods} side-table plus the graph
     * node that carries its identity and edges.
     *
     * @param overrideCandidate carries {@code @Override}, so an OVERRIDES edge should be sought
     */
    public record ExtractedMethod(MethodRecord record, Node node, String declaringClassId,
                                  List<String> paramTypes, boolean overrideCandidate) {}

    /**
     * Extract methods for every non-data node declared in {@code cu}.
     *
     * <p>Record components are still emitted as {@code field}-kind {@link MethodRecord}s (the
     * briefing renders models from them) but never become nodes — a record component is data.
     */
    public List<ExtractedMethod> extract(CompilationUnit cu, List<Node> nodes, String filePath,
                                         long lastModified) {
        var results = new ArrayList<ExtractedMethod>();
        var packageName = cu.getPackageDeclaration()
            .map(pd -> pd.getNameAsString()).orElse("");

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(decl -> {
            String qualifiedName = qualify(packageName, decl.getNameAsString());
            var owner = nodes.stream().filter(n -> n.id().equals(qualifiedName)).findFirst();
            if (owner.isEmpty()) return;
            if (DATA_TYPES.contains(owner.get().type())) return;

            String basePath = extractAnnotationValue(decl, "RequestMapping");

            decl.getMethods().forEach(m -> {
                String httpAnnotation = extractHttpAnnotation(basePath, m);
                var paramTypes = m.getParameters().stream()
                    .map(p -> MethodIds.simplifyType(p.getTypeAsString()))
                    .toList();
                String params = m.getParameters().stream()
                    .map(p -> p.getTypeAsString() + " " + p.getNameAsString())
                    .collect(Collectors.joining(", "));

                var record = new MethodRecord(
                    qualifiedName,
                    m.getNameAsString(),
                    m.getTypeAsString(),
                    params.isEmpty() ? "" : params,
                    httpAnnotation,
                    visibilityOf(m, decl));

                String methodId = MethodIds.of(qualifiedName, m.getNameAsString(), paramTypes);
                var node = new Node(
                    methodId,
                    NodeType.METHOD,
                    m.getNameAsString(),
                    methodId,
                    filePath,
                    m.getBegin().map(p -> p.line).orElse(owner.get().lineNumber()),
                    signatureOf(m),
                    lastModified);

                results.add(new ExtractedMethod(record, node, qualifiedName, paramTypes,
                    hasAnnotation(m, "Override")));
            });
        });

        // Records: components stay signature rows so the briefing can render model shapes,
        // but they are data and never become nodes.
        cu.findAll(RecordDeclaration.class).forEach(decl -> {
            String qualifiedName = qualify(packageName, decl.getNameAsString());
            var owner = nodes.stream().filter(n -> n.id().equals(qualifiedName)).findFirst();
            if (owner.isEmpty()) return;

            decl.getParameters().forEach(p ->
                results.add(new ExtractedMethod(
                    new MethodRecord(qualifiedName, p.getNameAsString(),
                        MethodIds.simplifyType(p.getTypeAsString()), null, null, "field"),
                    null, qualifiedName, List.of(), false))
            );
        });

        return results;
    }

    private static String qualify(String packageName, String simpleName) {
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    private static String visibilityOf(MethodDeclaration m, ClassOrInterfaceDeclaration decl) {
        if (decl.isInterface()) return "interface";
        if (m.isPublic()) return "public";
        if (m.isProtected()) return "protected";
        if (m.isPrivate()) return "private";
        return "package";
    }

    /**
     * The declaration line only — never the body. A METHOD node exists so an agent can find the
     * method and Read the exact range; storing bodies would multiply the database for text the
     * agent can fetch precisely when it needs it.
     */
    private static String signatureOf(MethodDeclaration m) {
        var modifiers = m.getModifiers().stream()
            .map(mod -> mod.getKeyword().asString())
            .collect(Collectors.joining(" "));
        String params = m.getParameters().stream()
            .map(p -> p.getTypeAsString() + " " + p.getNameAsString())
            .collect(Collectors.joining(", "));
        return (modifiers.isBlank() ? "" : modifiers + " ")
            + m.getTypeAsString() + " " + m.getNameAsString() + "(" + params + ")";
    }

    private static boolean hasAnnotation(NodeWithAnnotations<?> node, String name) {
        return node.getAnnotations().stream().anyMatch(a -> a.getNameAsString().equals(name));
    }

    private String extractHttpAnnotation(String basePath, MethodDeclaration method) {
        for (String httpMethod : List.of("Get", "Post", "Put", "Delete", "Patch")) {
            String annotName = httpMethod + "Mapping";
            String path = extractAnnotationValue(method, annotName);
            if (path != null) {
                String fullPath = (basePath != null ? basePath : "") + path;
                return httpMethod.toUpperCase() + " " + fullPath;
            }
        }
        return null;
    }

    private String extractAnnotationValue(NodeWithAnnotations<?> node, String annotationName) {
        for (var annot : node.getAnnotations()) {
            if (!annot.getNameAsString().equals(annotationName)) continue;

            if (annot instanceof SingleMemberAnnotationExpr sma) {
                return stripQuotes(sma.getMemberValue().toString());
            }
            if (annot instanceof NormalAnnotationExpr na) {
                for (var pair : na.getPairs()) {
                    if (pair.getNameAsString().equals("value") || pair.getNameAsString().equals("path")) {
                        return stripQuotes(pair.getValue().toString());
                    }
                }
            }
            if (annot instanceof MarkerAnnotationExpr) {
                return "";
            }
        }
        return null;
    }

    private static String stripQuotes(String value) {
        if (value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
