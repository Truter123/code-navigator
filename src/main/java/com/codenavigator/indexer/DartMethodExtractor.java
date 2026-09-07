package com.codenavigator.indexer;

import com.codenavigator.graph.Edge;
import com.codenavigator.graph.EdgeType;
import com.codenavigator.graph.MethodIds;
import com.codenavigator.graph.Node;
import com.codenavigator.graph.NodeType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Method-level indexing for Dart, mirroring what {@link TypeScriptMethodExtractor} does for
 * TypeScript: regex and brace-matching, not a parser, matching {@link DartIndexer}'s existing
 * approach. The cost is the same as the TypeScript side and is accepted for the same reason: a
 * declaration split oddly across lines is missed, and a call through anything other than an
 * implicit same-class receiver or a typed field is not resolved. Nothing is guessed; unresolved
 * calls are dropped and counted.
 *
 * <p>Dart has no {@code this.} convention for method calls (unlike TypeScript, where {@code this}
 * is how a same-class call is told apart from anything else) — a sibling method is usually called
 * bare, e.g. {@code _submit()}. Method names are lowerCamelCase by Dart convention and class names
 * are UpperCamelCase, so a bare call to a lowercase identifier cannot be confused with a
 * constructor invocation, which is what makes resolving it safe without a receiver.
 */
public class DartMethodExtractor {

    /** `class Foo extends Bar {` — the start of a class body; shared shape with DartIndexer. */
    private static final Pattern CLASS_DECL = Pattern.compile(
        "(?:(?:abstract|final|base|interface|sealed|mixin)\\s+)*class\\s+(\\w+)");

    /**
     * A method declaration inside a class body: optional {@code @override}, optional {@code static},
     * a return type (required in Dart except for constructors), a name, a parameter list, then an
     * opening brace. Getters ({@code Type get name}) are not matched — matching TypeScriptMethodExtractor's
     * bar, which does not chase accessor syntax either.
     */
    private static final Pattern METHOD_DECL = Pattern.compile(
        "^[ \\t]*(?:@override\\s*\\r?\\n[ \\t]*)?(?:(static)\\s+)?"
            + "(?:[A-Za-z_][\\w<>,.?\\[\\] ]*?\\s+)?"
            + "(\\w+)\\s*(?:<[^>(]*>)?\\s*\\(([^()]*)\\)\\s*(?:async\\*?|sync\\*?)?\\s*\\{",
        Pattern.MULTILINE);

    /** `final AuthRepository _repo;` or `late final TokenStore _tokens;` — a typed field. */
    private static final Pattern FIELD_DECL = Pattern.compile(
        "(?:final|late\\s+final|var)\\s+([\\w.<>?]+)\\s+(_?\\w+)\\s*[;=]");

    /** `_tokens.clear(` — a call through a field, the only receiver whose type is knowable here. */
    private static final Pattern FIELD_CALL = Pattern.compile("(?<!\\.)\\b(_?[a-z]\\w*)\\.(\\w+)\\s*\\(");

    /** `_submit(` or `this.foo(` — a bare or explicit same-class call. */
    private static final Pattern SIBLING_CALL = Pattern.compile("(?<![.\\w])(?:this\\.)?([a-z_]\\w*)\\s*\\(");

    /** Words that parse as a call or declaration but are control flow or literals, not a method. */
    private static final Set<String> NOT_METHODS = Set.of(
        "if", "for", "while", "switch", "catch", "return", "do", "else", "try", "finally", "new",
        "await", "yield", "throw", "case", "with", "super", "assert", "print", "is", "as", "in");

    /** Class-like node types the extractor will attach methods to. */
    private static final Set<NodeType> DART_CLASS_TYPES = Set.of(
        NodeType.FE_COMPONENT, NodeType.FE_SERVICE, NodeType.FE_MODEL,
        NodeType.COMMAND, NodeType.COMMAND_HANDLER, NodeType.QUERY, NodeType.QUERY_HANDLER);

    private int callSitesSeen;
    private int callSitesResolved;
    private int receiverExternal;

    public int callSitesSeen() { return callSitesSeen; }
    public int callSitesResolved() { return callSitesResolved; }

