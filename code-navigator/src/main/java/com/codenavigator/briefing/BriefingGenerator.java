package com.codenavigator.briefing;

import com.codenavigator.domain.DomainSqliteStore;
import com.codenavigator.domain.model.*;
import com.codenavigator.graph.*;
import com.codenavigator.graph.GraphStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class BriefingGenerator {

    private final GraphStore store;
    private final DomainSqliteStore domainStore;

    public BriefingGenerator(GraphStore store, DomainSqliteStore domainStore) {
        this.store = store;
        this.domainStore = domainStore;
    }

    public void generate(Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        writeFile(outputDir, "overview.md", generateOverview());
        writeFile(outputDir, "endpoints.md", generateEndpoints());
        writeFile(outputDir, "models.md", generateModels());
        writeFile(outputDir, "services.md", generateServices());
        writeFile(outputDir, "components.md", generateComponents());
        writeFile(outputDir, "projections.md", generateProjections());
        if (domainStore != null) {
            writeFile(outputDir, "domain.md", generateDomain());
            writeFile(outputDir, "flows.md", generateFlows());
            writeFile(outputDir, "rules.md", generateRules());
        }
    }

    private String generateOverview() {
        var allNodes = store.getAllNodes();
        String tier = store.getConfig("tier");
        int edgeCount = store.getEdgeCount();
        int fileCount = store.getFileCount();

        var byPackage = allNodes.stream().collect(Collectors.groupingBy(
            n -> extractModulePackage(n.qualifiedName()), LinkedHashMap::new, Collectors.toList()));

        var sb = new StringBuilder();
        sb.append("# Project Briefing (generated ").append(LocalDate.now()).append(")\n");
        sb.append("Tier: ").append(tier != null ? tier : "unknown");
        sb.append(" | ").append(allNodes.size()).append(" nodes");
        sb.append(" | ").append(edgeCount).append(" edges");
        sb.append(" | ").append(fileCount).append(" files\n\n");

        sb.append("## Modules\n");
        for (var entry : byPackage.entrySet()) {
            var counts = entry.getValue().stream()
                .collect(Collectors.groupingBy(Node::type, Collectors.counting()));
            String summary = counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getValue() + " " + e.getKey().name().toLowerCase())
                .collect(Collectors.joining(", "));
            sb.append("- ").append(entry.getKey()).append(": ").append(summary).append("\n");
        }
        return sb.toString();
    }

    private String generateEndpoints() {
        var controllers = store.findNodesByType(NodeType.CONTROLLER);
        if (controllers.isEmpty()) return null;

        var sb = new StringBuilder();
        sb.append("# Endpoints\n\n");

        for (var ctrl : controllers) {
            String basePath = extractBasePath(ctrl.codeSnippet());
            sb.append("## ").append(ctrl.name()).append(" (").append(basePath).append(")\n");

            var methods = store.findMethodsByNodeId(ctrl.id());
            var httpMethods = methods.stream()
                .filter(m -> m.annotations() != null && !m.annotations().isEmpty())
                .toList();

            if (!httpMethods.isEmpty()) {
                for (var method : httpMethods) {
                    String returnType = simplifyReturnType(method.returnType());
                    String[] parts = method.annotations().split(" ", 2);
                    String httpMethod = parts[0];
                    String path = parts.length > 1 ? parts[1] : basePath;
                    sb.append(String.format("  %-7s %-30s -> %s%n", httpMethod, path, returnType));
                }
            } else {
                // Fallback: show dispatched commands/queries (DDD mode)
                var dispatched = store.findEdgesFrom(ctrl.id()).stream()
                    .filter(e -> e.type() == EdgeType.DISPATCHES_COMMAND || e.type() == EdgeType.DISPATCHES_QUERY)
                    .toList();
                for (var edge : dispatched) {
                    var target = store.findNodeById(edge.targetId());
                    String targetName = target.map(Node::name).orElse(edge.targetId());
                    sb.append("  ").append(basePath).append(" -> ").append(targetName).append("\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String simplifyReturnType(String returnType) {
        if (returnType == null) return "void";
        if (returnType.startsWith("ResponseEntity<") && returnType.endsWith(">")) {
            return returnType.substring("ResponseEntity<".length(), returnType.length() - 1);
        }
        return returnType;
    }

    private String generateModels() {
        var records = new ArrayList<>(store.findNodesByType(NodeType.RECORD));
        records.addAll(store.findNodesByType(NodeType.COMMAND));
        records.addAll(store.findNodesByType(NodeType.QUERY));
        if (records.isEmpty()) return null;

        var recordIds = records.stream().map(Node::id).toList();
        var allFields = store.findMethodsByNodeIds(recordIds);
        var fieldsByNode = allFields.stream()
            .filter(m -> "field".equals(m.visibility()))
            .collect(Collectors.groupingBy(GraphStore.MethodRecord::nodeId));

        if (fieldsByNode.isEmpty()) return null;

        var byPackage = records.stream()
            .filter(r -> fieldsByNode.containsKey(r.id()))
            .collect(Collectors.groupingBy(
                r -> extractPackage(r.qualifiedName()), LinkedHashMap::new, Collectors.toList()));

        var sb = new StringBuilder();
        sb.append("# Models\n\n");

        for (var entry : byPackage.entrySet()) {
            sb.append("## ").append(entry.getKey()).append("\n");
            for (var record : entry.getValue()) {
                var fields = fieldsByNode.get(record.id());
                if (fields == null || fields.isEmpty()) continue;
                String fieldStr = fields.stream()
                    .map(f -> f.name() + ": " + f.returnType())
                    .collect(Collectors.joining(", "));
                sb.append("- **").append(record.name()).append("**: ").append(fieldStr).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String generateServices() {
        var services = store.findNodesByType(NodeType.SERVICE);
        if (services.isEmpty()) return null;

        var serviceIds = services.stream().map(Node::id).toList();
        var allMethods = store.findMethodsByNodeIds(serviceIds);
        var methodsByNode = allMethods.stream()
            .filter(m -> "public".equals(m.visibility()))
            .collect(Collectors.groupingBy(GraphStore.MethodRecord::nodeId));

        if (methodsByNode.isEmpty()) return null;

        var sb = new StringBuilder();
        sb.append("# Services\n\n");

        for (var svc : services) {
            var methods = methodsByNode.get(svc.id());
            if (methods == null || methods.isEmpty()) continue;
            sb.append("## ").append(svc.name()).append("\n");
            for (var method : methods) {
                sb.append("  ").append(method.name()).append("(");
                if (method.parameters() != null && !method.parameters().isEmpty()) {
                    sb.append(method.parameters());
                }
                sb.append(") -> ").append(method.returnType() != null ? method.returnType() : "void").append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String generateComponents() {
        var components = store.findNodesByType(NodeType.FE_COMPONENT);
        if (components.isEmpty()) return null;

        var sb = new StringBuilder();
        sb.append("# Components\n\n");

        for (var comp : components) {
            var serviceEdges = store.findEdgesFrom(comp.id()).stream()
                .filter(e -> e.type() == EdgeType.USES_SERVICE)
                .toList();
            if (serviceEdges.isEmpty()) continue;

            sb.append("## ").append(comp.name()).append("\n");
            for (var edge : serviceEdges) {
                var feService = store.findNodeById(edge.targetId());
                String serviceName = feService.map(Node::name).orElse(edge.targetId());

                String controllerName = feService.flatMap(s ->
                    store.findEdgesFrom(s.id()).stream()
                        .filter(e -> e.type() == EdgeType.CALLS_API)
                        .findFirst()
                        .flatMap(e -> store.findNodeById(e.targetId()))
                        .map(Node::name)
                ).orElse(null);

                sb.append("  uses: ").append(serviceName);
                if (controllerName != null) sb.append(" -> ").append(controllerName);
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String generateProjections() {
        var projections = store.findNodesByType(NodeType.PROJECTION_HANDLER);
        if (projections.isEmpty()) return null;

        var sb = new StringBuilder();
        sb.append("# Projections\n\n");

        for (var proj : projections) {
            sb.append("## ").append(proj.name()).append("\n");

            var events = store.findEdgesFrom(proj.id()).stream()
                .filter(e -> e.type() == EdgeType.PROJECTS_EVENT)
                .map(e -> store.findNodeById(e.targetId()).map(Node::name).orElse(e.targetId()))
                .toList();
            var eventsIncoming = store.findEdgesTo(proj.id()).stream()
                .filter(e -> e.type() == EdgeType.PROJECTS_EVENT)
                .map(e -> store.findNodeById(e.sourceId()).map(Node::name).orElse(e.sourceId()))
                .toList();
            var allEvents = new ArrayList<>(events);
            allEvents.addAll(eventsIncoming);

            if (!allEvents.isEmpty()) {
                sb.append("  listens: ").append(String.join(", ", allEvents)).append("\n");
            }

            var views = store.findEdgesFrom(proj.id()).stream()
                .filter(e -> e.type() == EdgeType.UPDATES_VIEW)
                .map(e -> store.findNodeById(e.targetId()).map(Node::name).orElse(e.targetId()))
                .toList();
            if (!views.isEmpty()) {
                sb.append("  updates: ").append(String.join(", ", views)).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String generateDomain() {
        var sb = new StringBuilder();
        sb.append("# Domain Model\n\n");

        var contexts = domainStore.getAllContexts();
        if (!contexts.isEmpty()) {
            sb.append("## Bounded Contexts\n\n");
            for (var ctx : contexts) {
                sb.append("### ").append(ctx.name()).append("\n");
                if (ctx.description() != null) sb.append(ctx.description()).append("\n");
                if (!ctx.entities().isEmpty()) sb.append("Entities: ").append(String.join(", ", ctx.entities())).append("\n");
                for (var comm : ctx.communicatesWith()) {
                    sb.append("  -> ").append(comm.context()).append(" (").append(comm.type()).append(", ").append(comm.via()).append(")\n");
                }
                sb.append("\n");
            }
        }

        var entities = domainStore.getAllEntities();
        if (!entities.isEmpty()) {
            var byContext = entities.stream().collect(Collectors.groupingBy(
                e -> e.context() != null ? e.context() : "unknown", LinkedHashMap::new, Collectors.toList()));
            sb.append("## Entities\n\n");
            for (var entry : byContext.entrySet()) {
                var byType = entry.getValue().stream().collect(Collectors.groupingBy(
                    e -> e.type() != null ? e.type() : "other"));
                for (var typeEntry : byType.entrySet()) {
                    String names = typeEntry.getValue().stream().map(DomainEntity::name).collect(Collectors.joining(", "));
                    sb.append("- ").append(entry.getKey()).append(" ").append(typeEntry.getKey()).append("s: ").append(names).append("\n");
                }
            }
            sb.append("\n");
        }

        var terms = domainStore.getAllTerms();
        if (!terms.isEmpty()) {
            sb.append("## Glossary\n");
            for (var term : terms) {
                sb.append("- **").append(term.name()).append("**: ").append(term.definition() != null ? term.definition() : "").append("\n");
            }
        }

        return sb.toString();
    }

    private String generateFlows() {
        var flows = domainStore.getAllFlows();
        if (flows.isEmpty()) return null;

        var sb = new StringBuilder();
        sb.append("# Business Flows\n\n");

        for (var flow : flows) {
            sb.append("## ").append(flow.name()).append("\n");
            if (flow.trigger() != null) sb.append("Trigger: ").append(flow.trigger()).append("\n");
            int i = 1;
            for (var step : flow.steps()) {
                sb.append(i++).append(". ");
                if (step.actor() != null) sb.append("[").append(step.actor()).append("] ");
                sb.append(step.action()).append("\n");
                if (step.onFailure() != null) sb.append("   Failure: ").append(step.onFailure()).append("\n");
            }
            if (flow.outcome() != null) sb.append("Outcome: ").append(flow.outcome()).append("\n");
            sb.append("\n");
        }
        return sb.toString();
    }

    private String generateRules() {
        var rules = domainStore.getAllRules();
        if (rules.isEmpty()) return null;

        var sb = new StringBuilder();
        sb.append("# Business Rules\n\n");

        var bySeverity = rules.stream().collect(Collectors.groupingBy(
            r -> r.severity().name(), LinkedHashMap::new, Collectors.toList()));

        for (var entry : bySeverity.entrySet()) {
            sb.append("## ").append(entry.getKey()).append("\n");
            for (var rule : entry.getValue()) {
                sb.append("- **").append(rule.name()).append("**: ");
                if (rule.description() != null) sb.append(rule.description());
                if (rule.entity() != null) sb.append(" (").append(rule.entity()).append(")");
                if (rule.invariant() != null) sb.append(" `").append(rule.invariant()).append("`");
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private void writeFile(Path dir, String filename, String content) throws IOException {
        if (content != null && !content.isEmpty()) {
            Files.writeString(dir.resolve(filename), content);
        }
    }

    private static final Pattern BASE_PATH_PATTERN = Pattern.compile(
        "@RequestMapping\\([^)]*\"([^\"]+)\"");

    private static String extractBasePath(String codeSnippet) {
        if (codeSnippet == null) return "/";
        Matcher m = BASE_PATH_PATTERN.matcher(codeSnippet);
        return m.find() ? m.group(1) : "/";
    }

    private static String extractPackage(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot > 0 ? qualifiedName.substring(0, lastDot) : qualifiedName;
    }

    private static String extractModulePackage(String qualifiedName) {
        String[] parts = qualifiedName.split("\\.");
        int depth = Math.min(parts.length - 1, 3);
        return String.join(".", Arrays.copyOf(parts, depth));
    }
}
