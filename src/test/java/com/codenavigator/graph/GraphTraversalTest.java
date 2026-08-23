package com.codenavigator.graph;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class GraphTraversalTest {

    @TempDir
    Path tempDir;

    private GraphStore store;
    private GraphTraversal traversal;

    // DDD chain nodes
    private static final String CTRL    = "ctrl";
    private static final String CMD     = "cmd";
    private static final String HANDLER = "handler";
    private static final String AGG     = "agg";
    private static final String EVENT   = "event";
    private static final String PROJ    = "proj";
    private static final String VIEW    = "view";

    // Spring chain nodes
    private static final String CTRL2  = "ctrl2";
    private static final String SVC    = "svc";
    private static final String REPO   = "repo";
    private static final String ENTITY = "entity";

    @BeforeEach
    void setUp() {
        store = new GraphStore(tempDir.resolve("test.db"));
        traversal = new GraphTraversal(store);

        // ---- DDD chain ----
        // ctrl → cmd (DISPATCHES_COMMAND)
        // handler → cmd (HANDLES)
        // handler → agg (LOADS_AGGREGATE)
        // agg → event (EMITS_EVENT)
        // proj → event (PROJECTS_EVENT)
        // proj → view (UPDATES_VIEW)

        saveNode(CTRL,    NodeType.CONTROLLER);
        saveNode(CMD,     NodeType.COMMAND);
        saveNode(HANDLER, NodeType.COMMAND_HANDLER);
        saveNode(AGG,     NodeType.AGGREGATE);
        saveNode(EVENT,   NodeType.DOMAIN_EVENT);
        saveNode(PROJ,    NodeType.PROJECTION_HANDLER);
        saveNode(VIEW,    NodeType.VIEW);

        saveEdge("e1", EdgeType.DISPATCHES_COMMAND, CTRL,    CMD);
        saveEdge("e2", EdgeType.HANDLES,            HANDLER, CMD);
        saveEdge("e3", EdgeType.LOADS_AGGREGATE,    HANDLER, AGG);
        saveEdge("e4", EdgeType.EMITS_EVENT,        AGG,     EVENT);
        saveEdge("e5", EdgeType.PROJECTS_EVENT,     PROJ,    EVENT);
        saveEdge("e6", EdgeType.UPDATES_VIEW,       PROJ,    VIEW);

        // ---- Spring chain ----
        // ctrl2 → svc (INJECTS)
        // svc → repo (INJECTS)
        // repo → entity (READS_VIEW)

        saveNode(CTRL2,  NodeType.CONTROLLER);
        saveNode(SVC,    NodeType.SERVICE);
        saveNode(REPO,   NodeType.REPOSITORY);
        saveNode(ENTITY, NodeType.ENTITY);

        saveEdge("e7", EdgeType.INJECTS,    CTRL2, SVC);
        saveEdge("e8", EdgeType.INJECTS,    SVC,   REPO);
        saveEdge("e9", EdgeType.READS_VIEW, REPO,  ENTITY);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void traceChainForwardFromController() {
        List<Node> chain = traversal.traceChain(CTRL);

        Set<String> ids = nodeIds(chain);
        assertThat(ids).containsExactlyInAnyOrder(CTRL, CMD, HANDLER, AGG, EVENT, PROJ, VIEW);
    }

    @Test
    void traceChainBackwardFromView() {
        List<Node> chain = traversal.traceChain(VIEW);

        Set<String> ids = nodeIds(chain);
        // view ← proj → event ← agg ← handler → cmd ← ctrl
        assertThat(ids).containsExactlyInAnyOrder(CTRL, CMD, HANDLER, AGG, EVENT, PROJ, VIEW);
    }

    @Test
    void traceChainFromMiddle() {
        List<Node> chain = traversal.traceChain(EVENT);

        Set<String> ids = nodeIds(chain);
        // event connects to agg (incoming EMITS_EVENT) and proj (incoming PROJECTS_EVENT)
        // agg connects to handler, handler connects to cmd, cmd connects to ctrl
        // proj connects to view
        assertThat(ids).containsExactlyInAnyOrder(CTRL, CMD, HANDLER, AGG, EVENT, PROJ, VIEW);
    }

    @Test
    void impactAnalysisDirectDependents() {
        List<Node> impacted = traversal.impact(EVENT, 1);

        Set<String> ids = nodeIds(impacted);
        // Direct neighbors of event (both directions, depth=1): agg (via EMITS_EVENT from agg) + proj (via PROJECTS_EVENT from proj)
        assertThat(ids).containsExactlyInAnyOrder(AGG, PROJ);
        assertThat(ids).doesNotContain(EVENT);
    }

    @Test
    void impactAnalysisDeep() {
        List<Node> impacted = traversal.impact(EVENT, 2);

        Set<String> ids = nodeIds(impacted);
        // depth=1: agg, proj
        // depth=2: handler (via agg←handler), view (via proj→view)
        assertThat(ids).contains(AGG, PROJ, HANDLER, VIEW);
        assertThat(ids).doesNotContain(EVENT);
    }

    @Test
    void traceChainReturnsNoDuplicates() {
        List<Node> chain = traversal.traceChain(CTRL);

        List<String> ids = chain.stream().map(Node::id).toList();
        Set<String> unique = Set.copyOf(ids);
        assertThat(ids).hasSize(unique.size());
    }

    @Test
    void traceChainSpringPath() {
        List<Node> chain = traversal.traceChain(CTRL2);

        Set<String> ids = nodeIds(chain);
        assertThat(ids).containsExactlyInAnyOrder(CTRL2, SVC, REPO, ENTITY);
        // DDD nodes should not appear
        assertThat(ids).doesNotContain(CTRL, CMD, HANDLER, AGG, EVENT, PROJ, VIEW);
    }

    @Test
    void callersReturnsOnlyIncomingCallEdges() {
        // Add a CALLS_METHOD edge: ctrl2 calls a method on svc
        saveEdge("e10", EdgeType.CALLS_METHOD, CTRL2, SVC);

        List<Node> callers = traversal.callers(SVC, 10);
        Set<String> ids = nodeIds(callers);

        // CTRL2 is a caller of SVC (via CALLS_METHOD and INJECTS)
        assertThat(ids).contains(CTRL2);
        // REPO is a callee, not a caller
        assertThat(ids).doesNotContain(REPO);
    }

    @Test
    void calleesReturnsOnlyOutgoingCallEdges() {
        // Add a CALLS_METHOD edge: svc calls a method on repo
        saveEdge("e11", EdgeType.CALLS_METHOD, SVC, REPO);

        List<Node> callees = traversal.callees(SVC, 10);
        Set<String> ids = nodeIds(callees);

        // REPO is a callee of SVC (via CALLS_METHOD and INJECTS)
        assertThat(ids).contains(REPO);
        // CTRL2 is a caller, not a callee
        assertThat(ids).doesNotContain(CTRL2);
    }

    @Test
    void callersRespectsDepthLimit() {
        // Chain: CTRL2 --CALLS_METHOD--> SVC --CALLS_METHOD--> REPO
        saveEdge("e10", EdgeType.CALLS_METHOD, CTRL2, SVC);
        saveEdge("e11", EdgeType.CALLS_METHOD, SVC, REPO);

        // callers of REPO at depth 1: only SVC (direct caller)
        List<Node> callers = traversal.callers(REPO, 1);
        Set<String> ids = nodeIds(callers);
        assertThat(ids).contains(SVC);
        assertThat(ids).doesNotContain(CTRL2); // CTRL2 is depth 2, excluded
    }

    // ---- Helpers ----

    private void saveNode(String id, NodeType type) {
        store.saveNode(new Node(id, type, id, "com.example." + id, "src/" + id + ".java", 1, "", 0));
    }

    private void saveEdge(String id, EdgeType type, String sourceId, String targetId) {
        store.saveEdge(new Edge(id, type, sourceId, targetId));
    }

    private Set<String> nodeIds(List<Node> nodes) {
        return nodes.stream().map(Node::id).collect(Collectors.toSet());
    }

    // ---- Method-level traversal ----

    /**
     * Two classes, each with one method, wired A.a() -> B.b():
     * <pre>
     *   A --DECLARES_METHOD--> A#a() --CALLS--> B#b() &lt;--DECLARES_METHOD-- B
     * </pre>
     */
    private void buildMethodGraph() {
        store.saveNode(new Node("A", NodeType.SERVICE, "A", "A", "A.java", 1, "", 0));
        store.saveNode(new Node("B", NodeType.SERVICE, "B", "B", "B.java", 1, "", 0));
        store.saveNode(new Node("A#a()", NodeType.METHOD, "a", "A#a()", "A.java", 2, "", 0));
        store.saveNode(new Node("B#b()", NodeType.METHOD, "b", "B#b()", "B.java", 2, "", 0));
        store.saveEdge(new Edge("d1", EdgeType.DECLARES_METHOD, "A", "A#a()"));
        store.saveEdge(new Edge("d2", EdgeType.DECLARES_METHOD, "B", "B#b()"));
        store.saveEdge(new Edge("c1", EdgeType.CALLS, "A#a()", "B#b()"));
    }

    @Test
    void declaresMethodIsAFreeHopSoClassReachesClassAtDepthOne() {
        buildMethodGraph();

        // A -> A#a() -> B#b() -> B is three edges but only one call hop. If DECLARES_METHOD cost
        // depth, class B would be unreachable at depth 1 and every class-level answer would
        // collapse to the class's own methods.
        assertThat(nodeIds(traversal.callees("A", 1))).contains("B");
    }

    @Test
    void methodCallsAreFollowedInBothDirections() {
        buildMethodGraph();

        assertThat(nodeIds(traversal.callees("A#a()", 1))).contains("B#b()");
        assertThat(nodeIds(traversal.callers("B#b()", 1))).contains("A#a()");
    }

    @Test
    void impactFromAMethodReachesItsCaller() {
        buildMethodGraph();

        assertThat(nodeIds(traversal.impact("B#b()", 2))).contains("A#a()", "A");
    }

    @Test
    void traversalSettlesNodesAtMinimumDepthNotFirstArrival() {
        // C#c() is reachable two ways: one call hop via the free DECLARES_METHOD edge from C,
        // and two call hops through the chain. A plain FIFO queue can settle it at the longer
        // depth depending on edge insertion order; 0-1 BFS must always settle it at the shorter.
        buildMethodGraph();
        store.saveNode(new Node("C", NodeType.SERVICE, "C", "C", "C.java", 1, "", 0));
        store.saveNode(new Node("C#c()", NodeType.METHOD, "c", "C#c()", "C.java", 2, "", 0));
        store.saveEdge(new Edge("c2", EdgeType.CALLS, "B#b()", "C#c()"));
        store.saveEdge(new Edge("d3", EdgeType.DECLARES_METHOD, "C", "C#c()"));

        // A#a() -> B#b() -> C#c() is two call hops.
        assertThat(nodeIds(traversal.callees("A#a()", 2))).contains("C#c()");
        assertThat(nodeIds(traversal.callees("A#a()", 1))).doesNotContain("C#c()");
    }

    @Test
    void startNodeIsNeverEmittedAsItsOwnResult() {
        buildMethodGraph();

        assertThat(nodeIds(traversal.callees("A", 3))).doesNotContain("A");
        assertThat(nodeIds(traversal.impact("A", 3))).doesNotContain("A");
    }
}
