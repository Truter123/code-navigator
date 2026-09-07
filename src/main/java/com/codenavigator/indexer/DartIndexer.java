package com.codenavigator.indexer;

import com.codenavigator.graph.Edge;
import com.codenavigator.graph.EdgeType;
import com.codenavigator.graph.Node;
import com.codenavigator.graph.NodeType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex-based indexer for Dart/Flutter source, mirroring {@link TypeScriptIndexer}'s approach:
 * file-content scanned, no Dart grammar. This app's `lib/` mirrors the backend's CQRS shape and
 * Flutter's widget model, so detection maps onto the existing {@link NodeType} values rather than
 * inventing frontend-only ones:
 *
 * <pre>
 *   1 class X extends CommandHandler&lt;...&gt;   -> COMMAND_HANDLER
 *   2 class X extends QueryHandler&lt;...&gt;     -> QUERY_HANDLER
 *   3 class X extends Command                 -> COMMAND
 *   4 class X extends Query&lt;...&gt;            -> QUERY
 *   5 class X extends StatelessWidget/StatefulWidget/State&lt;...&gt; -> FE_COMPONENT
 *   6 abstract interface class X (a port)     -> FE_SERVICE
 *   7 class name ends Repository/Reads/Writes/Store/Bus/Client -> FE_SERVICE
 *   8 enum X                                  -> FE_ENUM
 *   9 anything else public                    -> FE_MODEL (plain data/DTO catch-all)
 * </pre>
 *
 * Rules 1-7 are exclusive per class (first match wins, same as TypeScriptIndexer's decorator
 * rules); a class not claimed by 1-7 falls to the FE_MODEL catch-all in rule 9.
 *
 * <p><b>Private classes are not indexed.</b> Dart's leading-underscore convention marks a symbol
 * library-private, and Flutter's split between a public {@code Widget} and its private
 * {@code _FooState}/{@code _FooPainter} companion makes those numerous — one pair per screen.
 * Skipping them mirrors what {@link TypeScriptIndexer} already does implicitly (it only matches
 * {@code export class/interface/enum}, i.e. the public surface); the value of the graph is inverse
 * to its noise, and a private State class is an implementation detail of the widget that declares
 * it, not a second thing worth naming.
 *
 * <p>Generated files are skipped entirely by the caller ({@code ProjectIndexer}), not here: files
 * under {@code lib/l10n/} (flutter gen-l10n output) and any {@code *.g.dart}/{@code *.freezed.dart}
 * build_runner output.
 */
public class DartIndexer {

    private static final Pattern CLASS_DECL = Pattern.compile(
        "(?:(?:abstract|final|base|interface|sealed|mixin)\\s+)*class\\s+(\\w+)");
    private static final Pattern ENUM_PATTERN = Pattern.compile("enum\\s+(\\w+)");

    private static final Pattern EXTENDS_IN_HEADER = Pattern.compile("extends\\s+(\\w+)");
    private static final Pattern SERVICE_SUFFIX = Pattern.compile(
        "(Repository|Reads|Writes|Store|Bus|Client)$");

    public List<Node> indexFile(Path file) {
        var nodes = new ArrayList<Node>();
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            return nodes;
        }

        var filePath = file.toString();
        long lastModified;
        try {
            lastModified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            lastModified = 0L;
        }

        String scan = stripComments(content);
        var claimed = new HashSet<String>();

        Matcher cls = CLASS_DECL.matcher(scan);
        while (cls.find()) {
            String name = cls.group(1);
            if (isPrivate(name) || !claimed.add(name)) continue;

            String modifiers = cls.group(0);
            String header = classHeader(scan, cls.end());

            NodeType type = classify(modifiers, header, name);
            nodes.add(node(name, type, content, cls.start(), filePath, lastModified));
        }

        Matcher en = ENUM_PATTERN.matcher(scan);
        while (en.find()) {
            String name = en.group(1);
            if (isPrivate(name) || !claimed.add(name)) continue;
            nodes.add(node(name, NodeType.FE_ENUM, content, en.start(), filePath, lastModified));
        }

