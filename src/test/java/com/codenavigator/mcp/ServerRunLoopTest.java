package com.codenavigator.mcp;

import com.codenavigator.graph.*;
import com.codenavigator.search.SearchService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stdio run loop. A client that closes the pipe has to end the process, so the loop must return
 * when its input ends. The timeouts only bound a failure (a loop that never returns); a passing run
 * never waits on them.
 */
class ServerRunLoopTest {

    @TempDir Path tempDir;
    private GraphStore graphStore;
    private GraphTraversal traversal;
    private CodeNavigatorMcpServer server;

    @BeforeEach
    void setUp() {
        graphStore = new GraphStore(tempDir.resolve("graph.db"));
        traversal = new GraphTraversal(graphStore);
        graphStore.saveNode(new Node("com.Order", NodeType.CLASS, "Order",
            "com.Order", "Order.java", 1, "class Order", 0));
        graphStore.saveNode(new Node("com.OrderService", NodeType.SERVICE, "OrderService",
            "com.OrderService", "Service.java", 1, "class OrderService", 0));
        graphStore.saveEdge(new Edge("e1", EdgeType.CALLS_METHOD, "com.OrderService", "com.Order"));
        server = new CodeNavigatorMcpServer(graphStore, traversal, new SearchService(graphStore, traversal));
    }

    @AfterEach
    void tearDown() {
        graphStore.close();
    }

    @Test
    @Timeout(15)
    void returnsWhenTheInputEnds() {
        server.run(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
    }

    @Test
    @Timeout(30)
    void advertisesTheToolTheGuardPointsAtThenReturnsWhenTheClientHangsUp() throws Exception {
        var guard = server.handleCgGuard(Map.of("symbol", "com.Order"));
        var pointer = Pattern.compile("More: (cg_\\w+)\\(").matcher(guard);
        assertThat(pointer.find()).as(guard).isTrue();

        var toServer = new PipedOutputStream();
        var serverIn = new PipedInputStream(toServer, 1 << 16);
        var serverOut = new PipedOutputStream();
        var fromServer = new BufferedReader(new InputStreamReader(
            new PipedInputStream(serverOut, 1 << 16), StandardCharsets.UTF_8));
        var loop = new Thread(() -> server.run(serverIn, serverOut), "run-loop");
        loop.setDaemon(true);
        loop.start();

        send(toServer, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
            + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"test\",\"version\":\"0\"}}}");
        fromServer.readLine(); // the initialize result
        send(toServer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        send(toServer, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");

        assertThat(fromServer.readLine()).contains("\"name\":\"" + pointer.group(1) + "\"");

        toServer.close(); // the client hangs up
        loop.join(10_000);
        assertThat(loop.isAlive()).as("run loop still alive after the client closed its input").isFalse();
    }

    private static void send(OutputStream to, String line) throws IOException {
        to.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        to.flush();
    }
}
