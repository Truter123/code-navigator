package com.codenavigator.indexer;

import com.codenavigator.graph.GraphStore.MethodRecord;
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

    private static final Set<NodeType> METHOD_TYPES = Set.of(
        NodeType.CONTROLLER, NodeType.SERVICE, NodeType.REPOSITORY,
        NodeType.COMMAND_HANDLER, NodeType.QUERY_HANDLER, NodeType.EVENT_LISTENER,
        NodeType.PROJECTION_HANDLER
    );

    public List<MethodRecord> extract(CompilationUnit cu, List<Node> nodes) {
        var results = new ArrayList<MethodRecord>();
        var packageName = cu.getPackageDeclaration()
            .map(pd -> pd.getNameAsString()).orElse("");

        // Extract from classes/interfaces
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(decl -> {
            String qualifiedName = packageName.isEmpty() ? decl.getNameAsString()
                : packageName + "." + decl.getNameAsString();
            var node = nodes.stream().filter(n -> n.id().equals(qualifiedName)).findFirst();
            if (node.isEmpty()) return;
            if (!METHOD_TYPES.contains(node.get().type())) return;

            String basePath = extractAnnotationValue(decl, "RequestMapping");

            decl.getMethods().stream()
                .filter(m -> m.isPublic() || decl.isInterface())
                .forEach(m -> {
                    String httpAnnotation = extractHttpAnnotation(basePath, m);
                    String params = m.getParameters().stream()
                        .map(p -> p.getTypeAsString() + " " + p.getNameAsString())
                        .collect(Collectors.joining(", "));
                    results.add(new MethodRecord(
                        qualifiedName,
                        m.getNameAsString(),
                        m.getTypeAsString(),
                        params.isEmpty() ? "" : params,
                        httpAnnotation,
                        m.isPublic() ? "public" : "interface"
                    ));
                });
        });

        // Extract from records
        cu.findAll(RecordDeclaration.class).forEach(decl -> {
            String qualifiedName = packageName.isEmpty() ? decl.getNameAsString()
                : packageName + "." + decl.getNameAsString();
            var node = nodes.stream().filter(n -> n.id().equals(qualifiedName)).findFirst();
            if (node.isEmpty()) return;

            decl.getParameters().forEach(p ->
                results.add(new MethodRecord(
                    qualifiedName,
                    p.getNameAsString(),
                    simplifyType(p.getTypeAsString()),
                    null,
                    null,
                    "field"
                ))
            );
        });

        return results;
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

    private static String simplifyType(String type) {
        int lastDot = type.lastIndexOf('.');
        return lastDot >= 0 ? type.substring(lastDot + 1) : type;
    }

    private static String stripQuotes(String value) {
        if (value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