        return nodes;
    }

    /**
     * Structural edges for one file's classes: {@code extends} -&gt; EXTENDS, {@code implements} and
     * {@code with} (a mixin, which adds behaviour the way an interface adds a contract; there is no
     * dedicated edge type for it) -&gt; IMPLEMENTS. Only emitted when the target is itself a known
     * Dart node — a framework base like {@code ChangeNotifier} or {@code StatelessWidget} is never
     * indexed, so it is silently skipped, the same way {@code EdgeExtractor} only links to nodes the
     * store already has.
     */
    public List<Edge> classEdges(Path file, Set<String> knownTypeNames) {
        var edges = new ArrayList<Edge>();
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            return edges;
        }

        String scan = stripComments(content);
        var seen = new HashSet<String>();
        Matcher cls = CLASS_DECL.matcher(scan);
        while (cls.find()) {
            String name = cls.group(1);
            if (isPrivate(name) || !seen.add(name) || !knownTypeNames.contains(name)) continue;

            String header = classHeader(scan, cls.end());

            Matcher ext = EXTENDS_IN_HEADER.matcher(header);
            if (ext.find() && knownTypeNames.contains(ext.group(1))) {
                edges.add(edge(EdgeType.EXTENDS, name, ext.group(1)));
            }
            for (String impl : listAfter(header, "implements")) {
                if (knownTypeNames.contains(impl)) edges.add(edge(EdgeType.IMPLEMENTS, name, impl));
            }
            for (String mixin : listAfter(header, "with")) {
                if (knownTypeNames.contains(mixin)) edges.add(edge(EdgeType.IMPLEMENTS, name, mixin));
            }
        }
        return edges;
    }

    /** The comma-separated type list after `implements`/`with` in a class header, up to the next such keyword. */
    private List<String> listAfter(String header, String keyword) {
        var pattern = Pattern.compile(
            "\\b" + keyword + "\\s+([\\w,\\s<>.]+?)(?=\\b(?:with|implements)\\b|$)");
        Matcher m = pattern.matcher(header);
        if (!m.find()) return List.of();

        var out = new ArrayList<String>();
        for (String part : m.group(1).split(",")) {
            String type = part.strip();
            int lt = type.indexOf('<');
            if (lt >= 0) type = type.substring(0, lt).strip();
            if (!type.isEmpty()) out.add(type);
        }
        return out;
    }

    /**
     * The class header — everything between the class name and its opening brace, minus a leading
     * generic type-parameter list. Without stripping it, {@code class CommandHandler<C extends
     * Command>} reads as "CommandHandler extends Command": the bound on a type parameter looks
     * exactly like a real extends clause to a regex that does not know the difference.
     */
    private String classHeader(String content, int afterName) {
        int headerEnd = content.indexOf('{', afterName);
        if (headerEnd <= afterName) return "";
        int start = skipTypeParams(content, afterName);
        return start <= headerEnd ? content.substring(start, headerEnd) : "";
    }

    /** If {@code pos} (after whitespace) is a `<`, returns the index just past its matching `>`. */
    private int skipTypeParams(String content, int pos) {
        int i = pos;
        while (i < content.length() && Character.isWhitespace(content.charAt(i))) i++;
        if (i >= content.length() || content.charAt(i) != '<') return pos;

        int depth = 0;
        for (; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') {
                depth--;
                if (depth == 0) return i + 1;
            } else if (c == '{' || c == ';') {
                return pos; // unbalanced; give up rather than swallow the real header
            }
        }
        return pos;
    }

    private Edge edge(EdgeType type, String sourceName, String targetName) {
        return new Edge(UUID.randomUUID().toString(), type, sourceName, targetName);
    }

    private NodeType classify(String modifiers, String header, String name) {
        Matcher ext = EXTENDS_IN_HEADER.matcher(header);
        String extendsName = ext.find() ? ext.group(1) : null;

        if ("CommandHandler".equals(extendsName)) return NodeType.COMMAND_HANDLER;
        if ("QueryHandler".equals(extendsName)) return NodeType.QUERY_HANDLER;
        if ("Command".equals(extendsName)) return NodeType.COMMAND;
        if ("Query".equals(extendsName)) return NodeType.QUERY;
        if ("StatelessWidget".equals(extendsName) || "StatefulWidget".equals(extendsName)
                || "State".equals(extendsName)) {
            return NodeType.FE_COMPONENT;
        }
        if (modifiers.contains("interface")) return NodeType.FE_SERVICE;
        if (SERVICE_SUFFIX.matcher(name).find()) return NodeType.FE_SERVICE;

        return NodeType.FE_MODEL;
    }

    /**
     * Blanks out `//` and `/* *&#47;` comments and string-literal contents, preserving length and
     * newlines so every match offset still lines up with the real {@code content} for line numbers
     * and snippets. Without this, prose like "per the class doc" or "enum names as strings" reads
     * as a declaration — Dart has no annotation that marks a comment the way `//` does in other
     * regex-scanned languages here, so a false "class doc" and "enum names" node were both found in
     * this app's own doc comments before this pass was added.
     */
    static String stripComments(String s) {
        var sb = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n) {
                    char d = s.charAt(j);
                    if (d == '\\') { j += 2; continue; }
                    if (d == c || d == '\n') break;
                    j++;
                }
                int end = Math.min(j + (j < n && s.charAt(j) == c ? 1 : 0), n);
                sb.append(s, i, end);
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                int end = s.indexOf('\n', i);
                int stop = end < 0 ? n : end;
                sb.append(" ".repeat(stop - i));
                i = stop;
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                for (int k = i; k < stop; k++) sb.append(s.charAt(k) == '\n' ? '\n' : ' ');
                i = stop;
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static boolean isPrivate(String name) {
        return name.startsWith("_");
    }

    private Node node(String name, NodeType type, String content, int start, String filePath, long lastModified) {
        return new Node(name, type, name, name, filePath, lineAt(content, start),
            buildSnippet(content, start), lastModified);
    }

    private String buildSnippet(String content, int startPos) {
        var lines = content.substring(startPos).lines().limit(10).toList();
        return String.join("\n", lines);
    }

    private int lineAt(String content, int charIndex) {
        int line = 1;
        for (int i = 0; i < charIndex && i < content.length(); i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }
}
