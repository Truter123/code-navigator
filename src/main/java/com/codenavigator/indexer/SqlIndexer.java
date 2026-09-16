package com.codenavigator.indexer;

import com.codenavigator.graph.Edge;
import com.codenavigator.graph.EdgeType;
import com.codenavigator.graph.Node;
import com.codenavigator.graph.NodeType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex-based {@link LanguageIndexer} for SQL migrations. Unlike {@link ShellIndexer}, SQL
 * semantics are whole-history ("last migration wins"): a rename or drop in a later file changes
 * what an earlier file's {@code CREATE} means. {@link #prepare} walks every file once, in Flyway
 * numeric order, and builds the final schema in memory; {@link #indexFile} and {@link
 * #extractEdges} just read the slice of that model whose defining file matches.
 */
public class SqlIndexer implements LanguageIndexer {

    private static final Set<String> EXCLUDED_SEGMENTS =
        Set.of("build", "target", "node_modules", ".gradle", "navigators", "out", "dist");

    private static final Pattern FLYWAY_VERSIONED =
        Pattern.compile("^V((?:\\d+)(?:[._]\\d+)*)__", Pattern.CASE_INSENSITIVE);

    private static final Pattern DOLLAR_TAG = Pattern.compile("\\$\\w*\\$");

    private static final Pattern CREATE_PATTERN = Pattern.compile(
        "CREATE\\s+(?:(OR\\s+REPLACE)\\s+)?(?:UNLOGGED\\s+|TEMP(?:ORARY)?\\s+)?"
            + "(TABLE|VIEW|MATERIALIZED\\s+VIEW|FUNCTION|PROCEDURE)\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?"
            + "(\"?[\\w]+\"?(?:\\.\"?[\\w]+\"?)?)",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern DROP_PATTERN = Pattern.compile(
        "DROP\\s+(TABLE|VIEW|MATERIALIZED\\s+VIEW|FUNCTION|PROCEDURE)\\s+(?:IF\\s+EXISTS\\s+)?"
            + "(\"?[\\w.]+\"?)",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern RENAME_PATTERN = Pattern.compile(
        "ALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(\"?[\\w.]+\"?)\\s+RENAME\\s+TO\\s+(\"?[\\w.]+\"?)",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern ALTER_FK_PATTERN = Pattern.compile(
        "ALTER\\s+TABLE\\s+(?:ONLY\\s+)?(\"?[\\w.]+\"?)\\s+ADD\\s+(?:CONSTRAINT\\s+\\S+\\s+)?"
            + "FOREIGN\\s+KEY[^;]*?REFERENCES\\s+(\"?[\\w.]+\"?)",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern INLINE_REFERENCES =
        Pattern.compile("REFERENCES\\s+(\"?[\\w.]+\"?)", Pattern.CASE_INSENSITIVE);

    private static final Pattern FROM_JOIN =
        Pattern.compile("\\b(?:FROM|JOIN)\\s+(\"?[\\w.]+\"?)", Pattern.CASE_INSENSITIVE);

    private static final Pattern AS_KEYWORD = Pattern.compile("\\bAS\\b", Pattern.CASE_INSENSITIVE);

    /** name -> object, keyed by current lowercase bare name; rebuilt fresh by every {@link #prepare}. */
    private Map<String, SchemaObject> objects = new LinkedHashMap<>();
    /** old lowercase bare name -> new lowercase bare name, for resolving a reference made before a rename. */
    private Map<String, String> aliases = new LinkedHashMap<>();

    private static final class SchemaObject {
        NodeType kind;
        String name;
        String qualifiedName;
        String definingFile;
        int definingLine;
        String snippet;
        long lastModified;
        final Set<String> fkTargets = new LinkedHashSet<>();
        final Set<String> derivesFrom = new LinkedHashSet<>();
    }

    @Override
    public boolean matches(Path file) {
        String path = file.toString();
        if (!path.endsWith(".sql")) return false;
        for (String segment : EXCLUDED_SEGMENTS) {
            if (path.contains(segment)) return false;
        }
        return true;
    }

    @Override
    public boolean rebuildAllOnChange() {
        return true;
    }

    @Override
    public Set<NodeType> ownedTypes() {
        return Set.of(NodeType.SQL_TABLE, NodeType.SQL_VIEW, NodeType.SQL_ROUTINE);
    }

    @Override
    public void prepare(List<Path> files, Path projectRoot) {
        objects = new LinkedHashMap<>();
        aliases = new LinkedHashMap<>();

        for (Path file : orderFiles(files)) {
            String content;
            long lastModified;
            try {
                content = Files.readString(file);
                lastModified = Files.getLastModifiedTime(file).toMillis();
            } catch (IOException e) {
                continue;
            }
            String rel = projectRoot.relativize(file).toString();
            String blanked = blankComments(content);
            for (int[] range : splitStatements(blanked)) {
                processStatement(content, blanked, range[0], range[1], rel, lastModified);
            }
        }
    }

    @Override
    public List<Node> indexFile(Path file, Path projectRoot) {
        String rel = projectRoot.relativize(file).toString();
        var nodes = new ArrayList<Node>();
        for (SchemaObject obj : objects.values()) {
            if (!obj.definingFile.equals(rel)) continue;
            nodes.add(new Node(nodeId(obj.name), obj.kind, obj.name, obj.qualifiedName,
                obj.definingFile, obj.definingLine, obj.snippet, obj.lastModified));
        }
        return nodes;
    }

    @Override
    public List<Edge> extractEdges(Path file, List<Node> fileNodes, KnownNames known) {
        if (fileNodes.isEmpty()) return List.of();
        String rel = fileNodes.getFirst().filePath();

        var edges = new ArrayList<Edge>();
        var dedupe = new HashSet<String>();
        for (SchemaObject obj : objects.values()) {
            if (!obj.definingFile.equals(rel)) continue;
            String fromId = nodeId(obj.name);
            for (String target : obj.fkTargets) {
                String resolved = resolve(target);
                if (resolved == null) continue;
                addEdge(edges, dedupe, EdgeType.REFERENCES_FK, fromId, nodeId(resolved));
            }
            for (String target : obj.derivesFrom) {
                String resolved = resolve(target);
                if (resolved == null) continue;
                addEdge(edges, dedupe, EdgeType.DERIVES_FROM, fromId, nodeId(resolved));
            }
        }
        return edges;
    }

    // ---- Statement handling ----

    private void processStatement(String content, String blanked, int start, int end, String rel, long lastModified) {
        String stmt = blanked.substring(start, end);

        Matcher cm = CREATE_PATTERN.matcher(stmt);
        if (cm.find()) {
            handleCreate(cm, stmt, content, start, rel, lastModified);
            return;
        }
        Matcher dm = DROP_PATTERN.matcher(stmt);
        if (dm.find()) {
            handleDrop(dm);
            return;
        }
        Matcher rm = RENAME_PATTERN.matcher(stmt);
        if (rm.find()) {
            handleRename(rm);
            return;
        }
        Matcher fm = ALTER_FK_PATTERN.matcher(stmt);
        if (fm.find()) {
            handleAlterFk(fm);
        }
    }

    private void handleCreate(Matcher cm, String stmt, String content, int stmtStart, String rel, long lastModified) {
        String kindRaw = cm.group(2).replaceAll("\\s+", " ").toUpperCase();
        NodeType kind = switch (kindRaw) {
            case "TABLE" -> NodeType.SQL_TABLE;
            case "VIEW", "MATERIALIZED VIEW" -> NodeType.SQL_VIEW;
            case "FUNCTION", "PROCEDURE" -> NodeType.SQL_ROUTINE;
            default -> null;
        };
        if (kind == null) return;

        String[] parsed = parseName(cm.group(3));
        String bare = parsed[0];
        String qualified = parsed[1];

        int absOffset = stmtStart + cm.start();
        var obj = new SchemaObject();
        obj.kind = kind;
        obj.name = bare;
        obj.qualifiedName = qualified;
        obj.definingFile = rel;
        obj.definingLine = lineAt(content, absOffset);
        obj.snippet = extractLine(content, absOffset);
        obj.lastModified = lastModified;

        String body = stmt.substring(cm.end());
        if (kind == NodeType.SQL_TABLE) {
            Matcher refM = INLINE_REFERENCES.matcher(body);
            while (refM.find()) {
                obj.fkTargets.add(parseName(refM.group(1))[0]);
            }
        } else {
            Matcher asM = AS_KEYWORD.matcher(body);
            String scanFrom = asM.find() ? body.substring(asM.end()) : body;
            Matcher fjM = FROM_JOIN.matcher(scanFrom);
            while (fjM.find()) {
                obj.derivesFrom.add(parseName(fjM.group(1))[0]);
            }
        }

        aliases.remove(bare);
        objects.put(bare, obj);
    }

    private void handleDrop(Matcher dm) {
        String bare = parseName(dm.group(2))[0];
        objects.remove(bare);
    }

    private void handleRename(Matcher rm) {
        String fromBare = parseName(rm.group(1))[0];
        String[] toParsed = parseName(rm.group(2));
        String toBare = toParsed[0];
        String toQualified = toParsed[1];

        SchemaObject obj = objects.remove(fromBare);
        if (obj != null) {
            obj.name = toBare;
            obj.qualifiedName = toQualified;
            objects.put(toBare, obj);
        }
        aliases.put(fromBare, toBare);
    }

    private void handleAlterFk(Matcher fm) {
        String tableBare = parseName(fm.group(1))[0];
        String targetBare = parseName(fm.group(2))[0];

        SchemaObject obj = objects.get(tableBare);
        if (obj == null) {
            String resolved = resolve(tableBare);
            if (resolved != null) obj = objects.get(resolved);
        }
        if (obj != null) obj.fkTargets.add(targetBare);
    }

    /** Follows the rename chain only when {@code bareName} is not itself a live object today. */
    private String resolve(String bareName) {
        String key = bareName;
        var seen = new HashSet<String>();
        while (!objects.containsKey(key) && aliases.containsKey(key) && seen.add(key)) {
            key = aliases.get(key);
        }
        return objects.containsKey(key) ? key : null;
    }

    private static String nodeId(String bareName) {
        return "sql:" + bareName;
    }

    private void addEdge(List<Edge> edges, Set<String> dedupe, EdgeType type, String from, String to) {
        if (from.equals(to)) return;
        if (!dedupe.add(type + "|" + from + "|" + to)) return;
        edges.add(new Edge(UUID.randomUUID().toString(), type, from, to));
    }

    // ---- Naming ----

    /** {name, qualifiedName} - both lowercased, quotes stripped; name is the bare (unqualified) part. */
    private static String[] parseName(String raw) {
        String cleaned = raw.replace("\"", "").toLowerCase();
        int dot = cleaned.lastIndexOf('.');
        String bare = dot >= 0 ? cleaned.substring(dot + 1) : cleaned;
        return new String[] {bare, cleaned};
    }

    // ---- Flyway ordering ----

    private List<Path> orderFiles(List<Path> files) {
        var versioned = new ArrayList<Path>();
        var rest = new ArrayList<Path>();
        for (Path f : files) {
            if (FLYWAY_VERSIONED.matcher(f.getFileName().toString()).find()) versioned.add(f);
            else rest.add(f);
        }
        versioned.sort((a, b) -> compareVersions(flywayVersionKey(a), flywayVersionKey(b)));
        rest.sort(Comparator.comparing(Path::toString));
        var out = new ArrayList<Path>(versioned);
        out.addAll(rest);
        return out;
    }

    private static List<Long> flywayVersionKey(Path f) {
        Matcher m = FLYWAY_VERSIONED.matcher(f.getFileName().toString());
        if (!m.find()) return List.of();
        var nums = new ArrayList<Long>();
        for (String part : m.group(1).split("[._]")) {
            nums.add(Long.parseLong(part));
        }
        return nums;
    }

    private static int compareVersions(List<Long> a, List<Long> b) {
        int len = Math.max(a.size(), b.size());
        for (int i = 0; i < len; i++) {
            long av = i < a.size() ? a.get(i) : 0;
            long bv = i < b.size() ? b.get(i) : 0;
            int c = Long.compare(av, bv);
            if (c != 0) return c;
        }
        return 0;
    }

    // ---- Statement splitting: top-level ';' outside quotes/dollar-quotes; comments blanked first ----

    /** Blanks {@code --} and {@code /* *}{@code /} comments outside single-quoted strings, preserving length. */
    private static String blankComments(String s) {
        var sb = new StringBuilder(s);
        int n = s.length();
        int i = 0;
        boolean inSingle = false;
        while (i < n) {
            char c = s.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    if (i + 1 < n && s.charAt(i + 1) == '\'') { i += 2; continue; }
                    inSingle = false;
                }
                i++;
                continue;
            }
            if (c == '\'') { inSingle = true; i++; continue; }
            if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
                int nl = s.indexOf('\n', i);
                int stop = nl < 0 ? n : nl;
                blank(sb, i, stop);
                i = stop;
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int close = s.indexOf("*/", i + 2);
                int stop = close < 0 ? n : close + 2;
                blank(sb, i, stop);
                i = stop;
                continue;
            }
            i++;
        }
        return sb.toString();
    }

    private static void blank(StringBuilder sb, int from, int to) {
        for (int j = from; j < to; j++) {
            if (sb.charAt(j) != '\n') sb.setCharAt(j, ' ');
        }
    }

    /** Top-level {@code ;} boundaries, respecting single-quoted strings and {@code $$}/{@code $tag$} bodies. */
    private static List<int[]> splitStatements(String s) {
        var out = new ArrayList<int[]>();
        int n = s.length();
        int start = 0;
        int i = 0;
        boolean inSingle = false;
        String dollarTag = null;
        while (i < n) {
            char c = s.charAt(i);
            if (dollarTag != null) {
                if (s.startsWith(dollarTag, i)) {
                    i += dollarTag.length();
                    dollarTag = null;
                    continue;
                }
                i++;
                continue;
            }
            if (inSingle) {
                if (c == '\'') {
                    if (i + 1 < n && s.charAt(i + 1) == '\'') { i += 2; continue; }
                    inSingle = false;
                }
                i++;
                continue;
            }
            if (c == '\'') { inSingle = true; i++; continue; }
            if (c == '$') {
                Matcher m = DOLLAR_TAG.matcher(s);
                m.region(i, n);
                if (m.lookingAt()) {
                    dollarTag = m.group();
                    i += dollarTag.length();
                    continue;
                }
            }
            if (c == ';') {
                if (i > start) out.add(new int[] {start, i});
                start = i + 1;
                i++;
                continue;
            }
            i++;
        }
        if (start < n) out.add(new int[] {start, n});
        return out;
    }

    // ---- Line utilities ----

    private static int lineAt(String content, int charIndex) {
        int line = 1;
        for (int i = 0; i < charIndex && i < content.length(); i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }

    private static String extractLine(String content, int charIndex) {
        int start = content.lastIndexOf('\n', charIndex);
        start = start < 0 ? 0 : start + 1;
        int end = content.indexOf('\n', charIndex);
        if (end < 0) end = content.length();
        return content.substring(start, end).trim();
    }
}