    /** Calls through a receiver this graph does not contain — {@code _client.post()}, Flutter/Dart SDK calls. */
    public int receiverExternal() { return receiverExternal; }

    /** A method found in a Dart class, with the class body it came from. */
    public record DartMethod(Node node, String ownerName, String body, Map<String, String> memberTypes) {}

    /**
     * Extract METHOD nodes for every class-like node declared in this file.
     *
     * @param classNodes the class-level nodes {@link DartIndexer} already produced
     */
    public List<DartMethod> extract(String content, List<Node> classNodes, String filePath, long lastModified) {
        var results = new ArrayList<DartMethod>();
        var byName = new HashMap<String, Node>();
        for (Node n : classNodes) {
            if (DART_CLASS_TYPES.contains(n.type())) byName.put(n.name(), n);
        }
        if (byName.isEmpty()) return results;

        // Comment-stripped, same length as content: a doc comment mentioning a method-shaped phrase
        // (see DartIndexer.stripComments) must not read as a declaration or a call site.
        String scan = DartIndexer.stripComments(content);

        Matcher decl = CLASS_DECL.matcher(scan);
        while (decl.find()) {
            Node owner = byName.get(decl.group(1));
            if (owner == null) continue;

            int open = scan.indexOf('{', decl.end());
            if (open < 0) continue;
            int close = TypeScriptMethodExtractor.matchingBrace(scan, open);
            if (close < 0) continue;

            String body = scan.substring(open + 1, close);
            var memberTypes = memberTypes(body);

            for (var found : methodsIn(body, owner.name())) {
                String id = MethodIds.of(owner.name(), found.name(), found.paramTypes());
                int line = lineAt(content, open + 1 + found.offset());
                var node = new Node(id, NodeType.METHOD, found.name(), id, filePath, line,
                    found.signature(), lastModified);
                results.add(new DartMethod(node, owner.name(), found.body(), memberTypes));
            }
        }
        return results;
    }

    /**
     * Resolve {@code method -> method} CALLS edges across the whole project.
     *
     * @param methods    every Dart method found, keyed by declaring class
     * @param knownTypes class-level node names that exist in the graph
     */
    public List<Edge> resolveCalls(List<DartMethod> methods, Set<String> knownTypes) {
        var byOwner = new LinkedHashMap<String, List<String>>();
        for (DartMethod m : methods) {
            byOwner.computeIfAbsent(m.ownerName(), k -> new ArrayList<>()).add(m.node().id());
        }

        var edges = new ArrayList<Edge>();
        var seen = new HashSet<String>();

        for (DartMethod m : methods) {
            String callerId = m.node().id();
            var ownMethodNames = byOwner.getOrDefault(m.ownerName(), List.of());

            // Bare / this.foo(...) — a sibling method on the same class.
            Matcher own = SIBLING_CALL.matcher(m.body());
            while (own.find()) {
                String name = own.group(1);
                if (NOT_METHODS.contains(name)) continue;
                boolean isSibling = ownMethodNames.stream()
                    .anyMatch(id -> name.equals(MethodIds.methodName(id)));
                if (!isSibling) continue;
                callSitesSeen++;
                pick(ownMethodNames, name).ifPresent(target -> add(edges, seen, callerId, target));
            }

            // field.other(...) — resolves when the field carries a declared type.
            Matcher viaField = FIELD_CALL.matcher(m.body());
            while (viaField.find()) {
                String fieldName = viaField.group(1);
                String methodName = viaField.group(2);
                if (NOT_METHODS.contains(methodName)) continue;
                String type = m.memberTypes().get(fieldName);
                if (type == null) continue; // not a known field access at all; not a call site we can judge
                callSitesSeen++;
                if (!knownTypes.contains(type)) {
                    receiverExternal++;
                    continue;
                }
                pick(byOwner.get(type), methodName)
                    .ifPresent(target -> add(edges, seen, callerId, target));
            }
        }
        return edges;
    }

    private void add(List<Edge> edges, Set<String> seen, String from, String to) {
        callSitesResolved++;
        if (from.equals(to) || !seen.add(from + ">" + to)) return;
        edges.add(new Edge(UUID.randomUUID().toString(), EdgeType.CALLS, from, to));
    }

