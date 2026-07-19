package com.codenavigator.domain;

import com.codenavigator.domain.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class DomainToolHandlers {

    private final DomainSqliteStore store;
    private final Path codeGraphDb;

    public DomainToolHandlers(DomainSqliteStore store, Path codeGraphDb) {
        this.store = store;
        this.codeGraphDb = codeGraphDb;
    }

    public DomainSqliteStore store() { return store; }

    /** Re-extract domain data if code graph has been re-indexed since last extraction. */
    void syncIfStale() {
        if (codeGraphDb == null || !Files.exists(codeGraphDb)) return;
        try {
            long lastExtracted = store.getConfigLong("code_graph_modified");
            long currentModified = Files.getLastModifiedTime(codeGraphDb).toMillis();
            if (currentModified > lastExtracted) {
                System.err.println("Code graph changed, re-extracting domain...");
                new CodeNavigatorExtractor().extract(store, codeGraphDb);
            }
        } catch (IOException e) {
            System.err.println("Warning: could not check code-navigator staleness");
        }
    }

    // ---- Tool handlers ----

    public String handleDmContext(Map<String, Object> args) {
        syncIfStale();

        var sb = new StringBuilder();
        sb.append("## Bounded Contexts\n\n");

        for (var ctx : store.getAllContexts()) {
            sb.append("### ").append(ctx.name()).append("\n");
            sb.append(ctx.description()).append("\n");
            if (ctx.owner() != null) sb.append("**Owner:** ").append(ctx.owner()).append("\n");
            if (ctx.entities() != null && !ctx.entities().isEmpty()) {
                sb.append("**Entities:** ").append(String.join(", ", ctx.entities())).append("\n");
            }
            if (ctx.communicatesWith() != null && !ctx.communicatesWith().isEmpty()) {
                sb.append("**Communicates with:**\n");
                for (var comm : ctx.communicatesWith()) {
                    sb.append("  - ").append(comm.context())
                        .append(" (").append(comm.type()).append(")")
                        .append(comm.via() != null ? " via " + comm.via() : "")
                        .append("\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public String handleDmGlossary(Map<String, Object> args) {
        var termFilter = (String) args.get("term");
        var contextFilter = (String) args.get("context");
        syncIfStale();

        var sb = new StringBuilder();
        sb.append("## Glossary\n\n");
        for (var term : store.getAllTerms()) {
            if (termFilter != null && !term.name().equalsIgnoreCase(termFilter)) continue;
            if (contextFilter != null && !contextFilter.equalsIgnoreCase(term.context())) continue;
            sb.append("### ").append(term.name()).append("\n");
            sb.append(term.definition()).append("\n");
            if (term.aliases() != null && !term.aliases().isEmpty()) {
                sb.append("**Aliases:** ").append(String.join(", ", term.aliases())).append("\n");
            }
            if (term.context() != null) sb.append("**Context:** ").append(term.context()).append("\n");
            if (term.relatedEntities() != null && !term.relatedEntities().isEmpty()) {
                sb.append("**Related entities:** ").append(String.join(", ", term.relatedEntities())).append("\n");
            }
            if (term.businessRule() != null) sb.append("**Business rule:** ").append(term.businessRule()).append("\n");
            sb.append("\n");
        }
        return sb.toString();
    }

    public String handleDmFlow(Map<String, Object> args) {
        var nameFilter = (String) args.get("name");
        var contextFilter = (String) args.get("context");
        syncIfStale();

        var sb = new StringBuilder();
        sb.append("## Business Flows\n\n");
        for (var flow : store.getAllFlows()) {
            if (nameFilter != null && !flow.name().equalsIgnoreCase(nameFilter)) continue;
            if (contextFilter != null && !contextFilter.equalsIgnoreCase(flow.context())) continue;
            sb.append("### ").append(flow.name()).append("\n");
            sb.append(flow.description()).append("\n");
            if (flow.context() != null) sb.append("**Context:** ").append(flow.context()).append("\n");
            sb.append("**Trigger:** ").append(flow.trigger()).append("\n\n");
            sb.append("**Steps:**\n");
            for (int i = 0; i < flow.steps().size(); i++) {
                var step = flow.steps().get(i);
                sb.append(i + 1).append(". **").append(step.actor()).append("**: ").append(step.action()).append("\n");
                if (step.onFailure() != null) {
                    sb.append("   - On failure: ").append(step.onFailure()).append("\n");
                }
            }
            sb.append("\n**Outcome:** ").append(flow.outcome()).append("\n\n");
        }
        return sb.toString();
    }

    public String handleDmRules(Map<String, Object> args) {
        var entityFilter = (String) args.get("entity");
        var contextFilter = (String) args.get("context");
        var severityFilter = args.get("severity") != null ? ((String) args.get("severity")).toUpperCase() : null;
        syncIfStale();

        var sb = new StringBuilder();
        sb.append("## Business Rules\n\n");
        for (var rule : store.getAllRules()) {
            if (entityFilter != null && !entityFilter.equalsIgnoreCase(rule.entity())) continue;
            if (contextFilter != null && !contextFilter.equalsIgnoreCase(rule.context())) continue;
            if (severityFilter != null && !severityFilter.equals(rule.severity().name())) continue;
            sb.append("### ").append(rule.name()).append(" [").append(rule.severity()).append("]\n");
            sb.append(rule.description()).append("\n");
            if (rule.context() != null) sb.append("**Context:** ").append(rule.context()).append("\n");
            if (rule.entity() != null) sb.append("**Entity:** ").append(rule.entity()).append("\n");
            sb.append("**Invariant:** `").append(rule.invariant()).append("`\n\n");
        }
        return sb.toString();
    }

    public String handleDmEntity(Map<String, Object> args) {
        var nameFilter = (String) args.get("name");
        var contextFilter = (String) args.get("context");
        var typeFilter = (String) args.get("type");
        syncIfStale();

        var sb = new StringBuilder();
        sb.append("## Domain Entities\n\n");
        for (var entity : store.getAllEntities()) {
            if (nameFilter != null && !entity.name().equalsIgnoreCase(nameFilter)) continue;
            if (contextFilter != null && !contextFilter.equalsIgnoreCase(entity.context())) continue;
            if (typeFilter != null && !typeFilter.equalsIgnoreCase(entity.type())) continue;
            sb.append("### ").append(entity.name()).append(" (").append(entity.type()).append(")\n");
            sb.append(entity.description()).append("\n");
            if (entity.context() != null) sb.append("**Context:** ").append(entity.context()).append("\n");
            if (entity.codeMapping() != null) sb.append("**Code:** `").append(entity.codeMapping()).append("`\n");
            if (entity.fields() != null && !entity.fields().isEmpty()) {
                sb.append("**Fields:**\n");
                for (var field : entity.fields()) {
                    sb.append("  - `").append(field.name()).append("` (").append(field.type()).append("): ")
                        .append(field.description());
                    if (field.transitions() != null) {
                        sb.append(" — transitions: ").append(field.transitions());
                    }
                    sb.append("\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public String handleDmExplain(Map<String, Object> args) {
        var question = (String) args.get("question");
        syncIfStale();

        var search = new DomainSearchService(store);
        var result = search.explain(question);

        if (result.contains("No relevant domain knowledge found")) {
            return "No relevant domain knowledge found for: " + question;
        }

        var sb = new StringBuilder();
        sb.append("## Domain context for: ").append(question).append("\n\n");
        // Strip the header from the search result since we have our own
        var lines = result.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (i == 0 && lines[i].startsWith("## Domain context for:")) continue;
            if (i == 1 && lines[i].isBlank()) continue;
            sb.append(lines[i]).append("\n");
        }
        return sb.toString();
    }
}
