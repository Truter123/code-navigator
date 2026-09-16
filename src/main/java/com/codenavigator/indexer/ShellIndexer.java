package com.codenavigator.indexer;

import com.codenavigator.graph.Edge;
import com.codenavigator.graph.EdgeType;
import com.codenavigator.graph.MethodIds;
import com.codenavigator.graph.Node;
import com.codenavigator.graph.NodeType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex-based {@link LanguageIndexer} for shell scripts, mirroring {@link GroovyIndexer}/{@link
 * DartIndexer}'s approach: file-content scanned, no shell grammar. One {@link
 * NodeType#SHELL_SCRIPT} node per file, one {@link NodeType#METHOD} node per function, and edges
 * for calling a sibling function ({@code CALLS}), sourcing another script ({@code SOURCES}) and
 * running a known binary ({@code RUNS} — the {@link NodeType#SHELL_COMMAND} target is minted
 * lazily by {@link ProjectIndexer}, the same way {@code EdgeExtractor} mints a {@code LIBRARY}
 * node for {@code USES_LIBRARY}).
 */
public class ShellIndexer implements LanguageIndexer {

    private static final Set<String> EXCLUDED_SEGMENTS =
        Set.of("build", ".gradle", "node_modules", "navigators", "target");

    /** `name() {` or `function name {` / `function name() {` — the start of a function body. */
    private static final Pattern FUNCTION_DECL = Pattern.compile(
        "^[ \\t]*(?:function\\s+(\\w+)\\s*(?:\\(\\))?|(\\w+)\\s*\\(\\))\\s*\\{", Pattern.MULTILINE);

    private static final Pattern HEREDOC_START = Pattern.compile("<<-?\\s*(['\"]?)(\\w+)\\1");

    private static final Pattern SOURCE_DOT = Pattern.compile(
        "(?:^|[;&|(\\s])(?:source|\\.)\\s+\"?([\\w./$-]+\\.sh)\"?", Pattern.MULTILINE);
    private static final Pattern SOURCE_INTERPRETER = Pattern.compile(
        "\\b(?:bash|sh)\\s+\"?([\\w./-]+\\.sh)\"?");
    private static final Pattern SOURCE_RELATIVE = Pattern.compile(
        "(?:^|[;&|(\\s])\\./([\\w./-]+\\.sh)", Pattern.MULTILINE);

    /** Longer names first so `docker-compose` wins over `docker`, `python3` over `python`, etc. */
    private static final Pattern RUN_CMD = Pattern.compile(
        "(?:^|;|&&|\\|\\||\\||\\(|\\$\\(|\\bsudo |\\bexec |\\btime )\\s*"
        + "(docker-compose|docker|\\./gradlew|gradlew|gradle|mvn|npm|npx|node|python3|python|"
        + "pip3|pip|psql|ssh|scp|rsync|kubectl|terraform|git|claude|jq|curl|java)\\b",
        Pattern.MULTILINE);

    @Override
    public boolean matches(Path file) {
        String path = file.toString();
        if (!path.endsWith(".sh")) return false;
        for (String segment : EXCLUDED_SEGMENTS) {
            if (path.contains(segment)) return false;
        }
        return true;
    }

    @Override
    public List<Node> indexFile(Path file, Path projectRoot) {
        var nodes = new ArrayList<Node>();
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            return nodes;
        }

        String filePath = projectRoot.relativize(file).toString();
        long lastModified;
        try {
            lastModified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            lastModified = 0L;
        }

        String name = scriptName(file);
        nodes.add(new Node(filePath, NodeType.SHELL_SCRIPT, name, filePath, filePath, 1,
            buildSnippet(content, 0), lastModified));
        return nodes;
    }

    @Override
    public List<Node> extractMethods(Path file, List<Node> fileNodes) {
        Node script = scriptNode(fileNodes);
        if (script == null) return List.of();

        String content;
        long lastModified;
        try {
            content = Files.readString(file);
            lastModified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return List.of();
        }

        String scan = stripForScan(content);
        var results = new ArrayList<Node>();
        var claimed = new HashSet<String>();
        for (Fn fn : findFunctions(scan)) {
            if (!claimed.add(fn.name())) continue;
            String id = MethodIds.of(script.id(), fn.name(), List.of());
            results.add(new Node(id, NodeType.METHOD, fn.name(), id, script.filePath(),
                lineAt(content, fn.start()), buildSnippet(content, fn.start()), lastModified));
        }
        return results;
    }

    @Override
    public List<Edge> extractEdges(Path file, List<Node> fileNodes, KnownNames known) {
        Node script = scriptNode(fileNodes);
        if (script == null) return List.of();

        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            return List.of();
        }

        String scan = stripForScan(content);
        var functions = findFunctions(scan);
        var methodIdByName = new LinkedHashMap<String, String>();
        for (Fn fn : functions) {
            methodIdByName.putIfAbsent(fn.name(), MethodIds.of(script.id(), fn.name(), List.of()));
        }

        var edges = new ArrayList<Edge>();
        var dedupe = new HashSet<String>();

        // DECLARES_METHOD: script -> every function it declares.
        for (String methodId : methodIdByName.values()) {
            addEdge(edges, dedupe, EdgeType.DECLARES_METHOD, script.id(), methodId);
        }

        // CALLS inside a function body: function -> sibling function.
        for (Fn fn : functions) {
            String body = scan.substring(fn.open() + 1, fn.close());
            String callerId = methodIdByName.get(fn.name());
            for (String calleeName : siblingCallsIn(body, methodIdByName.keySet())) {
                if (calleeName.equals(fn.name())) continue; // recursion is not modelled
                addEdge(edges, dedupe, EdgeType.CALLS, callerId, methodIdByName.get(calleeName));
            }
        }

        // CALLS at top level: script -> function, from everything outside a function body.
        String outside = blankRanges(scan, functions);
        for (String calleeName : siblingCallsIn(outside, methodIdByName.keySet())) {
            addEdge(edges, dedupe, EdgeType.CALLS, script.id(), methodIdByName.get(calleeName));
        }

        // SOURCES: script -> script, only when the target file exists in the project.
        Path root = projectRoot(file, script);
        for (String targetId : sourcedScriptIds(scan, file, root)) {
            addEdge(edges, dedupe, EdgeType.SOURCES, script.id(), targetId);
        }

        // RUNS: script -> SHELL_COMMAND, one edge per distinct command.
        for (String command : runCommands(scan)) {
            addEdge(edges, dedupe, EdgeType.RUNS, script.id(), command);
        }

        return edges;
    }

    private Node scriptNode(List<Node> fileNodes) {
        return fileNodes.stream().filter(n -> n.type() == NodeType.SHELL_SCRIPT).findFirst().orElse(null);
    }

    private void addEdge(List<Edge> edges, Set<String> dedupe, EdgeType type, String from, String to) {
        if (from == null || to == null || from.equals(to)) return;
        if (!dedupe.add(type + "|" + from + "|" + to)) return;
        edges.add(new Edge(UUID.randomUUID().toString(), type, from, to));
    }

    // ---- Function parsing ----

    private record Fn(String name, int start, int open, int close) {}

    private List<Fn> findFunctions(String scan) {
        var out = new ArrayList<Fn>();
        Matcher m = FUNCTION_DECL.matcher(scan);
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2);
            int open = m.end() - 1; // the matched '{'
            int close = TypeScriptMethodExtractor.matchingBrace(scan, open);
            if (close < 0) continue;
            out.add(new Fn(name, m.start(), open, close));
        }
        return out;
    }

    /** Text with each function's full range (declaration through closing brace) blanked out. */
    private String blankRanges(String scan, List<Fn> functions) {
        char[] chars = scan.toCharArray();
        for (Fn fn : functions) {
            for (int i = fn.start(); i <= fn.close() && i < chars.length; i++) {
                if (chars[i] != '\n') chars[i] = ' ';
            }
        }
        return new String(chars);
    }

    /**
     * Names from {@code candidates} that appear as a call in {@code text}: a word boundary before
     * the name and, after it, whitespace, `;`, `|`, `&`, `)` or end of line — never `(` (a
     * declaration) or `=` (an assignment), because those characters are simply not in the allowed
     * set.
     */
    private Set<String> siblingCallsIn(String text, Set<String> candidates) {
        var found = new LinkedHashSet<String>();
        for (String name : candidates) {
            var pattern = Pattern.compile(
                "(?<![\\w.])" + Pattern.quote(name) + "(?=[\\s;|&)]|$)", Pattern.MULTILINE);
            if (pattern.matcher(text).find()) found.add(name);
        }
        return found;
    }

    // ---- SOURCES ----

    private Set<String> sourcedScriptIds(String scan, Path file, Path root) {
        var raw = new LinkedHashSet<String>();
        collect(SOURCE_DOT, scan, raw);
        collect(SOURCE_INTERPRETER, scan, raw);
        collect(SOURCE_RELATIVE, scan, raw);

        var ids = new LinkedHashSet<String>();
        Path scriptDir = file.toAbsolutePath().getParent();
        for (String path : raw) {
            String stripped = path.startsWith("$SCRIPT_DIR/") ? path.substring("$SCRIPT_DIR/".length()) : path;
            Path resolved = scriptDir.resolve(stripped).normalize();
            if (!Files.exists(resolved)) continue;
            if (!resolved.startsWith(root)) continue;
            ids.add(root.relativize(resolved).toString());
        }
        return ids;
    }

    private void collect(Pattern pattern, String text, Set<String> out) {
        Matcher m = pattern.matcher(text);
        while (m.find()) out.add(m.group(1));
    }

    /** The project root, recovered from the file's absolute path and its own project-relative id. */
    private Path projectRoot(Path file, Node script) {
        Path root = file.toAbsolutePath().normalize();
        int segments = script.filePath().isEmpty() ? 0 : script.filePath().split("/").length;
        for (int i = 0; i < segments && root.getParent() != null; i++) {
            root = root.getParent();
        }
        return root;
    }

    // ---- RUNS ----

    private Set<String> runCommands(String scan) {
        var found = new LinkedHashSet<String>();
        Matcher m = RUN_CMD.matcher(scan);
        while (m.find()) found.add(m.group(1));
        return found;
    }

    // ---- Comment / heredoc stripping ----

    /**
     * Blanks `#` comments outside quotes, then heredoc bodies, preserving length and newlines so
     * every match offset still lines up with the real file content for line numbers and snippets
     * — the same discipline {@link DartIndexer#stripComments} uses.
     */
    static String stripForScan(String content) {
        return blankHeredocs(stripHashComments(content));
    }

    private static String stripHashComments(String s) {
        var sb = new StringBuilder(s.length());
        int i = 0, n = s.length();
        Character quote = null;
        while (i < n) {
            char c = s.charAt(i);
            if (quote != null) {
                if (c == '\\' && i + 1 < n) {
                    sb.append(c).append(s.charAt(i + 1));
                    i += 2;
                    continue;
                }
                sb.append(c);
                if (c == quote) quote = null;
                i++;
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                sb.append(c);
                i++;
                continue;
            }
            if (c == '#') {
                int end = s.indexOf('\n', i);
                int stop = end < 0 ? n : end;
                sb.append(" ".repeat(stop - i));
                i = stop;
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String blankHeredocs(String s) {
        var sb = new StringBuilder(s);
        Matcher m = HEREDOC_START.matcher(s);
        while (m.find()) {
            String delimiter = m.group(2);
            int lineStart = s.indexOf('\n', m.end());
            if (lineStart < 0) continue;

            int pos = lineStart + 1;
            int bodyEnd = -1;
            while (pos <= s.length()) {
                int nextNl = s.indexOf('\n', pos);
                int lineEnd = nextNl < 0 ? s.length() : nextNl;
                if (s.substring(pos, lineEnd).trim().equals(delimiter)) {
                    bodyEnd = pos;
                    break;
                }
                if (nextNl < 0) break;
                pos = nextNl + 1;
            }
            if (bodyEnd < 0) continue; // unterminated heredoc; leave the text as-is

            for (int i = lineStart + 1; i < bodyEnd; i++) {
                if (sb.charAt(i) != '\n') sb.setCharAt(i, ' ');
            }
        }
        return sb.toString();
    }

    // ---- Utilities ----

    private static String scriptName(Path file) {
        String fn = file.getFileName().toString();
        int dot = fn.lastIndexOf('.');
        return dot > 0 ? fn.substring(0, dot) : fn;
    }

    private String buildSnippet(String content, int startPos) {
        var lines = content.substring(startPos).lines().limit(10).toList();
        return String.join("\n", lines);
    }

    private static int lineAt(String content, int charIndex) {
        int line = 1;
        for (int i = 0; i < charIndex && i < content.length(); i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }
}
