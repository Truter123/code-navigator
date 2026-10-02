package com.codenavigator.mcp;

import com.codenavigator.graph.*;
import com.codenavigator.search.SearchService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code +N (more: cg_related ...)} pointer on a cg_node edge line is a promise: run exactly as
 * printed, the call answers for that line. Each test renders a capped line on a fixture graph, runs
 * the printed pointer through the server and counts what comes back against the line's own total.
 *
 * <p>cg_related has no edge-type filter, so two kinds of pointer are exact (a class's methods, a
 * method's callers) and the rest are the closest walk that crosses the edge: exact on a node whose
 * only such edges are the capped ones, a superset otherwise.
 */
class CgNodePointerTest {

    /** More than the 8 names a line lists, and more than the 10 rows cg_related prints by default. */
    private static final int TOTAL = 12;

    private static final Pattern LINE_TOTAL = Pattern.compile("\\((\\d+)(?:, \\d+ in tests)?\\):");
    private static final Pattern POINTER = Pattern.compile("\\(more: cg_related (.+)\\)$");
    private static final Pattern ARGUMENTS = Pattern.compile("^(.+?)((?: \\w+=\\S+)+)$");
    private static final Pattern HEADER = Pattern.compile("(?m)^(\\d+) (?:affected node\\(s\\)|\\w+ found)");
    private static final Pattern FOOTER = Pattern.compile("(?m)^… (\\d+) more\\.");
    /** A row is {@code File.java:line Name TYPE}, indented under its directory line: name, then type. */
    private static final Pattern ROW = Pattern.compile("(?m)^  \\S+:\\d+ (\\S+) (\\w+)$");

    @TempDir Path tempDir;
    private GraphStore store;
    private CodeNavigatorMcpServer server;
    private int edgeSeq;

    @BeforeEach
    void setUp() {
        store = new GraphStore(tempDir.resolve("test.db"));
        var traversal = new GraphTraversal(store);
        server = new CodeNavigatorMcpServer(store, traversal, new SearchService(store, traversal));
    }

    @AfterEach
    void tearDown() { store.close(); }

    // ---- exact: the pointer returns the line's rows and nothing else ----

    @Test
    void declaresMethodPointerListsTheClassesOwnMethodsAndNothingPastThem() {
        clazz("Hub", NodeType.CLASS);
        clazz("Other", NodeType.CLASS);
        var methods = IntStream.rangeClosed(1, TOTAL).mapToObj(i -> method("Hub", "m" + i)).toList();
        // A call out of the class: a walk that goes one hop further would list it as well.
        edge(EdgeType.CALLS, methods.get(0), method("Other", "run"));

        assertExact(follow("Hub", "out DECLARES_METHOD"), "METHOD", names("m"));
    }

    @Test
    void callsPointerOfAMethodListsItsCallers() {
        clazz("Hub", NodeType.CLASS);
        for (int owner = 0; owner < 3; owner++) clazz("Caller" + owner, NodeType.CLASS);
        String target = method("Hub", "target");
        for (int i = 1; i <= TOTAL; i++) edge(EdgeType.CALLS, method("Caller" + i % 3, "c" + i), target);

        assertExact(follow("target", "in CALLS"), "METHOD", names("c"));
    }

    @Test
    void injectsPointerOfAClassListsTheInjectingClasses() {
        clazz("Service", NodeType.CLASS);
        for (int i = 1; i <= TOTAL; i++) {
            clazz("S" + i, NodeType.CLASS);
            edge(EdgeType.INJECTS, id("S" + i), id("Service"));
        }

        assertExact(follow("Service", "in INJECTS"), "CLASS", names("S"));
    }

    // ---- an edge type the directional walk does not follow: the pointer has to say "both" ----

    @Test
    void emitsEventPointerListsTheEvents() {
        clazz("Emitter", NodeType.AGGREGATE);
        for (int i = 1; i <= TOTAL; i++) {
            clazz("Event" + i, NodeType.DOMAIN_EVENT);
            edge(EdgeType.EMITS_EVENT, id("Emitter"), id("Event" + i));
        }

        assertExact(follow("Emitter", "out EMITS_EVENT"), "DOMAIN_EVENT", names("Event"));
    }

    @Test
    void returnsTypePointerListsTheReturningClasses() {
        clazz("Product", NodeType.CLASS);
        for (int i = 1; i <= TOTAL; i++) {
            clazz("Reader" + i, NodeType.CLASS);
            edge(EdgeType.RETURNS_TYPE, id("Reader" + i), id("Product"));
        }

        assertExact(follow("Product", "in RETURNS_TYPE"), "CLASS", names("Reader"));
    }

    // ---- closest faithful: the walk also names the classes it passes through ----

    @Test
    void callsPointerOutOfAMethodListsTheCalleesAndTheClassesAroundThem() {
        clazz("Hub", NodeType.CLASS);
        clazz("Callee", NodeType.CLASS);
        String run = method("Hub", "run");
        for (int i = 1; i <= TOTAL; i++) edge(EdgeType.CALLS, run, method("Callee", "d" + i));

        var followed = follow("run", "out CALLS");

        // On the way a callee is named with its owner, and the method with its own: those rows are extra.
        var allowed = names("d");
        allowed.addAll(Set.of("Hub", "Callee"));
        assertThat(followed.header()).as("header count vs the line's total").isGreaterThanOrEqualTo(followed.total());
        assertThat(allowed).containsAll(followed.rowNames());
        assertThat(followed.rowNames().size() + followed.footer()).as("listed + footer").isEqualTo(followed.header());
    }

    // ---- helpers ----

    /** What following the pointer printed on cg_node's {@code lineStart} edge line comes back with. */
    private record Followed(int total, int header, List<String> rowTypes, List<String> rowNames, int footer) {}

    private Followed follow(String symbol, String lineStart) {
        String node = server.handleCgNodeTool(Map.of("symbol", symbol));
        String line = node.lines().filter(l -> l.startsWith(lineStart + " (")).findFirst()
            .orElseThrow(() -> new AssertionError("no '" + lineStart + "' line in:\n" + node));
        var total = LINE_TOTAL.matcher(line);
        var pointer = POINTER.matcher(line);
        assertThat(total.find() && pointer.find())
            .as("a capped line carries its total and a pointer: " + line).isTrue();

        String reply = server.handleCgRelated(arguments(pointer.group(1)));

        var header = HEADER.matcher(reply);
        assertThat(header.find()).as("a count header in:\n" + reply).isTrue();
        var footer = FOOTER.matcher(reply);
        var types = new ArrayList<String>();
        var names = new ArrayList<String>();
        var rows = ROW.matcher(reply);
        while (rows.find()) {
            names.add(rows.group(1));
            types.add(rows.group(2));
        }
        return new Followed(Integer.parseInt(total.group(1)), Integer.parseInt(header.group(1)),
            types, names, footer.find() ? Integer.parseInt(footer.group(1)) : 0);
    }

    /** The pointer's {@code symbol key=value ...} as the arguments of the tool call it spells. */
    private static Map<String, Object> arguments(String pointer) {
        var parts = ARGUMENTS.matcher(pointer);
        assertThat(parts.matches()).as("symbol followed by key=value pairs: " + pointer).isTrue();
        var args = new HashMap<String, Object>();
        args.put("symbol", parts.group(1));
        for (String pair : parts.group(2).trim().split(" ")) {
            String[] keyValue = pair.split("=", 2);
            boolean number = keyValue[0].equals("depth") || keyValue[0].equals("limit");
            args.put(keyValue[0], number ? (Object) Integer.parseInt(keyValue[1]) : keyValue[1]);
        }
        return args;
    }

    /** The reply accounts for exactly the line: its count, its kind of row, and rows plus footer. */
    private static void assertExact(Followed followed, String rowType, Set<String> promised) {
        assertThat(followed.header()).as("header count vs the line's total").isEqualTo(followed.total());
        assertThat(followed.rowTypes()).as("row types").isNotEmpty().allMatch(rowType::equals);
        assertThat(promised).as("rows are the line's").containsAll(followed.rowNames());
        assertThat(followed.rowNames().size() + followed.footer()).as("listed + footer").isEqualTo(followed.total());
    }

    private static Set<String> names(String prefix) {
        return IntStream.rangeClosed(1, TOTAL).mapToObj(i -> prefix + i).collect(Collectors.toCollection(HashSet::new));
    }

    private static String id(String simple) { return "com.sample." + simple; }

    private void clazz(String simple, NodeType type) {
        store.saveNode(new Node(id(simple), type, simple, id(simple),
            "src/main/java/com/sample/" + simple + ".java", 1, "class " + simple, 0));
    }

    /** A method of {@code owner}, declared by it, with the id the indexer gives it. */
    private String method(String owner, String name) {
        String id = id(owner) + "#" + name + "()";
        store.saveNode(new Node(id, NodeType.METHOD, name, id,
            "src/main/java/com/sample/" + owner + ".java", 1, "void " + name + "()", 0));
        edge(EdgeType.DECLARES_METHOD, id(owner), id);
        return id;
    }

    private void edge(EdgeType type, String from, String to) {
        store.saveEdge(new Edge("e" + edgeSeq++, type, from, to));
    }
}
