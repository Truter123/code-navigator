package com.codenavigator.mcp;

import com.codenavigator.graph.*;
import com.codenavigator.search.SearchService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 6,000-character reply cap. A list tool asked for more rows than fit used to be cut by the cap
 * itself, mid-list, with a note that named parameters the tool did not have. It now lowers its own
 * {@code limit} until the list ends on its own footer, and a closing line says so.
 */
class ReplyCapTest {

    private static final int CAP = 6_000;

    @TempDir Path tempDir;
    private GraphStore store;
    private CodeNavigatorMcpServer server;

    @BeforeEach
    void setUp() {
        store = new GraphStore(tempDir.resolve("cap.db"));
        var traversal = new GraphTraversal(store);
        server = new CodeNavigatorMcpServer(store, traversal, new SearchService(store, traversal));
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    /** {@code pairs} long-named package pairs that call each other: 2 x pairs packages, pairs cycles. */
    private void addCycles(int pairs) {
        String prefix = "org.example.platform.subsystem.feature";
        for (int i = 1; i <= pairs; i++) {
            String a = prefix + i + ".alpha.A" + i;
            String b = prefix + i + ".beta.B" + i;
            store.saveNode(new Node(a, NodeType.SERVICE, "A" + i, a, "a/A" + i + ".java", 1, "", 0));
            store.saveNode(new Node(b, NodeType.SERVICE, "B" + i, b, "b/B" + i + ".java", 1, "", 0));
            store.saveEdge(new Edge("ca" + i, EdgeType.CALLS_METHOD, a, b));
            store.saveEdge(new Edge("cb" + i, EdgeType.CALLS_METHOD, b, a));
        }
    }

    /** {@code count} unreferenced services, all matching the query {@code Widget}. */
    private void addWidgets(int count) {
        for (int i = 1; i <= count; i++) {
            String id = "dead.Widget" + i;
            store.saveNode(new Node(id, NodeType.SERVICE, "Widget" + i, id, "dead/Widget" + i + ".java", 1, "", 0));
        }
    }

    @Test
    void packagesStayUnderTheCapAtAnyLimitAndEndOnTheListsOwnFooter() {
        addCycles(150);

        for (int limit : new int[] {10, 30, 100, 400}) {
            var reply = server.reply("cg_health", Map.of("kind", "packages", "limit", limit));

            assertThat(reply.length()).as("limit %d", limit).isLessThanOrEqualTo(CAP);
            assertThat(reply).as("limit %d", limit)
                .doesNotContain("more line(s) not shown")
                .contains("more. Raise `limit`");
        }
        var lowered = server.reply("cg_health", Map.of("kind", "packages", "limit", 400));
        assertThat(lowered).contains("`limit` lowered from 400 to ").contains("Narrow with `package`");
    }

    @Test
    void aReplyThatFitsIsTheHandlersOwn() {
        addCycles(3);
        Map<String, Object> args = Map.of("kind", "packages");

        assertThat(server.reply("cg_health", args)).isEqualTo(server.handleCgHealth(args));
    }

    @Test
    void deadCodeAndSearchLowerALimitThatWouldNotFit() {
        addWidgets(400);

        assertLowered("cg_health", Map.of("kind", "dead", "limit", 400));
        assertLowered("cg_search", Map.of("query", "Widget", "limit", 400));
    }

    private void assertLowered(String tool, Map<String, Object> args) {
        var reply = server.reply(tool, args);

        assertThat(reply.length()).as(tool).isLessThanOrEqualTo(CAP);
        assertThat(reply).as(tool)
            .doesNotContain("more line(s) not shown")
            .contains("`limit` lowered from 400 to ");
    }

    @Test
    void aLimitWellAboveTheRowsThatExistStillEndsOnTheListsOwnFooter() {
        addWidgets(170);
        Map<String, Object> asked = Map.of("query", "Widget", "limit", 1000);
        // The list is complete at 170 rows and a little over the cap, so a limit shrunk by a ratio
        // changes nothing until it drops below 170, which is where the old loop ran out of passes.
        assertThat(server.handleCgSearch(asked).length()).isBetween(CAP + 1, CAP + 600);

        var reply = server.reply("cg_search", asked);

        assertThat(reply.length()).isLessThanOrEqualTo(CAP);
        assertThat(reply).doesNotContain("more line(s) not shown")
            .containsPattern("… \\d+ more\\. Raise `limit`")
            .contains("`limit` lowered from 1000 to ").endsWith(")\n");
    }

    @Test
    void theLoweredLimitIsTheLargestOneThatFits() {
        addWidgets(170);
        var lowered = Pattern.compile("lowered from 1000 to (\\d+)")
            .matcher(server.reply("cg_search", Map.of("query", "Widget", "limit", 1000)));
        assertThat(lowered.find()).isTrue();
        int fits = Integer.parseInt(lowered.group(1));

        // 250 is the room the cap keeps free for the "lowered" line.
        assertThat(server.handleCgSearch(Map.of("query", "Widget", "limit", fits)).length())
            .isLessThanOrEqualTo(CAP - 250);
        assertThat(server.handleCgSearch(Map.of("query", "Widget", "limit", fits + 1)).length())
            .isGreaterThan(CAP - 250);
    }

    @Test
    void aToolWithoutLimitIsCutAtALineAndNamesOnlyItsOwnParameters() {
        String big = "line\n".repeat(5_000);

        assertThat(CodeNavigatorMcpServer.truncateOutput(big, "cg_guard"))
            .contains("more line(s) not shown").contains("Narrow with `depth`")
            .doesNotContain("limit").doesNotContain("granularity");
        assertThat(CodeNavigatorMcpServer.truncateOutput(big, "cg_deps"))
            .contains("more line(s) not shown").doesNotContain("Narrow").doesNotContain("`");
        assertThat(CodeNavigatorMcpServer.truncateOutput(big, "cg_related"))
            .contains("Lower `limit`").contains("`granularity`");
    }

    @Test
    @Timeout(30)
    void everyCapNoteNamesOnlyParametersItsToolReallyHas() throws Exception {
        var toServer = new PipedOutputStream();
        var serverIn = new PipedInputStream(toServer, 1 << 16);
        var serverOut = new PipedOutputStream();
        var fromServer = new BufferedReader(new InputStreamReader(
            new PipedInputStream(serverOut, 1 << 17), StandardCharsets.UTF_8));
        var loop = new Thread(() -> server.run(serverIn, serverOut), "run-loop");
        loop.setDaemon(true);
        loop.start();
        send(toServer, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
            + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"test\",\"version\":\"0\"}}}");
        fromServer.readLine(); // the initialize result
        send(toServer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        send(toServer, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        String list = fromServer.readLine();
        toServer.close();
        loop.join(10_000);

        var parameters = parametersByTool(list);
        assertThat(parameters.keySet()).containsExactlyInAnyOrder("cg_map", "cg_search", "cg_node",
            "cg_related", "cg_guard", "cg_context", "cg_health", "cg_deps", "cg_export");
        assertThat(parameters.get("cg_health")).contains("package", "limit", "type");
        for (var tool : parameters.keySet()) {
            String cut = CodeNavigatorMcpServer.truncateOutput("line\n".repeat(5_000), tool);
            var named = new ArrayList<String>();
            var name = Pattern.compile("`(\\w+)`").matcher(cut.substring(cut.indexOf("not shown")));
            while (name.find()) named.add(name.group(1));
            assertThat(parameters.get(tool)).as("%s names %s", tool, named).containsAll(named);
        }
    }

    /** Each tool's schema property names, read off the {@code tools/list} reply. */
    private static Map<String, Set<String>> parametersByTool(String list) {
        var starts = new TreeMap<Integer, String>();
        var tool = Pattern.compile("\"name\":\"(cg_\\w+)\"").matcher(list);
        while (tool.find()) starts.put(tool.start(), tool.group(1));

        var byTool = new HashMap<String, Set<String>>();
        var positions = new ArrayList<>(starts.keySet());
        for (int i = 0; i < positions.size(); i++) {
            int end = i + 1 < positions.size() ? positions.get(i + 1) : list.length();
            String segment = list.substring(positions.get(i), end);
            int from = segment.indexOf('{', segment.indexOf("\"properties\":"));
            int depth = 0;
            int to = from;
            for (; to < segment.length(); to++) {
                char c = segment.charAt(to);
                if (c == '{') depth++;
                if (c == '}' && --depth == 0) break;
            }
            var names = new HashSet<String>();
            var property = Pattern.compile("\"(\\w+)\":\\{[^{}]*\\}").matcher(segment.substring(from + 1, to));
            while (property.find()) names.add(property.group(1));
            byTool.put(starts.get(positions.get(i)), names);
        }
        return byTool;
    }

    private static void send(OutputStream to, String line) throws IOException {
        to.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        to.flush();
    }
}
