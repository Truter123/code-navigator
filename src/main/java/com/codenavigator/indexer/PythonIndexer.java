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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex/indent-based {@link LanguageIndexer} for Python, mirroring {@link ShellIndexer}'s
 * approach: file-content scanned, no Python grammar. One {@link NodeType#PY_MODULE} node per
 * file, one {@link NodeType#PY_CLASS} node per column-0 class, one {@link NodeType#METHOD} node
 * per module-level function or direct class method, and {@code IMPORTS}/{@code CALLS}/{@code
 * EXTENDS}/{@code DECLARES_METHOD} edges. {@link #prepare} runs once per run over every Python
 * file to build the cross-file knowledge (module qualified names and their declared top-level
 * function names) that import/call resolution needs; everything else is scanned fresh per file,
 * like {@link ShellIndexer}.
 */
public class PythonIndexer implements LanguageIndexer {

    private static final Set<String> EXCLUDED_SEGMENTS = Set.of(
        "venv", ".venv", "env", "__pycache__", ".tox", "site-packages", "build", "dist",
        "node_modules", "navigators", ".gradle");

    private static final Pattern CLASS_DECL = Pattern.compile(
        "^class\\s+(\\w+)\\s*(?:\\(([^)]*)\\))?\\s*:", Pattern.MULTILINE);

    private static final Pattern DEF_DECL = Pattern.compile(
        "^([ \\t]*)(?:async\\s+)?def\\s+(\\w+)\\s*\\(", Pattern.MULTILINE);

    private static final Pattern IMPORT_STMT = Pattern.compile("^import\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern FROM_IMPORT = Pattern.compile(
        "^from\\s+(\\.*)([\\w.]*)\\s+import\\s+(.+)$", Pattern.MULTILINE);

    private static final Pattern SELF_CALL = Pattern.compile("\\bself\\.(\\w+)\\s*\\(");
    private static final Pattern BARE_CALL = Pattern.compile("(?<![\\w.])(\\w+)\\s*\\(");

    private static final Set<String> BUILTINS = Set.of(
        "print", "len", "range", "isinstance", "str", "int", "list", "dict", "set", "super",
        "open", "type", "enumerate", "zip", "sorted", "min", "max", "sum", "any", "all",
        "getattr", "hasattr", "setattr", "iter", "next", "repr", "format", "tuple", "bool",
        "float", "bytes", "id", "hash", "map", "filter", "vars", "dir", "round", "abs", "input",
        "object", "Exception", "ValueError", "TypeError", "RuntimeError", "KeyError");

    /** Raw literal text collected during a file scan; step 4 (cross-language table edges) consumes it. */
    public record StringLiteral(String ownerNodeId, String text) {}

    // Whole-run state, rebuilt fresh by every prepare().
    private Map<String, String> qualifiedByPath = new LinkedHashMap<>();     // relative path -> qualified name
    private Map<String, String> pathByQualified = new LinkedHashMap<>();     // qualified name -> relative path
    private Map<String, Set<String>> moduleFunctions = new LinkedHashMap<>(); // qualified name -> module-level fn names

    private final List<StringLiteral> literals = new ArrayList<>();

    /** Package-private: nothing consumes this today, step 4 will. */
    List<StringLiteral> stringLiterals() {
        return literals;
    }

    @Override
    public boolean matches(Path file) {
        if (!file.toString().endsWith(".py")) return false;
        for (Path part : file) {
            String seg = part.toString();
            if (EXCLUDED_SEGMENTS.contains(seg)) return false;
            if (seg.endsWith(".egg-info")) return false;
        }
        return true;
    }

    @Override
    public Set<NodeType> ownedTypes() {
        return Set.of(NodeType.PY_MODULE, NodeType.PY_CLASS);
    }

    @Override
    public boolean rebuildAllOnChange() {
        return false;
    }

    @Override
    public void prepare(List<Path> files, Path projectRoot) {
        qualifiedByPath = new LinkedHashMap<>();
        pathByQualified = new LinkedHashMap<>();
        moduleFunctions = new LinkedHashMap<>();
        literals.clear();

        for (Path file : files) {
            String rel = projectRoot.relativize(file).toString();
            String qualified = qualifiedName(file, projectRoot);
            qualifiedByPath.put(rel, qualified);
            pathByQualified.put(qualified, rel);

            String content;
            try {
                content = Files.readString(file);
            } catch (IOException e) {
                continue;
            }
            String stripped = stripPython(content).stripped();
            var moduleDefNames = new LinkedHashSet<String>();
            Matcher dm = DEF_DECL.matcher(stripped);
            while (dm.find()) {
                if (dm.group(1).length() == 0) moduleDefNames.add(dm.group(2));
            }
            moduleFunctions.put(qualified, moduleDefNames);
        }
    }

    @Override
    public List<Node> indexFile(Path file, Path projectRoot) {
        var nodes = new ArrayList<Node>();
        String content;
        long lastModified;
        try {
            content = Files.readString(file);
            lastModified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return nodes;
        }

        String rel = projectRoot.relativize(file).toString();
        String moduleId = rel;
        String qualified = qualifiedByPath.getOrDefault(rel, qualifiedName(file, projectRoot));
        boolean isInit = file.getFileName().toString().equals("__init__.py");
        String moduleName = isInit ? parentDirName(file) : baseName(file);

        var strip = stripPython(content);
        String stripped = strip.stripped();

        nodes.add(new Node(moduleId, NodeType.PY_MODULE, moduleName, qualified, rel, 1,
            moduleSnippet(content, stripped), lastModified));

        for (ClassMatch cls : findClasses(stripped)) {
            String classId = classId(rel, cls.name());
            int line = lineAt(content, cls.headerStart());
            nodes.add(new Node(classId, NodeType.PY_CLASS, cls.name(), classId, rel, line,
                lineText(content, cls.headerStart()), lastModified));
        }

        return nodes;
    }

    @Override
    public List<Node> extractMethods(Path file, List<Node> fileNodes) {
        Node module = moduleNode(fileNodes);
        if (module == null) return List.of();

        String content;
        long lastModified;
        try {
            content = Files.readString(file);
            lastModified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return List.of();
        }

        String stripped = stripPython(content).stripped();
        var classes = findClasses(stripped);
        var results = new ArrayList<Node>();
        var claimed = new HashSet<String>();

        for (DefMatch def : allDefs(stripped)) {
            ClassMatch owner = enclosingDirectMethodClass(def, classes);
            String declaringId;
            if (def.indent() == 0) {
                declaringId = module.id();
            } else if (owner != null) {
                declaringId = classNodeId(fileNodes, owner.name());
                if (declaringId == null) declaringId = classId(module.filePath(), owner.name());
            } else {
                continue; // nested function / method: not a direct member
            }

            String id = MethodIds.of(declaringId, def.name(), List.of());
            if (!claimed.add(id)) continue;
            int line = lineAt(content, def.headerStart());
            results.add(new Node(id, NodeType.METHOD, def.name(), id, module.filePath(),
                line, lineText(content, def.headerStart()), lastModified));
        }
        return results;
    }

    @Override
    public List<Edge> extractEdges(Path file, List<Node> fileNodes, KnownNames known) {
        Node module = moduleNode(fileNodes);
        if (module == null) return List.of();

        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            return List.of();
        }

        var strip = stripPython(content);
        String stripped = strip.stripped();
        var classes = findClasses(stripped);
        var defs = allDefs(stripped);

        var edges = new ArrayList<Edge>();
        var dedupe = new HashSet<String>();

        // Class -> module ids by name, and def -> declaring id / node id.
        var classIdByName = new LinkedHashMap<String, String>();
        for (ClassMatch cls : classes) {
            String id = classNodeId(fileNodes, cls.name());
            if (id == null) id = classId(module.filePath(), cls.name());
            classIdByName.put(cls.name(), id);
        }

        record ResolvedDef(DefMatch def, String declaringId, String methodId, ClassMatch owner) {}
        var resolvedDefs = new ArrayList<ResolvedDef>();
        for (DefMatch def : defs) {
            ClassMatch owner = enclosingDirectMethodClass(def, classes);
            String declaringId;
            if (def.indent() == 0) {
                declaringId = module.id();
            } else if (owner != null) {
                declaringId = classIdByName.get(owner.name());
            } else {
                continue;
            }
            resolvedDefs.add(new ResolvedDef(def, declaringId, MethodIds.of(declaringId, def.name(), List.of()), owner));
        }

        // DECLARES_METHOD
        for (ResolvedDef rd : resolvedDefs) {
            addEdge(edges, dedupe, EdgeType.DECLARES_METHOD, rd.declaringId(), rd.methodId());
        }

        // EXTENDS: same-file base classes only.
        for (ClassMatch cls : classes) {
            for (String base : cls.bases()) {
                if (base.equals(cls.name())) continue;
                String baseId = classIdByName.get(base);
                if (baseId == null) continue;
                addEdge(edges, dedupe, EdgeType.EXTENDS, classIdByName.get(cls.name()), baseId);
            }
        }

        // Imports: absolute `import x[, y as z]` and `from (.*)x import ...`.
        String currentPackage = packageOf(module.qualifiedName(), isInit(file));
        Matcher im = IMPORT_STMT.matcher(stripped);
        while (im.find()) {
            for (String item : im.group(1).split(",")) {
                String target = item.trim();
                if (target.isEmpty()) continue;
                target = target.split("\\bas\\b")[0].trim();
                String targetPath = pathByQualified.get(target);
                if (targetPath != null) {
                    addEdge(edges, dedupe, EdgeType.IMPORTS, module.id(), targetPath);
                }
            }
        }

        var fromBindings = new LinkedHashMap<String, ImportBinding>(); // usedName -> binding
        Matcher fm = FROM_IMPORT.matcher(stripped);
        while (fm.find()) {
            String dots = fm.group(1);
            String afterDots = fm.group(2);
            String namesRaw = fm.group(3);
            String targetQualified = resolveFromTarget(dots, afterDots, currentPackage);
            if (targetQualified == null) continue;
            String targetPath = pathByQualified.get(targetQualified);
            if (targetPath == null) continue;
            addEdge(edges, dedupe, EdgeType.IMPORTS, module.id(), targetPath);

            if (namesRaw.trim().equals("*")) continue;
            for (String rawName : namesRaw.split(",")) {
                String trimmed = rawName.trim();
                if (trimmed.isEmpty()) continue;
                String[] parts = trimmed.split("\\bas\\b");
                String original = parts[0].trim();
                String used = parts.length > 1 ? parts[1].trim() : original;
                if (original.isEmpty() || used.isEmpty()) continue;
                fromBindings.put(used, new ImportBinding(targetQualified, targetPath, original));
            }
        }

        // Local module-level function names (for CALLS resolution / builtin shadowing).
        var localModuleDefNames = new LinkedHashSet<String>();
        for (ResolvedDef rd : resolvedDefs) {
            if (rd.declaringId().equals(module.id())) localModuleDefNames.add(rd.def().name());
        }

        // CALLS
        for (ResolvedDef rd : resolvedDefs) {
            String body = bodyText(stripped, rd.def());

            if (rd.owner() != null) {
                Matcher sm = SELF_CALL.matcher(body);
                var siblingMethods = new LinkedHashSet<String>();
                for (ResolvedDef other : resolvedDefs) {
                    if (rd.declaringId().equals(other.declaringId())) siblingMethods.add(other.def().name());
                }
                while (sm.find()) {
                    String calleeName = sm.group(1);
                    if (!siblingMethods.contains(calleeName)) continue;
                    String calleeId = MethodIds.of(rd.declaringId(), calleeName, List.of());
                    addEdge(edges, dedupe, EdgeType.CALLS, rd.methodId(), calleeId);
                }
            }

            Matcher bm = BARE_CALL.matcher(body);
            while (bm.find()) {
                String name = bm.group(1);
                if (localModuleDefNames.contains(name)) {
                    if (name.equals(rd.def().name()) && rd.declaringId().equals(module.id())) continue;
                    addEdge(edges, dedupe, EdgeType.CALLS, rd.methodId(), MethodIds.of(module.id(), name, List.of()));
                    continue;
                }
                ImportBinding binding = fromBindings.get(name);
                if (binding != null) {
                    Set<String> declared = moduleFunctions.get(binding.targetQualified());
                    if (declared != null && declared.contains(binding.originalName())) {
                        addEdge(edges, dedupe, EdgeType.CALLS, rd.methodId(),
                            MethodIds.of(binding.targetPath(), binding.originalName(), List.of()));
                    }
                    continue;
                }
                if (BUILTINS.contains(name)) continue; // not shadowed by a same-file def
            }
        }

        return edges;
    }

    private record ImportBinding(String targetQualified, String targetPath, String originalName) {}

    // ---- Import target resolution ----

    private String resolveFromTarget(String dots, String afterDots, String currentPackage) {
        String target;
        if (dots.isEmpty()) {
            target = afterDots;
        } else {
            String pkg = currentPackage;
            for (int i = 1; i < dots.length(); i++) {
                pkg = parentQualified(pkg);
            }
            target = afterDots.isEmpty() ? pkg : (pkg.isEmpty() ? afterDots : pkg + "." + afterDots);
        }
        return target.isEmpty() ? null : target;
    }

    private static String parentQualified(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? "" : qualified.substring(0, dot);
    }

    private static String packageOf(String moduleQualified, boolean isInit) {
        return isInit ? moduleQualified : parentQualified(moduleQualified);
    }

    private boolean isInit(Path file) {
        return file.getFileName().toString().equals("__init__.py");
    }

    // ---- Node lookups ----

    private Node moduleNode(List<Node> fileNodes) {
        return fileNodes.stream().filter(n -> n.type() == NodeType.PY_MODULE).findFirst().orElse(null);
    }

    private String classNodeId(List<Node> fileNodes, String className) {
        return fileNodes.stream()
            .filter(n -> n.type() == NodeType.PY_CLASS && n.name().equals(className))
            .map(Node::id).findFirst().orElse(null);
    }

    private static String classId(String filePath, String className) {
        return filePath + "::" + className;
    }

    // ---- Qualified name / module naming ----

    private static String qualifiedName(Path file, Path projectRoot) {
        boolean isInit = file.getFileName().toString().equals("__init__.py");
        var parts = new java.util.ArrayDeque<String>();
        if (!isInit) parts.addLast(baseName(file));

        Path dir = file.getParent();
        while (dir != null && dir.startsWith(projectRoot) && !dir.equals(projectRoot)) {
            if (Files.exists(dir.resolve("__init__.py"))) {
                parts.addFirst(dir.getFileName().toString());
                dir = dir.getParent();
            } else {
                break;
            }
        }
        return String.join(".", parts);
    }

    private static String baseName(Path file) {
        String fn = file.getFileName().toString();
        int dot = fn.lastIndexOf('.');
        return dot > 0 ? fn.substring(0, dot) : fn;
    }

    private static String parentDirName(Path file) {
        Path parent = file.getParent();
        return parent == null ? "" : parent.getFileName().toString();
    }

    // ---- Class / def structural parsing ----

    private record ClassMatch(String name, int headerStart, int blockEnd, int bodyIndent, List<String> bases) {}
    private record DefMatch(String name, int indent, int headerStart, int blockEnd) {}

    private List<ClassMatch> findClasses(String stripped) {
        var out = new ArrayList<ClassMatch>();
        Matcher m = CLASS_DECL.matcher(stripped);
        while (m.find()) {
            int headerStart = m.start();
            int blockEnd = blockEndOffset(stripped, headerStart, 0);
            int bodyIndent = firstBodyIndent(stripped, headerStart, blockEnd);
            out.add(new ClassMatch(m.group(1), headerStart, blockEnd, bodyIndent, splitBases(m.group(2))));
        }
        return out;
    }

    private List<DefMatch> allDefs(String stripped) {
        var out = new ArrayList<DefMatch>();
        Matcher m = DEF_DECL.matcher(stripped);
        while (m.find()) {
            int indent = m.group(1).length();
            int headerStart = m.start();
            int blockEnd = blockEndOffset(stripped, headerStart, indent);
            out.add(new DefMatch(m.group(2), indent, headerStart, blockEnd));
        }
        return out;
    }

    /** The class whose body directly owns {@code def} (indent matches the class's own body indent). */
    private ClassMatch enclosingDirectMethodClass(DefMatch def, List<ClassMatch> classes) {
        if (def.indent() == 0) return null;
        for (ClassMatch cls : classes) {
            if (def.headerStart() > cls.headerStart() && def.headerStart() < cls.blockEnd()
                && def.indent() == cls.bodyIndent()) {
                return cls;
            }
        }
        return null;
    }

    private List<String> splitBases(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        var out = new ArrayList<String>();
        for (String token : raw.split(",")) {
            String t = token.trim();
            if (t.isEmpty() || t.contains("=")) continue; // keyword arg like metaclass=X
            out.add(t);
        }
        return out;
    }

    private String bodyText(String stripped, DefMatch def) {
        int bodyStart = nextLineStart(stripped, def.headerStart());
        int end = Math.max(bodyStart, def.blockEnd());
        return stripped.substring(bodyStart, Math.min(end, stripped.length()));
    }

    // ---- Indent / line utilities ----

    private static int nextLineStart(String s, int offset) {
        int nl = s.indexOf('\n', offset);
        return nl < 0 ? s.length() : nl + 1;
    }

    private static int indentAt(String s, int lineStart) {
        int i = lineStart, n = s.length(), count = 0;
        while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
            count++;
        }
        return count;
    }

    private static int blockEndOffset(String s, int headerLineStart, int headerIndent) {
        int pos = nextLineStart(s, headerLineStart);
        while (pos < s.length()) {
            int end = nextLineStart(s, pos);
            int lineTextEnd = (end > 0 && end <= s.length() && end - 1 < s.length() && s.charAt(end - 1) == '\n')
                ? end - 1 : end;
            String text = s.substring(pos, Math.min(lineTextEnd, s.length()));
            if (!text.isBlank()) {
                if (indentAt(s, pos) <= headerIndent) return pos;
            }
            if (end == pos) break; // no progress guard
            pos = end;
        }
        return s.length();
    }

    private static int firstBodyIndent(String s, int headerLineStart, int blockEnd) {
        int pos = nextLineStart(s, headerLineStart);
        while (pos < blockEnd) {
            int end = nextLineStart(s, pos);
            int lineTextEnd = (end > 0 && end - 1 < s.length() && s.charAt(end - 1) == '\n') ? end - 1 : end;
            String text = s.substring(pos, Math.min(lineTextEnd, blockEnd));
            if (!text.isBlank()) return indentAt(s, pos);
            if (end == pos) break;
            pos = end;
        }
        return -1;
    }

    private static int lineAt(String content, int charIndex) {
        int line = 1;
        for (int i = 0; i < charIndex && i < content.length(); i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }

    private static String lineText(String content, int charIndex) {
        int start = content.lastIndexOf('\n', charIndex);
        start = start < 0 ? 0 : start + 1;
        int end = content.indexOf('\n', charIndex);
        if (end < 0) end = content.length();
        return content.substring(start, end).trim();
    }

    private static final Pattern DEF_OR_CLASS_LINE = Pattern.compile(
        "^(import\\s|from\\s|def\\s|async\\s+def\\s|class\\s).*$", Pattern.MULTILINE);

    private String moduleSnippet(String content, String stripped) {
        String docstring = moduleDocstringFirstLine(content, stripped);
        if (docstring != null) return docstring;
        Matcher m = DEF_OR_CLASS_LINE.matcher(stripped);
        if (m.find()) return lineText(content, m.start());
        return "";
    }

    private String moduleDocstringFirstLine(String content, String stripped) {
        int i = 0;
        int n = stripped.length();
        while (i < n) {
            int lineStart = i;
            int lineEnd = nextLineStart(stripped, i);
            String text = stripped.substring(lineStart, Math.min(lineEnd, n)).trim();
            if (text.isEmpty()) {
                i = lineEnd;
                continue;
            }
            // First non-blank line: is it a bare string literal (a docstring)?
            char c = content.length() > lineStart ? firstNonSpace(content, lineStart) : 0;
            if (c == '"' || c == '\'' || isStringPrefixStart(content, lineStart)) {
                String raw = content.substring(lineStart, Math.min(lineEnd, content.length())).trim();
                String inner = stripQuotes(raw);
                String firstLine = inner.lines().findFirst().orElse("").trim();
                return firstLine;
            }
            return null;
        }
        return null;
    }

    private static char firstNonSpace(String s, int lineStart) {
        int i = lineStart;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        return i < s.length() ? s.charAt(i) : 0;
    }

    private static boolean isStringPrefixStart(String s, int lineStart) {
        int i = lineStart;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        int len = stringPrefixLength(s, i);
        return len > 0;
    }

    private static String stripQuotes(String raw) {
        String s = raw;
        int i = 0;
        while (i < s.length() && Character.isLetter(s.charAt(i))) i++;
        s = s.substring(i);
        for (String q : List.of("\"\"\"", "'''", "\"", "'")) {
            if (s.startsWith(q)) {
                String body = s.substring(q.length());
                if (body.endsWith(q)) body = body.substring(0, body.length() - q.length());
                return body;
            }
        }
        return s;
    }

    // ---- Comment / string stripping, with raw literal collection ----

    private record StripOutcome(String stripped, List<int[]> literalRanges) {}

    private StripOutcome stripPython(String content) {
        var sb = new StringBuilder(content.length());
        var ranges = new ArrayList<int[]>();
        int i = 0, n = content.length();
        while (i < n) {
            char c = content.charAt(i);
            if (c == '#') {
                int end = content.indexOf('\n', i);
                int stop = end < 0 ? n : end;
                sb.append(" ".repeat(stop - i));
                i = stop;
                continue;
            }
            int prefixLen = stringPrefixLength(content, i);
            int qPos = i + prefixLen;
            if (qPos < n && (content.charAt(qPos) == '\'' || content.charAt(qPos) == '"')) {
                char q = content.charAt(qPos);
                boolean triple = qPos + 2 < n && content.charAt(qPos + 1) == q && content.charAt(qPos + 2) == q;
                int openLen = triple ? 3 : 1;
                int bodyStart = qPos + openLen;
                int bodyEnd;
                int closeLen;
                if (triple) {
                    String close = "" + q + q + q;
                    int idx = content.indexOf(close, bodyStart);
                    bodyEnd = idx < 0 ? n : idx;
                    closeLen = idx < 0 ? 0 : 3;
                } else {
                    int idx = findSingleQuoteEnd(content, bodyStart, q);
                    bodyEnd = idx;
                    closeLen = (idx < n && content.charAt(idx) == q) ? 1 : 0;
                }
                int literalEnd = bodyEnd + closeLen;
                sb.append(content, i, bodyStart);
                for (int k = bodyStart; k < bodyEnd; k++) {
                    sb.append(content.charAt(k) == '\n' ? '\n' : ' ');
                }
                sb.append(content, bodyEnd, Math.min(literalEnd, n));
                ranges.add(new int[] {i, literalEnd});
                literals.add(new StringLiteral("", content.substring(i, Math.min(literalEnd, n))));
                i = literalEnd;
                continue;
            }
            sb.append(c);
            i++;
        }
        return new StripOutcome(sb.toString(), ranges);
    }

    private static int findSingleQuoteEnd(String s, int start, char q) {
        int i = start, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (c == '\n') return i;
            if (c == q) return i;
            i++;
        }
        return n;
    }

    private static int stringPrefixLength(String s, int pos) {
        int n = s.length();
        int len = 0;
        while (len < 2 && pos + len < n && isPrefixChar(s.charAt(pos + len))) len++;
        if (pos + len < n && (s.charAt(pos + len) == '\'' || s.charAt(pos + len) == '"')) return len;
        return 0;
    }

    private static boolean isPrefixChar(char c) {
        char l = Character.toLowerCase(c);
        return l == 'r' || l == 'b' || l == 'f' || l == 'u';
    }

    // ---- Edge helper ----

    private void addEdge(List<Edge> edges, Set<String> dedupe, EdgeType type, String from, String to) {
        if (from == null || to == null || from.equals(to)) return;
        if (!dedupe.add(type + "|" + from + "|" + to)) return;
        edges.add(new Edge(UUID.randomUUID().toString(), type, from, to));
    }
}
