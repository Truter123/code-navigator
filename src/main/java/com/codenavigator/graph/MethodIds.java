package com.codenavigator.graph;

import java.util.List;

/**
 * Identity rules for {@link NodeType#METHOD} nodes.
 *
 * <p>A method id is {@code <declaringClassFqn>#<name>(<simpleParamTypes>)} — for example
 * {@code com.nlp.domain.andon.Andon#resolve(UUID)}. Simple parameter type names keep the id
 * short enough to read in a tool response and to type back into the next call, while still
 * separating overloads. Generic arguments are dropped for the same reason: {@code List<UUID>}
 * indexes as {@code List}.
 *
 * <p>Ids are stable across re-indexing as long as the signature is stable, which is what lets a
 * business rule anchor to a method (Stage 3) without re-resolving it every run.
 */
public final class MethodIds {

    public static final char SEPARATOR = '#';

    private MethodIds() {}

    /** Build the node id for a method declared on {@code classFqn}. */
    public static String of(String classFqn, String methodName, List<String> paramTypes) {
        return classFqn + SEPARATOR + methodName + "(" + String.join(",", paramTypes) + ")";
    }

    /** True when {@code id} looks like a method id rather than a type id. */
    public static boolean isMethodId(String id) {
        return id != null && id.indexOf(SEPARATOR) >= 0;
    }

    /** The declaring class fqn of a method id, or null when {@code id} is not a method id. */
    public static String declaringClass(String id) {
        if (!isMethodId(id)) return null;
        return id.substring(0, id.indexOf(SEPARATOR));
    }

    /** The bare method name of a method id, or null when {@code id} is not a method id. */
    public static String methodName(String id) {
        if (!isMethodId(id)) return null;
        String rest = id.substring(id.indexOf(SEPARATOR) + 1);
        int paren = rest.indexOf('(');
        return paren >= 0 ? rest.substring(0, paren) : rest;
    }

    /**
     * Display form: {@code Andon#resolve(UUID)} — the simple class name rather than the fqn,
     * since responses already carry {@code file:line}.
     */
    public static String shortLabel(String id) {
        if (!isMethodId(id)) return id;
        String cls = declaringClass(id);
        int lastDot = cls.lastIndexOf('.');
        String simple = lastDot >= 0 ? cls.substring(lastDot + 1) : cls;
        return simple + id.substring(id.indexOf(SEPARATOR));
    }

    /**
     * Strip generics and array/varargs marks from a declared type so the id stays short and
     * matches what a caller would type: {@code List<UUID>} -> {@code List}, {@code UUID...} ->
     * {@code UUID}, {@code com.foo.Bar} -> {@code Bar}.
     */
    public static String simplifyType(String type) {
        if (type == null || type.isBlank()) return "?";
        String t = type.trim();
        // An inline object or function type has no name worth carrying, and TypeScript spreads
        // them over several lines — embedding one verbatim produced multi-line ids that could not
        // be typed back into a tool call.
        if (t.startsWith("{")) return "object";
        if (t.contains("=>")) return "fn";
        int generic = t.indexOf('<');
        if (generic >= 0) t = t.substring(0, generic);
        t = t.replace("...", "").replace("[]", "");
        int lastDot = t.lastIndexOf('.');
        if (lastDot >= 0) t = t.substring(lastDot + 1);
        t = t.replaceAll("\\s+", "");
        if (t.length() > 40) t = t.substring(0, 40);
        return t.isBlank() ? "?" : t;
    }
}
