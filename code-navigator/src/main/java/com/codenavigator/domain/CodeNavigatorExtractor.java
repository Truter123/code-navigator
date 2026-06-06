package com.codenavigator.domain;

import com.codenavigator.domain.model.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CodeNavigatorExtractor {

    private static final Map<String, String> NODE_TYPE_TO_ENTITY_TYPE = Map.ofEntries(
        Map.entry("AGGREGATE", "aggregate"),
        Map.entry("ENTITY", "entity"),
        Map.entry("VIEW", "entity"),
        Map.entry("DOMAIN_EVENT", "event"),
        Map.entry("COMMAND", "command"),
        Map.entry("COMMAND_HANDLER", "entity"),
        Map.entry("QUERY", "command"),
        Map.entry("QUERY_HANDLER", "entity"),
        Map.entry("SERVICE", "entity"),
        Map.entry("REPOSITORY", "entity"),
        Map.entry("CONTROLLER", "entity")
    );

    // Matches Java field declarations: optional annotations, modifiers, type, name
    private static final Pattern FIELD_PATTERN = Pattern.compile(
        "^\\s+(?:(?:private|protected|public|final|static|transient|volatile)\\s+)*" +
        "(\\w+(?:<[^>]+>)?)\\s+(\\w+)\\s*(?:=|;)",
        Pattern.MULTILINE
    );

    // Matches record parameters: Type name
    private static final Pattern RECORD_PARAM_PATTERN = Pattern.compile(
        "(\\w+(?:<[^>]+>)?)\\s+(\\w+)(?:\\s*,|\\s*\\))"
    );

    // Matches JavaDoc first sentence
    private static final Pattern JAVADOC_PATTERN = Pattern.compile(
        "/\\*\\*\\s*\\n?\\s*\\*?\\s*(.+?)(?:\\.|\\n\\s*\\*\\s*\\n|\\n\\s*\\*/)",
        Pattern.DOTALL
    );

    public String extract(DomainSqliteStore domainStore, Path codeGraphDb) {
        if (!Files.exists(codeGraphDb)) {
            return "Error: code-navigator database not found at " + codeGraphDb;
        }

        try {
            domainStore.clearAll();

            List<ExtractedNode> nodes;
            List<ExtractedEdge> edges;
            try (var conn = DriverManager.getConnection("jdbc:sqlite:" + codeGraphDb.toAbsolutePath())) {
                nodes = loadNodes(conn);
                edges = loadEdges(conn);
            }

            // Build lookup maps
            var nodeById = new HashMap<String, ExtractedNode>();
            var nodesByName = new HashMap<String, ExtractedNode>();
            for (var node : nodes) {
                nodeById.put(node.id, node);
                nodesByName.put(node.name, node);
            }

            // Group by package → context name
            var byPackage = new LinkedHashMap<String, List<ExtractedNode>>();
            for (var node : nodes) {
                var pkg = extractPackage(node.qualifiedName);
                byPackage.computeIfAbsent(pkg, k -> new ArrayList<>()).add(node);
            }

            // Map node name → context name for lookups
            var nodeToContext = new HashMap<String, String>();
            for (var entry : byPackage.entrySet()) {
                var contextName = humanizePackage(entry.getKey());
                for (var node : entry.getValue()) {
                    nodeToContext.put(node.name, contextName);
                }
            }

            // Save entities with fields parsed from code_snippet
            for (var node : nodes) {
                var entityType = NODE_TYPE_TO_ENTITY_TYPE.getOrDefault(node.type, "entity");
                var fields = parseFields(node);
                var description = buildDescription(node, edges, nodeById);
                var context = nodeToContext.get(node.name);

                domainStore.saveEntity(new DomainEntity(
                    node.name,
                    context,
                    entityType,
                    description,
                    fields,
                    node.qualifiedName
                ));
            }

            // Save bounded contexts with communications
            for (var entry : byPackage.entrySet()) {
                var contextName = humanizePackage(entry.getKey());
                var entityNames = entry.getValue().stream().map(ExtractedNode::name).toList();
                var communications = extractCommunications(entry.getKey(), entry.getValue(), edges, nodeById, nodeToContext);

                domainStore.saveContext(new BoundedContext(
                    contextName,
                    "Auto-extracted from " + entry.getKey(),
                    null,
                    entityNames,
                    communications
                ));
            }

            // Save glossary terms with aliases and context
            for (var node : nodes) {
                if (!"AGGREGATE".equals(node.type) && !"DOMAIN_EVENT".equals(node.type)
                    && !"COMMAND".equals(node.type)) continue;
                var humanName = splitCamelCase(node.name);
                var aliases = buildAliases(node);
                var context = nodeToContext.get(node.name);
                var definition = buildTermDefinition(node, edges, nodeById);

                domainStore.saveTerm(new GlossaryTerm(
                    humanName,
                    definition,
                    aliases,
                    context,
                    List.of(node.name),
                    null
                ));
            }

            // Extract business flows from command → handler → aggregate → event chains
            var flows = extractFlows(edges, nodeById, nodeToContext);
            for (var flow : flows) {
                domainStore.saveFlow(flow);
            }

            // Extract business rules from aggregates (validation patterns in code)
            var rules = extractRules(nodes, edges, nodeById, nodeToContext);
            for (var rule : rules) {
                domainStore.saveRule(rule);
            }

            // Store code-graph modification time
            domainStore.setConfig("code_graph_modified",
                String.valueOf(Files.getLastModifiedTime(codeGraphDb).toMillis()));

            // Build summary
            var sb = new StringBuilder();
            sb.append("## Extracted domain from code-navigator\n\n");
            sb.append("Saved to SQLite domain store:\n\n");
            sb.append("- **entities** — ").append(nodes.size()).append(" entities extracted\n");
            sb.append("- **contexts** — ").append(byPackage.size()).append(" bounded contexts\n");

            long termCount = nodes.stream()
                .filter(n -> "AGGREGATE".equals(n.type) || "DOMAIN_EVENT".equals(n.type) || "COMMAND".equals(n.type))
                .count();
            sb.append("- **glossary** — ").append(termCount).append(" terms\n");
            sb.append("- **flows** — ").append(flows.size()).append(" business flows\n");
            sb.append("- **rules** — ").append(rules.size()).append(" business rules\n\n");

            sb.append("### Extracted entities by type\n\n");
            var byType = new LinkedHashMap<String, List<String>>();
            for (var node : nodes) {
                var entityType = NODE_TYPE_TO_ENTITY_TYPE.getOrDefault(node.type, "entity");
                byType.computeIfAbsent(entityType, k -> new ArrayList<>()).add(node.name);
            }
            for (var entry : byType.entrySet()) {
                sb.append("- **").append(entry.getKey()).append("**: ");
                sb.append(String.join(", ", entry.getValue())).append("\n");
            }
            sb.append("\n");

            sb.append("Review and enrich the domain store with business knowledge.\n");
            return sb.toString();

        } catch (Exception e) {
            return "Error extracting from code-navigator: " + e.getMessage();
        }
    }

    // ---- Field extraction from code snippets ----

    private List<EntityField> parseFields(ExtractedNode node) {
        if (node.codeSnippet == null || node.codeSnippet.isBlank()) return List.of();

        var fields = new ArrayList<EntityField>();

        // For records (commands, events), parse the record parameters
        if (node.codeSnippet.contains("record ")) {
            int parenStart = node.codeSnippet.indexOf('(');
            int parenEnd = node.codeSnippet.indexOf(')');
            if (parenStart >= 0 && parenEnd > parenStart) {
                String params = node.codeSnippet.substring(parenStart, parenEnd + 1);
                Matcher m = RECORD_PARAM_PATTERN.matcher(params);
                while (m.find()) {
                    fields.add(new EntityField(
                        m.group(2),
                        m.group(1),
                        humanizeFieldName(m.group(2)),
                        null
                    ));
                }
            }
            return fields;
        }

        // For classes (aggregates, entities), parse field declarations
        Matcher m = FIELD_PATTERN.matcher(node.codeSnippet);
        while (m.find()) {
            String type = m.group(1);
            String name = m.group(2);
            // Skip common non-domain fields
            if (isInfrastructureField(name, type)) continue;
            fields.add(new EntityField(
                name,
                type,
                humanizeFieldName(name),
                isStatusField(name, type) ? "state field" : null
            ));
        }
        return fields;
    }

    private boolean isInfrastructureField(String name, String type) {
        return name.equals("serialVersionUID") || name.equals("log") || name.equals("logger")
            || type.equals("Logger") || type.equals("Log");
    }

    private boolean isStatusField(String name, String type) {
        String lower = name.toLowerCase();
        return lower.contains("status") || lower.contains("state") || lower.contains("phase")
            || type.toLowerCase().contains("status") || type.toLowerCase().contains("state");
    }

    private String humanizeFieldName(String name) {
        return splitCamelCase(name).toLowerCase();
    }

    // ---- Description building ----

    private String buildDescription(ExtractedNode node, List<ExtractedEdge> edges, Map<String, ExtractedNode> nodeById) {
        var sb = new StringBuilder();

        // Try to extract JavaDoc
        if (node.codeSnippet != null) {
            var javadoc = extractJavadoc(node.codeSnippet);
            if (javadoc != null) {
                sb.append(javadoc);
            } else {
                sb.append(splitCamelCase(node.name));
            }
        } else {
            sb.append(splitCamelCase(node.name));
        }

        // Add relationship context
        var related = new ArrayList<String>();
        for (var edge : edges) {
            if (edge.sourceId.equals(node.id)) {
                var target = nodeById.get(edge.targetId);
                if (target != null) {
                    switch (edge.type) {
                        case "EMITS_EVENT" -> related.add("emits " + splitCamelCase(target.name));
                        case "HANDLES" -> related.add("handles " + splitCamelCase(target.name));
                        case "LOADS_AGGREGATE" -> related.add("loads " + target.name);
                        case "LISTENS_TO" -> related.add("listens to " + splitCamelCase(target.name));
                    }
                }
            }
        }
        if (!related.isEmpty()) {
            sb.append(" — ").append(String.join(", ", related));
        }

        return sb.toString();
    }

    private String extractJavadoc(String code) {
        Matcher m = JAVADOC_PATTERN.matcher(code);
        if (m.find()) {
            return m.group(1).replaceAll("\\s*\\*\\s*", " ").strip();
        }
        return null;
    }

    // ---- Term definitions ----

    private String buildTermDefinition(ExtractedNode node, List<ExtractedEdge> edges, Map<String, ExtractedNode> nodeById) {
        var sb = new StringBuilder();

        if (node.codeSnippet != null) {
            var javadoc = extractJavadoc(node.codeSnippet);
            if (javadoc != null) {
                return javadoc;
            }
        }

        switch (node.type) {
            case "AGGREGATE" -> {
                sb.append(splitCamelCase(node.name)).append(" — core domain aggregate");
                var events = new ArrayList<String>();
                for (var edge : edges) {
                    if (edge.sourceId.equals(node.id) && "EMITS_EVENT".equals(edge.type)) {
                        var target = nodeById.get(edge.targetId);
                        if (target != null) events.add(splitCamelCase(target.name));
                    }
                }
                if (!events.isEmpty()) {
                    sb.append(". Produces events: ").append(String.join(", ", events));
                }
            }
            case "DOMAIN_EVENT" -> {
                sb.append(splitCamelCase(node.name)).append(" — domain event");
                var fields = parseFields(node);
                if (!fields.isEmpty()) {
                    var fieldNames = fields.stream().map(EntityField::name).toList();
                    sb.append(" carrying: ").append(String.join(", ", fieldNames));
                }
            }
            case "COMMAND" -> {
                sb.append(splitCamelCase(node.name)).append(" — command");
                var fields = parseFields(node);
                if (!fields.isEmpty()) {
                    var fieldNames = fields.stream().map(EntityField::name).toList();
                    sb.append(" with parameters: ").append(String.join(", ", fieldNames));
                }
            }
            default -> sb.append(splitCamelCase(node.name));
        }

        return sb.toString();
    }

    // ---- Alias building ----

    private List<String> buildAliases(ExtractedNode node) {
        var aliases = new ArrayList<String>();
        var camel = splitCamelCase(node.name);

        // Add the raw class name as alias
        aliases.add(node.name);

        // For events: strip "Event" suffix for alias
        if (node.name.endsWith("Event")) {
            var withoutEvent = node.name.substring(0, node.name.length() - 5);
            aliases.add(splitCamelCase(withoutEvent));
        }
        // For commands: strip "Command" suffix
        if (node.name.endsWith("Command")) {
            var withoutCommand = node.name.substring(0, node.name.length() - 7);
            aliases.add(splitCamelCase(withoutCommand));
        }

        return aliases;
    }

    // ---- Flow extraction from command chains ----

    private List<BusinessFlow> extractFlows(List<ExtractedEdge> edges, Map<String, ExtractedNode> nodeById, Map<String, String> nodeToContext) {
        var flows = new ArrayList<BusinessFlow>();

        // Find command → handler → aggregate → event chains
        // Group by: handler HANDLES command, handler LOADS_AGGREGATE aggregate
        var handlerToCommand = new LinkedHashMap<String, List<String>>();
        var handlerToAggregate = new LinkedHashMap<String, List<String>>();
        var aggregateToEvents = new LinkedHashMap<String, List<String>>();

        for (var edge : edges) {
            var source = nodeById.get(edge.sourceId);
            var target = nodeById.get(edge.targetId);
            if (source == null || target == null) continue;

            switch (edge.type) {
                case "HANDLES" -> {
                    if ("COMMAND_HANDLER".equals(source.type) && "COMMAND".equals(target.type)) {
                        handlerToCommand.computeIfAbsent(source.name, k -> new ArrayList<>()).add(target.name);
                    }
                }
                case "LOADS_AGGREGATE" -> {
                    if ("COMMAND_HANDLER".equals(source.type)) {
                        handlerToAggregate.computeIfAbsent(source.name, k -> new ArrayList<>()).add(target.name);
                    }
                }
                case "EMITS_EVENT" -> {
                    if ("AGGREGATE".equals(source.type)) {
                        aggregateToEvents.computeIfAbsent(source.name, k -> new ArrayList<>()).add(target.name);
                    }
                }
            }
        }

        // For each command handler, build a flow
        for (var entry : handlerToCommand.entrySet()) {
            var handlerName = entry.getKey();
            for (var commandName : entry.getValue()) {
                var commandNode = nodeById.values().stream()
                    .filter(n -> n.name.equals(commandName)).findFirst().orElse(null);
                if (commandNode == null) continue;

                var steps = new ArrayList<FlowStep>();
                var flowName = splitCamelCase(commandName.replace("Command", ""));
                var context = nodeToContext.get(handlerName);

                // Step 1: Command received
                var commandFields = parseFields(commandNode);
                var paramDesc = commandFields.isEmpty() ? ""
                    : " with " + commandFields.stream().map(EntityField::name).reduce((a, b) -> a + ", " + b).orElse("");
                steps.add(new FlowStep(
                    "Receive " + splitCamelCase(commandName) + paramDesc,
                    "System",
                    null
                ));

                // Step 2: Handler processes
                var aggregates = handlerToAggregate.getOrDefault(handlerName, List.of());
                for (var aggName : new LinkedHashSet<>(aggregates)) {
                    steps.add(new FlowStep(
                        "Load and modify " + splitCamelCase(aggName),
                        handlerName,
                        null
                    ));

                    // Step 3: Events emitted
                    var events = aggregateToEvents.getOrDefault(aggName, List.of());
                    for (var eventName : events) {
                        // Only include events related to this command's domain
                        if (isRelatedEvent(commandName, eventName, aggName)) {
                            steps.add(new FlowStep(
                                "Emit " + splitCamelCase(eventName),
                                aggName,
                                null
                            ));
                        }
                    }
                }

                if (steps.size() > 1) {
                    var trigger = splitCamelCase(commandName) + " received";
                    var outcome = aggregates.isEmpty() ? "Command processed"
                        : splitCamelCase(aggregates.get(0)) + " updated";

                    flows.add(new BusinessFlow(
                        flowName,
                        "Business flow for " + flowName.toLowerCase(),
                        context,
                        trigger,
                        steps,
                        outcome
                    ));
                }
            }
        }

        return flows;
    }

    private boolean isRelatedEvent(String commandName, String eventName, String aggregateName) {
        // Match events that share the aggregate name prefix
        String aggLower = aggregateName.toLowerCase();
        String eventLower = eventName.toLowerCase();
        String cmdLower = commandName.toLowerCase().replace("command", "");
        return eventLower.contains(aggLower) || eventLower.contains(cmdLower.replace("create", "created")
            .replace("update", "updated").replace("cancel", "cancelled").replace("delete", "deleted")
            .replace("receive", "received").replace("validate", "validated").replace("send", "sent")
            .replace("fulfill", "fulfilled"));
    }

    // ---- Rule extraction ----

    private List<BusinessRule> extractRules(List<ExtractedNode> nodes, List<ExtractedEdge> edges,
                                            Map<String, ExtractedNode> nodeById, Map<String, String> nodeToContext) {
        var rules = new ArrayList<BusinessRule>();

        // For each aggregate, create rules about its state lifecycle
        for (var node : nodes) {
            if (!"AGGREGATE".equals(node.type)) continue;
            var context = nodeToContext.get(node.name);

            // Find all events this aggregate emits → state transitions
            var events = new ArrayList<String>();
            for (var edge : edges) {
                if (edge.sourceId.equals(node.id) && "EMITS_EVENT".equals(edge.type)) {
                    var target = nodeById.get(edge.targetId);
                    if (target != null) events.add(target.name);
                }
            }

            if (!events.isEmpty()) {
                var eventNames = events.stream().map(this::splitCamelCase).toList();
                rules.add(new BusinessRule(
                    node.name + " Lifecycle",
                    splitCamelCase(node.name) + " lifecycle transitions",
                    context,
                    node.name,
                    Severity.INFO,
                    "Valid events: " + String.join(", ", eventNames)
                ));
            }

            // Check for status fields → state machine rules
            var fields = parseFields(node);
            for (var field : fields) {
                if (isStatusField(field.name(), field.type())) {
                    rules.add(new BusinessRule(
                        node.name + " " + capitalize(field.name()) + " Rule",
                        splitCamelCase(node.name) + " has state tracked by " + field.name() + " (" + field.type() + ")",
                        context,
                        node.name,
                        Severity.WARNING,
                        field.name() + " must follow valid state transitions"
                    ));
                }
            }

            // Find commands that load this aggregate → invariant about who can modify it
            var commandHandlers = new ArrayList<String>();
            for (var edge : edges) {
                if ("LOADS_AGGREGATE".equals(edge.type)) {
                    var target = nodeById.get(edge.targetId);
                    var source = nodeById.get(edge.sourceId);
                    if (target != null && source != null && target.name.equals(node.name)
                        && "COMMAND_HANDLER".equals(source.type)) {
                        commandHandlers.add(source.name);
                    }
                }
            }
            if (!commandHandlers.isEmpty()) {
                rules.add(new BusinessRule(
                    node.name + " Modification Rule",
                    splitCamelCase(node.name) + " can only be modified through designated command handlers",
                    context,
                    node.name,
                    Severity.ERROR,
                    "Authorized handlers: " + String.join(", ", commandHandlers)
                ));
            }
        }

        return rules;
    }

    // ---- Context communication extraction ----

    private List<ContextCommunication> extractCommunications(String currentPkg, List<ExtractedNode> contextNodes,
                                                              List<ExtractedEdge> edges, Map<String, ExtractedNode> nodeById,
                                                              Map<String, String> nodeToContext) {
        var currentNodeIds = new HashSet<String>();
        for (var node : contextNodes) {
            currentNodeIds.add(node.id);
        }
        var currentContextName = humanizePackage(currentPkg);

        // Find edges that cross context boundaries
        var communicationsMap = new LinkedHashMap<String, ContextCommunication>();
        for (var edge : edges) {
            if (!currentNodeIds.contains(edge.sourceId)) continue;
            var target = nodeById.get(edge.targetId);
            if (target == null) continue;
            var targetContext = nodeToContext.get(target.name);
            if (targetContext == null || targetContext.equals(currentContextName)) continue;

            String commType = switch (edge.type) {
                case "DISPATCHES_COMMAND" -> "command";
                case "LISTENS_TO" -> "event";
                case "CALLS_API" -> "api";
                case "LOADS_AGGREGATE" -> "aggregate-access";
                case "USES_SERVICE" -> "service";
                default -> null;
            };
            if (commType == null) continue;

            var key = targetContext + ":" + commType;
            if (!communicationsMap.containsKey(key)) {
                var source = nodeById.get(edge.sourceId);
                var via = (source != null ? source.name : "unknown") + " → " + target.name;
                communicationsMap.put(key, new ContextCommunication(targetContext, commType, via));
            }
        }

        return new ArrayList<>(communicationsMap.values());
    }

    // ---- Loading from code-graph DB ----

    private List<ExtractedNode> loadNodes(Connection conn) throws SQLException {
        var nodes = new ArrayList<ExtractedNode>();
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT id, type, name, qualified_name, file_path, code_snippet FROM nodes")) {
            while (rs.next()) {
                var type = rs.getString("type");
                if (NODE_TYPE_TO_ENTITY_TYPE.containsKey(type)) {
                    nodes.add(new ExtractedNode(
                        rs.getString("id"),
                        type,
                        rs.getString("name"),
                        rs.getString("qualified_name"),
                        rs.getString("file_path"),
                        rs.getString("code_snippet")
                    ));
                }
            }
        }
        return nodes;
    }

    private List<ExtractedEdge> loadEdges(Connection conn) throws SQLException {
        var edges = new ArrayList<ExtractedEdge>();
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT type, source_id, target_id FROM edges")) {
            while (rs.next()) {
                edges.add(new ExtractedEdge(
                    rs.getString("type"),
                    rs.getString("source_id"),
                    rs.getString("target_id")
                ));
            }
        }
        return edges;
    }

    // ---- String utilities ----

    private String extractPackage(String qualifiedName) {
        if (qualifiedName == null) return "default";
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot > 0 ? qualifiedName.substring(0, lastDot) : "default";
    }

    private String humanizePackage(String pkg) {
        if (pkg == null || pkg.equals("default")) return "Default";
        int lastDot = pkg.lastIndexOf('.');
        var last = lastDot >= 0 ? pkg.substring(lastDot + 1) : pkg;
        return last.substring(0, 1).toUpperCase() + last.substring(1);
    }

    private String splitCamelCase(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1 $2");
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    // ---- Records ----

    private record ExtractedNode(String id, String type, String name, String qualifiedName, String filePath, String codeSnippet) {}
    private record ExtractedEdge(String type, String sourceId, String targetId) {}
}
