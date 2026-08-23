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
 * Method-level indexing for TypeScript, mirroring what {@link MethodExtractor} and
 * {@link MethodCallExtractor} do for Java.
 *
 * <p>Regex and brace-matching rather than a parser, matching {@link TypeScriptIndexer}'s existing
 * approach — adding a TypeScript grammar for this would be a far larger change than the answer is
 * worth. The cost is real and bounded: a declaration split oddly across lines is missed, and a call
 * through anything other than {@code this} or a typed field is not resolved. Nothing is guessed;
 * unresolved calls are dropped and counted, exactly as on the Java side.
 */
public class TypeScriptMethodExtractor {

    /** `export class Foo extends Bar {` — the start of a class body. */
    private static final Pattern CLASS_DECL = Pattern.compile(
        "(?:export\\s+)?(?:abstract\\s+)?class\\s+(\\w+)");

    /**
     * A method declaration inside a class body: optional modifiers, a name, a parameter list, an
     * optional return type, then an opening brace.
     */
    private static final Pattern METHOD_DECL = Pattern.compile(
        "^[ \\t]*(?:(public|private|protected)\\s+)?(?:static\\s+)?(?:override\\s+)?(?:readonly\\s+)?"
            + "(?:(async)\\s+)?(?:(get|set)\\s+)?(\\w+)\\s*(?:<[^>(]*>)?\\s*"
            + "\\(([^()]*)\\)\\s*(?::\\s*[^{;=]+?)?\\s*\\{",
        Pattern.MULTILINE);

    /** `foo = (a: string) => {` — an arrow-function property, which is a method in all but syntax. */
    private static final Pattern ARROW_METHOD = Pattern.compile(
        "^[ \\t]*(?:(public|private|protected)\\s+)?(?:static\\s+)?(?:readonly\\s+)?"
            + "(\\w+)\\s*(?::\\s*[^=;]+)?=\\s*(?:async\\s+)?\\(([^()]*)\\)\\s*(?::\\s*[^=]+?)?=>",
        Pattern.MULTILINE);

    /** `private readonly svc: WorkerService` — constructor parameter or class property. */
    private static final Pattern TYPED_MEMBER = Pattern.compile(
        "(?:public|private|protected|readonly)\\s+(?:readonly\\s+)?(\\w+)\\s*:\\s*([\\w.]+)");
    private static final Pattern PROPERTY_DECL = Pattern.compile(
        "^[ \\t]*(?:(?:public|private|protected|readonly|static)\\s+)*(\\w+)\\s*:\\s*([\\w.]+)",
        Pattern.MULTILINE);

    /** `this.foo(` and `this.bar.foo(` — the only receivers whose type is knowable here. */
    private static final Pattern THIS_CALL = Pattern.compile("this\\.(\\w+)\\s*\\(");
    private static final Pattern THIS_FIELD_CALL = Pattern.compile("this\\.(\\w+)\\.(\\w+)\\s*\\(");

    /**
     * Words that look like a method declaration but are control flow. Without this,
     * {@code if (ready) {} } indexes as a method named {@code if}.
     */
    private static final Set<String> NOT_METHODS = Set.of(
        "if", "for", "while", "switch", "catch", "return", "function", "typeof", "do", "else",
        "try", "finally", "new", "await", "yield", "throw", "case", "with", "delete", "void");

    /** Class-like node types the extractor will attach methods to. */
    private static final Set<NodeType> TS_CLASS_TYPES = Set.of(
        NodeType.FE_SERVICE, NodeType.FE_COMPONENT, NodeType.FE_CLASS, NodeType.FE_PIPE,
        NodeType.FE_GUARD, NodeType.FE_INTERCEPTOR, NodeType.FE_VALIDATOR);

    private int callSitesSeen;
    private int callSitesResolved;
    private int receiverExternal;

    public int callSitesSeen() { return callSitesSeen; }
    public int callSitesResolved() { return callSitesResolved; }

