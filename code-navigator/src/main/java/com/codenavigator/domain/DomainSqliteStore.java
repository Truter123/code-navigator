package com.codenavigator.domain;

import com.codenavigator.domain.model.*;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class DomainSqliteStore implements AutoCloseable {

    private final Connection connection;

    public record SearchResult(String type, String name, String description, String context) {}

    public DomainSqliteStore(Path dbPath) {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA foreign_keys=ON");
            }
            initSchema();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open database: " + dbPath, e);
        }
    }

    private void initSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS config (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS contexts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE,
                    description TEXT,
                    owner TEXT
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS context_entities (
                    context_id INTEGER REFERENCES contexts(id) ON DELETE CASCADE,
                    entity_name TEXT NOT NULL,
                    PRIMARY KEY (context_id, entity_name)
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS context_communications (
                    context_id INTEGER REFERENCES contexts(id) ON DELETE CASCADE,
                    target_context TEXT NOT NULL,
                    type TEXT,
                    via TEXT
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS terms (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    definition TEXT,
                    context TEXT,
                    business_rule TEXT
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS term_aliases (
                    term_id INTEGER REFERENCES terms(id) ON DELETE CASCADE,
                    alias TEXT NOT NULL
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS term_related_entities (
                    term_id INTEGER REFERENCES terms(id) ON DELETE CASCADE,
                    entity_name TEXT NOT NULL
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS flows (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    description TEXT,
                    context TEXT,
                    trigger_text TEXT,
                    outcome TEXT
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS flow_steps (
                    flow_id INTEGER REFERENCES flows(id) ON DELETE CASCADE,
                    step_order INTEGER NOT NULL,
                    action TEXT NOT NULL,
                    actor TEXT,
                    on_failure TEXT
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS rules (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    description TEXT,
                    context TEXT,
                    entity TEXT,
                    severity TEXT NOT NULL,
                    invariant TEXT
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS entities (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    context TEXT,
                    type TEXT,
                    description TEXT,
                    code_mapping TEXT
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS entity_fields (
                    entity_id INTEGER REFERENCES entities(id) ON DELETE CASCADE,
                    name TEXT NOT NULL,
                    type TEXT,
                    description TEXT,
                    transitions TEXT
                )""");

            stmt.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS domain_fts USING fts5(
                    item_type,
                    name,
                    description,
                    context
                )""");
        }
    }

    // ---- Config ----

    public void setConfig(String key, String value) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO config (key, value) VALUES (?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set config: " + key, e);
        }
    }

    public String getConfig(String key) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT value FROM config WHERE key = ?")) {
            ps.setString(1, key);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("value");
            }
            return null;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get config: " + key, e);
        }
    }

    public long getConfigLong(String key) {
        String value = getConfig(key);
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---- Contexts ----

    public void saveContext(BoundedContext ctx) {
        try {
            // Delete existing context with same name (cascade deletes children)
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM contexts WHERE name = ?")) {
                ps.setString(1, ctx.name());
                ps.executeUpdate();
            }

            long contextId;
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO contexts (name, description, owner) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, ctx.name());
                ps.setString(2, ctx.description());
                ps.setString(3, ctx.owner());
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                keys.next();
                contextId = keys.getLong(1);
            }

            if (ctx.entities() != null) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO context_entities (context_id, entity_name) VALUES (?, ?)")) {
                    for (String entity : ctx.entities()) {
                        ps.setLong(1, contextId);
                        ps.setString(2, entity);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }

            if (ctx.communicatesWith() != null) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO context_communications (context_id, target_context, type, via) VALUES (?, ?, ?, ?)")) {
                    for (ContextCommunication comm : ctx.communicatesWith()) {
                        ps.setLong(1, contextId);
                        ps.setString(2, comm.context());
                        ps.setString(3, comm.type());
                        ps.setString(4, comm.via());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }

            // Clean old FTS entry and insert new one
            deleteFts(ctx.name(), "context");
            insertFts("context", ctx.name(), ctx.description(), null);

        } catch (SQLException e) {
            throw new RuntimeException("Failed to save context: " + ctx.name(), e);
        }
    }

    public List<BoundedContext> getAllContexts() {
        try {
            List<BoundedContext> contexts = new ArrayList<>();
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT id, name, description, owner FROM contexts ORDER BY id")) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    String name = rs.getString("name");
                    String description = rs.getString("description");
                    String owner = rs.getString("owner");

                    List<String> entities = loadContextEntities(id);
                    List<ContextCommunication> comms = loadContextCommunications(id);

                    contexts.add(new BoundedContext(name, description, owner, entities, comms));
                }
            }
            return contexts;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load contexts", e);
        }
    }

    private List<String> loadContextEntities(long contextId) throws SQLException {
        List<String> entities = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT entity_name FROM context_entities WHERE context_id = ?")) {
            ps.setLong(1, contextId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                entities.add(rs.getString("entity_name"));
            }
        }
        return entities;
    }

    private List<ContextCommunication> loadContextCommunications(long contextId) throws SQLException {
        List<ContextCommunication> comms = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT target_context, type, via FROM context_communications WHERE context_id = ?")) {
            ps.setLong(1, contextId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                comms.add(new ContextCommunication(
                    rs.getString("target_context"),
                    rs.getString("type"),
                    rs.getString("via")
                ));
            }
        }
        return comms;
    }

    // ---- Terms ----

    public void saveTerm(GlossaryTerm term) {
        try {
            // Delete existing term with same name and context (cascade deletes children)
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM terms WHERE name = ? AND context = ?")) {
                ps.setString(1, term.name());
                ps.setString(2, term.context());
                ps.executeUpdate();
            }
            deleteFts(term.name(), "term");

            long termId;
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO terms (name, definition, context, business_rule) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, term.name());
                ps.setString(2, term.definition());
                ps.setString(3, term.context());
                ps.setString(4, term.businessRule());
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                keys.next();
                termId = keys.getLong(1);
            }

            if (term.aliases() != null) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO term_aliases (term_id, alias) VALUES (?, ?)")) {
                    for (String alias : term.aliases()) {
                        ps.setLong(1, termId);
                        ps.setString(2, alias);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }

            if (term.relatedEntities() != null) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO term_related_entities (term_id, entity_name) VALUES (?, ?)")) {
                    for (String entity : term.relatedEntities()) {
                        ps.setLong(1, termId);
                        ps.setString(2, entity);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }

            insertFts("term", term.name(), term.definition(), term.context());

        } catch (SQLException e) {
            throw new RuntimeException("Failed to save term: " + term.name(), e);
        }
    }

    public List<GlossaryTerm> getAllTerms() {
        try {
            List<GlossaryTerm> terms = new ArrayList<>();
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT id, name, definition, context, business_rule FROM terms ORDER BY id")) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    List<String> aliases = loadTermAliases(id);
                    List<String> relatedEntities = loadTermRelatedEntities(id);

                    terms.add(new GlossaryTerm(
                        rs.getString("name"),
                        rs.getString("definition"),
                        aliases,
                        rs.getString("context"),
                        relatedEntities,
                        rs.getString("business_rule")
                    ));
                }
            }
            return terms;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load terms", e);
        }
    }

    private List<String> loadTermAliases(long termId) throws SQLException {
        List<String> aliases = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT alias FROM term_aliases WHERE term_id = ?")) {
            ps.setLong(1, termId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                aliases.add(rs.getString("alias"));
            }
        }
        return aliases;
    }

    private List<String> loadTermRelatedEntities(long termId) throws SQLException {
        List<String> entities = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT entity_name FROM term_related_entities WHERE term_id = ?")) {
            ps.setLong(1, termId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                entities.add(rs.getString("entity_name"));
            }
        }
        return entities;
    }

    // ---- Flows ----

    public void saveFlow(BusinessFlow flow) {
        try {
            // Delete existing flow with same name and context (cascade deletes children)
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM flows WHERE name = ? AND context = ?")) {
                ps.setString(1, flow.name());
                ps.setString(2, flow.context());
                ps.executeUpdate();
            }
            deleteFts(flow.name(), "flow");

            long flowId;
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO flows (name, description, context, trigger_text, outcome) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, flow.name());
                ps.setString(2, flow.description());
                ps.setString(3, flow.context());
                ps.setString(4, flow.trigger());
                ps.setString(5, flow.outcome());
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                keys.next();
                flowId = keys.getLong(1);
            }

            if (flow.steps() != null) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO flow_steps (flow_id, step_order, action, actor, on_failure) VALUES (?, ?, ?, ?, ?)")) {
                    int order = 0;
                    for (FlowStep step : flow.steps()) {
                        ps.setLong(1, flowId);
                        ps.setInt(2, order++);
                        ps.setString(3, step.action());
                        ps.setString(4, step.actor());
                        ps.setString(5, step.onFailure());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }

            insertFts("flow", flow.name(), flow.description(), flow.context());

        } catch (SQLException e) {
            throw new RuntimeException("Failed to save flow: " + flow.name(), e);
        }
    }

    public List<BusinessFlow> getAllFlows() {
        try {
            List<BusinessFlow> flows = new ArrayList<>();
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT id, name, description, context, trigger_text, outcome FROM flows ORDER BY id")) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    List<FlowStep> steps = loadFlowSteps(id);

                    flows.add(new BusinessFlow(
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("context"),
                        rs.getString("trigger_text"),
                        steps,
                        rs.getString("outcome")
                    ));
                }
            }
            return flows;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load flows", e);
        }
    }

    private List<FlowStep> loadFlowSteps(long flowId) throws SQLException {
        List<FlowStep> steps = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT action, actor, on_failure FROM flow_steps WHERE flow_id = ? ORDER BY step_order")) {
            ps.setLong(1, flowId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                steps.add(new FlowStep(
                    rs.getString("action"),
                    rs.getString("actor"),
                    rs.getString("on_failure")
                ));
            }
        }
        return steps;
    }

    // ---- Rules ----

    public void saveRule(BusinessRule rule) {
        try {
            // Delete existing rule with same name and context
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM rules WHERE name = ? AND context = ?")) {
                ps.setString(1, rule.name());
                ps.setString(2, rule.context());
                ps.executeUpdate();
            }
            deleteFts(rule.name(), "rule");

            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO rules (name, description, context, entity, severity, invariant) VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, rule.name());
                ps.setString(2, rule.description());
                ps.setString(3, rule.context());
                ps.setString(4, rule.entity());
                ps.setString(5, rule.severity().name());
                ps.setString(6, rule.invariant());
                ps.executeUpdate();
            }

            insertFts("rule", rule.name(), rule.description(), rule.context());

        } catch (SQLException e) {
            throw new RuntimeException("Failed to save rule: " + rule.name(), e);
        }
    }

    public List<BusinessRule> getAllRules() {
        try {
            List<BusinessRule> rules = new ArrayList<>();
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT name, description, context, entity, severity, invariant FROM rules ORDER BY id")) {
                while (rs.next()) {
                    rules.add(new BusinessRule(
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("context"),
                        rs.getString("entity"),
                        Severity.valueOf(rs.getString("severity")),
                        rs.getString("invariant")
                    ));
                }
            }
            return rules;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load rules", e);
        }
    }

    /**
     * Return all rules whose entity field case-insensitively matches any of the given terms.
     * Returns empty list if terms is empty.
     */
    public List<BusinessRule> findRulesMentioning(java.util.Collection<String> terms) {
        if (terms == null || terms.isEmpty()) return List.of();
        try {
            List<BusinessRule> result = new ArrayList<>();
            String placeholders = terms.stream().map(t -> "?").collect(java.util.stream.Collectors.joining(", "));
            String sql = "SELECT name, description, context, entity, severity, invariant FROM rules " +
                         "WHERE LOWER(entity) IN (" + placeholders + ")";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                int i = 1;
                for (String term : terms) {
                    ps.setString(i++, term.toLowerCase());
                }
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    result.add(new BusinessRule(
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("context"),
                        rs.getString("entity"),
                        Severity.valueOf(rs.getString("severity")),
                        rs.getString("invariant")
                    ));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query rules by terms: " + terms, e);
        }
    }

    /**
     * Return all bounded contexts that list any of the given entity names in their context_entities.
     * Returns empty list if entityNames is empty.
     */
    public List<BoundedContext> findContextsContaining(java.util.Collection<String> entityNames) {
        if (entityNames == null || entityNames.isEmpty()) return List.of();
        try {
            String placeholders = entityNames.stream().map(e -> "?").collect(java.util.stream.Collectors.joining(", "));
            String sql = "SELECT DISTINCT c.id FROM contexts c " +
                         "JOIN context_entities ce ON ce.context_id = c.id " +
                         "WHERE LOWER(ce.entity_name) IN (" + placeholders + ")";
            List<Long> ids = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                int i = 1;
                for (String name : entityNames) {
                    ps.setString(i++, name.toLowerCase());
                }
                ResultSet rs = ps.executeQuery();
                while (rs.next()) ids.add(rs.getLong(1));
            }
            if (ids.isEmpty()) return List.of();

            // Re-use getAllContexts logic but filter to matched ids
            List<BoundedContext> result = new ArrayList<>();
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT id, name, description, owner FROM contexts ORDER BY id")) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    if (!ids.contains(id)) continue;
                    result.add(new BoundedContext(
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("owner"),
                        loadContextEntities(id),
                        loadContextCommunications(id)
                    ));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query contexts by entity names: " + entityNames, e);
        }
    }

    // ---- Entities ----

    public void saveEntity(DomainEntity entity) {
        try {
            // Delete existing entity with same name and context (cascade deletes children)
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM entities WHERE name = ? AND context = ?")) {
                ps.setString(1, entity.name());
                ps.setString(2, entity.context());
                ps.executeUpdate();
            }
            deleteFts(entity.name(), "entity");

            long entityId;
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO entities (name, context, type, description, code_mapping) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, entity.name());
                ps.setString(2, entity.context());
                ps.setString(3, entity.type());
                ps.setString(4, entity.description());
                ps.setString(5, entity.codeMapping());
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                keys.next();
                entityId = keys.getLong(1);
            }

            if (entity.fields() != null) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO entity_fields (entity_id, name, type, description, transitions) VALUES (?, ?, ?, ?, ?)")) {
                    for (EntityField field : entity.fields()) {
                        ps.setLong(1, entityId);
                        ps.setString(2, field.name());
                        ps.setString(3, field.type());
                        ps.setString(4, field.description());
                        ps.setString(5, field.transitions());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }

            insertFts("entity", entity.name(), entity.description(), entity.context());

        } catch (SQLException e) {
            throw new RuntimeException("Failed to save entity: " + entity.name(), e);
        }
    }

    public List<DomainEntity> getAllEntities() {
        try {
            List<DomainEntity> entities = new ArrayList<>();
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT id, name, context, type, description, code_mapping FROM entities ORDER BY id")) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    List<EntityField> fields = loadEntityFields(id);

                    entities.add(new DomainEntity(
                        rs.getString("name"),
                        rs.getString("context"),
                        rs.getString("type"),
                        rs.getString("description"),
                        fields,
                        rs.getString("code_mapping")
                    ));
                }
            }
            return entities;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load entities", e);
        }
    }

    private List<EntityField> loadEntityFields(long entityId) throws SQLException {
        List<EntityField> fields = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT name, type, description, transitions FROM entity_fields WHERE entity_id = ?")) {
            ps.setLong(1, entityId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                fields.add(new EntityField(
                    rs.getString("name"),
                    rs.getString("type"),
                    rs.getString("description"),
                    rs.getString("transitions")
                ));
            }
        }
        return fields;
    }

    // ---- Search ----

    public List<SearchResult> searchFts(String query) {
        try (var ps = connection.prepareStatement("""
                SELECT item_type, name, description, context
                FROM domain_fts WHERE domain_fts MATCH ?""")) {
            // Use prefix matching (query*) to handle partial matches
            // Also try without trailing 's' for basic plural handling
            String ftsQuery = query + "* OR " + (query.endsWith("s") ? query.substring(0, query.length() - 1) + "*" : query + "*");
            ps.setString(1, ftsQuery);
            var results = new ArrayList<SearchResult>();
            var rs = ps.executeQuery();
            while (rs.next()) {
                results.add(new SearchResult(
                    rs.getString("item_type"),
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getString("context")));
            }
            if (results.isEmpty()) {
                return searchLike(query);
            }
            return results;
        } catch (SQLException e) {
            // FTS5 MATCH can fail on invalid syntax — fall back to LIKE
            return searchLike(query);
        }
    }

    public List<SearchResult> searchLike(String query) {
        String like = "%" + query + "%";
        try (var ps = connection.prepareStatement("""
                SELECT item_type, name, description, context
                FROM domain_fts WHERE name LIKE ? OR description LIKE ?""")) {
            ps.setString(1, like);
            ps.setString(2, like);
            var results = new ArrayList<SearchResult>();
            var rs = ps.executeQuery();
            while (rs.next()) {
                results.add(new SearchResult(
                    rs.getString("item_type"),
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getString("context")));
            }
            return results;
        } catch (SQLException e) {
            throw new RuntimeException("LIKE search failed for: " + query, e);
        }
    }

    // ---- Utility ----

    public void clearAll() {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM domain_fts");
            stmt.execute("DELETE FROM context_entities");
            stmt.execute("DELETE FROM context_communications");
            stmt.execute("DELETE FROM contexts");
            stmt.execute("DELETE FROM term_aliases");
            stmt.execute("DELETE FROM term_related_entities");
            stmt.execute("DELETE FROM terms");
            stmt.execute("DELETE FROM flow_steps");
            stmt.execute("DELETE FROM flows");
            stmt.execute("DELETE FROM rules");
            stmt.execute("DELETE FROM entity_fields");
            stmt.execute("DELETE FROM entities");
            stmt.execute("DELETE FROM config");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear all data", e);
        }
    }

    public boolean isEmpty() {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM entities")) {
            rs.next();
            return rs.getInt(1) == 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check if empty", e);
        }
    }

    public DomainModel getModel(String projectName, String projectPath) {
        return new DomainModel(
            projectName,
            projectPath,
            getAllContexts(),
            getAllTerms(),
            getAllFlows(),
            getAllRules(),
            getAllEntities()
        );
    }

    // ---- AutoCloseable ----

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to close database connection", e);
        }
    }

    // ---- Private helpers ----

    private void deleteFts(String name, String itemType) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM domain_fts WHERE name = ? AND item_type = ?")) {
            ps.setString(1, name);
            ps.setString(2, itemType);
            ps.executeUpdate();
        }
    }

    private void insertFts(String itemType, String name, String description, String context) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO domain_fts (item_type, name, description, context) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, itemType);
            ps.setString(2, name);
            ps.setString(3, description);
            ps.setString(4, context);
            ps.executeUpdate();
        }
    }
}