    /**
     * Dart has no overloading with distinct bodies, so a name identifies one method. When a name
     * somehow appears twice, resolving to neither beats picking arbitrarily.
     */
    private Optional<String> pick(List<String> candidates, String name) {
        if (candidates == null) return Optional.empty();
        var hits = candidates.stream()
            .filter(id -> name.equals(MethodIds.methodName(id)))
            .toList();
        return hits.size() == 1 ? Optional.of(hits.get(0)) : Optional.empty();
    }

    // ---- Parsing helpers ----

    private record Found(String name, List<String> paramTypes, String signature, String body, int offset) {}

    private List<Found> methodsIn(String classBody, String ownerName) {
        var out = new ArrayList<Found>();
        var claimed = new HashSet<String>();

        Matcher m = METHOD_DECL.matcher(classBody);
        while (m.find()) {
            String name = m.group(2);
            if (NOT_METHODS.contains(name) || name.equals(ownerName) || !claimed.add(name)) continue;

            int open = classBody.indexOf('{', m.end() - 1);
            int close = TypeScriptMethodExtractor.matchingBrace(classBody, open);
            String body = (open >= 0 && close > open) ? classBody.substring(open + 1, close) : "";

            String modifiers = m.group(1) == null ? "" : m.group(1) + " ";
            String flatParams = m.group(3).strip().replaceAll("\\s+", " ");
            out.add(new Found(name, paramTypes(m.group(3)),
                (modifiers + name + "(" + flatParams + ")").strip(),
                body, m.start()));
        }
        return out;
    }

    /** `String a, int? b, {required Type c}` -> [String, int, Type]. */
    private List<String> paramTypes(String params) {
        var types = new ArrayList<String>();
        if (params == null || params.isBlank()) return types;

        // A `{...}` (named) or `[...]` (positional-optional) wrapper is punctuation around the
        // whole parameter list, not a generic bracket — unwrap it first so splitTopLevel's
        // depth-tracking (which treats `{`/`[` like `<` for `Map<K, V>`) does not see the
        // parameters inside as nested and collapse the entire block into one "parameter".
        String unwrapped = params.strip();
        if ((unwrapped.startsWith("{") && unwrapped.endsWith("}"))
                || (unwrapped.startsWith("[") && unwrapped.endsWith("]"))) {
            unwrapped = unwrapped.substring(1, unwrapped.length() - 1);
        }

        for (String raw : splitTopLevel(unwrapped)) {
            String part = raw.strip();
            part = part.replaceFirst("^required\\s+", "").strip();
            int eq = part.indexOf('=');
            if (eq >= 0) part = part.substring(0, eq).strip();          // drop a default value
            if (part.isEmpty()) continue;

            if (part.startsWith("this.")) {
                types.add("any");                    // constructor field shorthand: type lives on the field
                continue;
            }
            int space = part.lastIndexOf(' ');
            if (space < 0) {
                types.add("any");                    // no declared type
                continue;
            }
            String type = part.substring(0, space).strip();
            types.add(MethodIds.simplifyType(type));
        }
        return types;
    }

    /** Split on commas that are not inside brackets — `Map<String, int>` is one parameter. */
    private List<String> splitTopLevel(String params) {
        var parts = new ArrayList<String>();
        int depth = 0, start = 0;
        for (int i = 0; i < params.length(); i++) {
            char c = params.charAt(i);
            if (c == '<' || c == '[' || c == '{' || c == '(') depth++;
            else if (c == '>' || c == ']' || c == '}' || c == ')') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(params.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(params.substring(start));
        return parts;
    }

    /** Field types, so `_tokens.clear()` can find `_tokens`'s class. */
    private Map<String, String> memberTypes(String classBody) {
        var types = new HashMap<String, String>();
        Matcher field = FIELD_DECL.matcher(classBody);
        while (field.find()) {
            types.putIfAbsent(field.group(2), MethodIds.simplifyType(field.group(1)));
        }
        return types;
    }

    private static int lineAt(String content, int charIndex) {
        int line = 1;
        for (int i = 0; i < charIndex && i < content.length(); i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }
}