    /**
     * Calls through a receiver this graph does not contain — {@code this.router.navigate()},
     * {@code this.fb.group()}. Angular and RxJS account for most calls in a component, and counting
     * them as failures buries how well the project's own calls resolve.
     */
    public int receiverExternal() { return receiverExternal; }

    /** A method found in a TypeScript class, with the class body it came from. */
    public record TsMethod(Node node, String ownerName, String body,
                           Map<String, String> memberTypes) {}

    /**
     * Extract METHOD nodes for every class-like node declared in this file.
     *
     * @param classNodes the class-level nodes {@link TypeScriptIndexer} already produced
     */
    public List<TsMethod> extract(String content, List<Node> classNodes, String filePath,
                                  long lastModified) {
        var results = new ArrayList<TsMethod>();
        var byName = new HashMap<String, Node>();
        for (Node n : classNodes) {
            if (TS_CLASS_TYPES.contains(n.type())) byName.put(n.name(), n);
        }
        if (byName.isEmpty()) return results;

        Matcher decl = CLASS_DECL.matcher(content);
        while (decl.find()) {
            Node owner = byName.get(decl.group(1));
            if (owner == null) continue;

            int open = content.indexOf('{', decl.end());
            if (open < 0) continue;
            int close = matchingBrace(content, open);
            if (close < 0) continue;

            String body = content.substring(open + 1, close);
            var memberTypes = memberTypes(body);

            for (var found : methodsIn(body)) {
                String id = MethodIds.of(owner.name(), found.name(), found.paramTypes());
                int line = lineAt(content, open + 1 + found.offset());
                var node = new Node(id, NodeType.METHOD, found.name(), id, filePath, line,
                    found.signature(), lastModified);
                results.add(new TsMethod(node, owner.name(), found.body(), memberTypes));
            }
        }
        return results;
    }

