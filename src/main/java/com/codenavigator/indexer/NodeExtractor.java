package com.codenavigator.indexer;

import com.codenavigator.graph.Node;
import com.codenavigator.graph.NodeType;
import com.codenavigator.graph.Project;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.util.ArrayList;
import java.util.List;

public class NodeExtractor {

    private final Project tier;

    public NodeExtractor(Project tier) {
        this.tier = tier;
    }

    public List<Node> extract(CompilationUnit cu, String filePath) {
        var nodes = new ArrayList<Node>();
        var packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(decl -> {
            var name = decl.getNameAsString();
            var qualifiedName = packageName.isEmpty() ? name : packageName + "." + name;
            var type = classifyClassOrInterface(decl);
            nodes.add(buildNode(qualifiedName, type, name, filePath, decl));
        });

        cu.findAll(RecordDeclaration.class).forEach(decl -> {
            var name = decl.getNameAsString();
            var qualifiedName = packageName.isEmpty() ? name : packageName + "." + name;
            var type = classifyRecord(decl);
            nodes.add(buildNode(qualifiedName, type, name, filePath, decl));
        });

        cu.findAll(EnumDeclaration.class).forEach(decl -> {
            var name = decl.getNameAsString();
            var qualifiedName = packageName.isEmpty() ? name : packageName + "." + name;
            nodes.add(buildNode(qualifiedName, NodeType.ENUM, name, filePath, decl));
        });

        return nodes;
    }

    private NodeType classifyClassOrInterface(ClassOrInterfaceDeclaration decl) {
        // Project 3: DDD detection (only if tier == DDD)
        if (tier == Project.DDD) {
            var dddType = detectDdd(decl);
            if (dddType != null) return dddType;
        }

        // Project 2: Spring detection (if tier == SPRING or tier == DDD)
        if (tier == Project.CRUD || tier == Project.DDD) {
            var springType = detectSpring(decl);
            if (springType != null) return springType;
        }

        // Project 1: Generic detection (always)
        return detectGeneric(decl);
    }

    private NodeType detectDdd(ClassOrInterfaceDeclaration decl) {
        // Check extended types in a single pass
        for (var ext : decl.getExtendedTypes()) {
            if (nameMatches(ext, "AggregateRoot")) return NodeType.AGGREGATE;
            if (nameMatches(ext, "EventApplier")) return NodeType.EVENT_APPLIER;
            if (ext.getNameAsString().endsWith("Event")) return NodeType.DOMAIN_EVENT;
        }

        // Check implemented types in a single pass
        for (var impl : decl.getImplementedTypes()) {
            if (nameMatches(impl, "CommandHandler")) return NodeType.COMMAND_HANDLER;
            if (nameMatches(impl, "QueryHandler")) return NodeType.QUERY_HANDLER;
        }

        // interface extends Repository<A,ID>
        if (decl.isInterface()) {
            for (var ext : decl.getExtendedTypes()) {
                if (nameMatches(ext, "Repository")) return NodeType.REPOSITORY;
            }
        }

        // @Component + @EventHandler methods → EVENT_LISTENER or PROJECTION_HANDLER
        if (hasAnnotation(decl, "Component") && hasEventHandlerMethods(decl)) {
            var source = decl.toString();
            if (source.contains("viewRepository.save")) return NodeType.PROJECTION_HANDLER;
            if (source.contains("commandBus.dispatch")) return NodeType.EVENT_LISTENER;
        }

        return null;
    }

    private NodeType detectSpring(ClassOrInterfaceDeclaration decl) {
        if (hasAnnotation(decl, "RestController")) return NodeType.CONTROLLER;
        if (hasAnnotation(decl, "Service")) return NodeType.SERVICE;
        if (hasAnnotation(decl, "Repository")) return NodeType.REPOSITORY;
        if (hasAnnotation(decl, "Mapper")) return NodeType.MAPPER;

        if (hasAnnotation(decl, "Entity")) {
            if (hasTableViewAnnotation(decl)) return NodeType.VIEW;
            return NodeType.ENTITY;
        }

        if (hasAnnotation(decl, "Configuration") || hasAnnotation(decl, "Component")) {
            return NodeType.CONFIGURATION;
        }

        return null;
    }

    private NodeType detectGeneric(ClassOrInterfaceDeclaration decl) {
        var name = decl.getNameAsString();
        if (name.endsWith("Controller")) return NodeType.CONTROLLER;
        if (name.endsWith("Service")) return NodeType.SERVICE;
        if (name.endsWith("Repository")) return NodeType.REPOSITORY;
        if (decl.isInterface()) return NodeType.INTERFACE;
        return NodeType.CLASS;
    }

    private NodeType classifyRecord(RecordDeclaration decl) {
        if (tier == Project.DDD) {
            for (var impl : decl.getImplementedTypes()) {
                if (nameMatches(impl, "Command")) return NodeType.COMMAND;
                if (nameMatches(impl, "Query")) return NodeType.QUERY;
            }
        }
        return NodeType.RECORD;
    }

    private boolean nameMatches(ClassOrInterfaceType type, String name) {
        return type.getNameAsString().equals(name);
    }

    private boolean hasAnnotation(NodeWithAnnotations<?> decl, String name) {
        return decl.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().equals(name));
    }

    private boolean hasTableViewAnnotation(ClassOrInterfaceDeclaration decl) {
        return decl.getAnnotations().stream()
                .filter(a -> a.getNameAsString().equals("Table"))
                .anyMatch(a -> {
                    if (a instanceof SingleMemberAnnotationExpr sma) {
                        return sma.getMemberValue().toString().endsWith("_view\"");
                    }
                    if (a instanceof NormalAnnotationExpr na) {
                        return na.getPairs().stream()
                                .filter(p -> p.getNameAsString().equals("name"))
                                .anyMatch(p -> p.getValue().toString().endsWith("_view\""));
                    }
                    return false;
                });
    }

    private boolean hasEventHandlerMethods(ClassOrInterfaceDeclaration decl) {
        return decl.getMethods().stream()
                .anyMatch(m -> hasAnnotation(m, "EventHandler"));
    }

    private Node buildNode(String qualifiedName, NodeType type, String name,
                           String filePath, BodyDeclaration<?> decl) {
        var lines = decl.toString().lines().toList();
        var snippet = lines.stream()
                .limit(10)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        var lineNumber = decl.getBegin().map(p -> p.line).orElse(0);

        return new Node(qualifiedName, type, name, qualifiedName, filePath, lineNumber, snippet, 0L);
    }
}