    /**
     * Resolve {@code method -> method} CALLS edges across the whole project.
     *
     * @param methods    every TypeScript method found, keyed by declaring class
     * @param knownTypes class-level node names that exist in the graph
     */
    public List<Edge> resolveCalls(List<TsMethod> methods, Set<String> knownTypes) {
        var byOwner = new LinkedHashMap<String, List<String>>();
        for (TsMethod m : methods) {
            byOwner.computeIfAbsent(m.ownerName(), k -> new ArrayList<>()).add(m.node().id());
        }

        var edges = new ArrayList<Edge>();
        var seen = new HashSet<String>();

        for (TsMethod m : methods) {
            String callerId = m.node().id();

            // this.other(...) — a sibling method on the same class.
            Matcher own = THIS_CALL.matcher(m.body());
            while (own.find()) {
                callSitesSeen++;
                // this.svc.foo() also matches THIS_CALL on "svc"; the field form handles it.
                if (m.body().startsWith(".", own.end() - 1)) continue;
                pick(byOwner.get(m.ownerName()), own.group(1))
                    .ifPresent(target -> add(edges, seen, callerId, target));
            }

            // this.field.other(...) — resolves when the field carries a declared type.
            Matcher viaField = THIS_FIELD_CALL.matcher(m.body());
            while (viaField.find()) {
                callSitesSeen++;
                String type = m.memberTypes().get(viaField.group(1));
                if (type == null || !knownTypes.contains(type)) {
                    receiverExternal++;
                    continue;
                }
                pick(byOwner.get(type), viaField.group(2))
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
     * TypeScript has no overloading with distinct bodies, so a name identifies one method. When a
     * name somehow appears twice, resolving to neither beats picking arbitrarily.
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

    private List<Found> methodsIn(String classBody) {
        var out = new ArrayList<Found>();
        var claimed = new HashSet<String>();

        Matcher m = METHOD_DECL.matcher(classBody);
        while (m.find()) {
            String name = m.group(4);
            if (NOT_METHODS.contains(name) || !claimed.add(name)) continue;

            int open = classBody.indexOf('{', m.end() - 1);
            int close = matchingBrace(classBody, open);
            String body = (open >= 0 && close > open) ? classBody.substring(open + 1, close) : "";

            String accessor = m.group(3) == null ? "" : m.group(3) + " ";
            String modifiers = (m.group(1) == null ? "" : m.group(1) + " ")
                + (m.group(2) == null ? "" : "async ");
            out.add(new Found(name, paramTypes(m.group(5)),
                (modifiers + accessor + name + "(" + m.group(5).strip() + ")").strip(),
                body, m.start()));
        }

        Matcher arrow = ARROW_METHOD.matcher(classBody);
        while (arrow.find()) {
            String name = arrow.group(2);
            if (NOT_METHODS.contains(name) || !claimed.add(name)) continue;
            int open = classBody.indexOf('{', arrow.end());
            int close = open >= 0 ? matchingBrace(classBody, open) : -1;
            String body = (open >= 0 && close > open) ? classBody.substring(open + 1, close) : "";
            out.add(new Found(name, paramTypes(arrow.group(3)),
                (name + " = (" + arrow.group(3).strip() + ") =>").strip(), body, arrow.start()));
        }
        return out;
    }

    /** `a: string, b?: number, private c: Svc` -> [string, number, Svc]. */
    private List<String> paramTypes(String params) {
        var types = new ArrayList<String>();
        if (params == null || params.isBlank()) return types;

        for (String raw : splitTopLevel(params)) {
            String part = raw.strip();
            if (part.isEmpty()) continue;
            int colon = part.indexOf(':');
            if (colon < 0) {
                types.add("any");                       // untyped parameter
                continue;
            }
            String type = part.substring(colon + 1).strip();
            int eq = type.indexOf('=');                 // default value
            if (eq >= 0) type = type.substring(0, eq).strip();
            types.add(MethodIds.simplifyType(type));
        }
        return types;
    }

    /** Split on commas that are not inside brackets — `Map<string, number>` is one parameter. */
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

    /** Field and constructor-parameter types, so `this.svc.load()` can find `svc`'s class. */
    private Map<String, String> memberTypes(String classBody) {
        var types = new HashMap<String, String>();
        Matcher injected = TYPED_MEMBER.matcher(classBody);
        while (injected.find()) {
            types.put(injected.group(1), MethodIds.simplifyType(injected.group(2)));
        }
        Matcher property = PROPERTY_DECL.matcher(classBody);
        while (property.find()) {
            types.putIfAbsent(property.group(1), MethodIds.simplifyType(property.group(2)));
        }
        return types;
    }

    /** Index of the brace closing the one at {@code open}, or -1. Skips strings and comments. */
    static int matchingBrace(String s, int open) {
        if (open < 0 || open >= s.length() || s.charAt(open) != '{') return -1;
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"', '\'', '`' -> i = skipString(s, i, c);
                case '/' -> i = skipComment(s, i);
                case '{' -> depth++;
                case '}' -> { if (--depth == 0) return i; }
                default -> { }
            }
            if (i < 0) return -1;
        }
        return -1;
    }

    private static int skipString(String s, int i, char quote) {
        for (int j = i + 1; j < s.length(); j++) {
            char c = s.charAt(j);
            if (c == '\\') { j++; continue; }
            if (c == quote) return j;
        }
        return s.length();
    }

    private static int skipComment(String s, int i) {
        if (i + 1 >= s.length()) return i;
        char next = s.charAt(i + 1);
        if (next == '/') {
            int end = s.indexOf('\n', i);
            return end < 0 ? s.length() : end;
        }
        if (next == '*') {
            int end = s.indexOf("*/", i + 2);
            return end < 0 ? s.length() : end + 1;
        }
        return i;
    }

    private static int lineAt(String content, int charIndex) {
        int line = 1;
        for (int i = 0; i < charIndex && i < content.length(); i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }
}
