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
     * Two classes wired A.a() -> B.b(), each also declaring an unrelated sibling method with no
     * CALLS edge (A#other(), B#other()), to prove the free DECLARES_METHOD hop never leaks a
     * class's other methods into a directional caller/callee answer:
     * <pre>
     *   A --DECLARES_METHOD--> A#a() --CALLS--> B#b() &lt;--DECLARES_METHOD-- B
     *   A --DECLARES_METHOD--> A#other()          B --DECLARES_METHOD--> B#other()
     * </pre>
     */
    private void buildMethodGraph() {
        store.saveNode(new Node("A", NodeType.SERVICE, "A", "A", "A.java", 1, "", 0));
        store.saveNode(new Node("B", NodeType.SERVICE, "B", "B", "B.java", 1, "", 0));
        store.saveNode(new Node("A#a()", NodeType.METHOD, "a", "A#a()", "A.java", 2, "", 0));
        store.saveNode(new Node("B#b()", NodeType.METHOD, "b", "B#b()", "B.java", 2, "", 0));
        store.saveNode(new Node("A#other()", NodeType.METHOD, "other", "A#other()", "A.java", 3, "", 0));
        store.saveNode(new Node("B#other()", NodeType.METHOD, "other", "B#other()", "B.java", 3, "", 0));
        store.saveEdge(new Edge("d1", EdgeType.DECLARES_METHOD, "A", "A#a()"));
        store.saveEdge(new Edge("d2", EdgeType.DECLARES_METHOD, "B", "B#b()"));
        store.saveEdge(new Edge("d4", EdgeType.DECLARES_METHOD, "A", "A#other()"));
        store.saveEdge(new Edge("d5", EdgeType.DECLARES_METHOD, "B", "B#other()"));
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
    void callersDoesNotLeakSiblingMethodsOfTheDeclaringClass() {
        buildMethodGraph();

        Set<String> ids = nodeIds(traversal.callers("B#b()", 1));
        assertThat(ids).contains("A#a()");
        assertThat(ids).doesNotContain("B#other()", "A#other()");
    }

    @Test
    void calleesDoesNotLeakSiblingMethodsOfTheDeclaringClass() {
        buildMethodGraph();

        Set<String> ids = nodeIds(traversal.callees("A#a()", 1));
        assertThat(ids).contains("B#b()");
        assertThat(ids).doesNotContain("A#other()", "B#other()");
    }

    @Test
    void callersFromAClassStartNodeStillFansOutToItsMethods() {
        buildMethodGraph();

        assertThat(nodeIds(traversal.callers("B", 1))).contains("A#a()");
    }

    @Test
    void contextOnlyClassExpandsNoEdgesOfItsOwn() {
        // E injects B (a class-level, directed call edge) and declares E#apply(), which has no
        // call edge of its own. B#b() only ever reaches B as context via the free method->class
        // hop, so B must not fan out into its own INJECTS callers (E) nor E's own methods — those
        // belong to a query that actually starts at B, not one that merely named it in passing.
        buildMethodGraph();
        store.saveNode(new Node("E", NodeType.SERVICE, "E", "E", "E.java", 1, "", 0));
        store.saveNode(new Node("E#apply()", NodeType.METHOD, "apply", "E#apply()", "E.java", 2, "", 0));
        store.saveEdge(new Edge("d6", EdgeType.DECLARES_METHOD, "E", "E#apply()"));
        store.saveEdge(new Edge("e12", EdgeType.INJECTS, "E", "B"));

        Set<String> depthOne = nodeIds(traversal.callers("B#b()", 1));
        assertThat(depthOne).contains("A#a()");
        assertThat(depthOne).doesNotContain("E", "E#apply()");

        Set<String> depthTwo = nodeIds(traversal.callers("B#b()", 2));
        assertThat(depthTwo).contains("A#a()");
        assertThat(depthTwo).doesNotContain("E", "E#apply()");

        // Starting the query at the class itself, E is a real hit again.
        assertThat(nodeIds(traversal.callers("B", 1))).contains("E");
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

    // ---- Extra-language seam edges (Python, shell, SQL) ----

    @Test
    void callersFollowsReferencesTableEdges() {
        store.saveNode(new Node("sql:supplier", NodeType.SQL_TABLE, "supplier", "supplier", "V1.sql", 1, "", 0));
        store.saveNode(new Node("SupplierEntity", NodeType.ENTITY, "SupplierEntity", "SupplierEntity", "SupplierEntity.java", 1, "", 0));
        store.saveNode(new Node("SupplierRepository", NodeType.REPOSITORY, "SupplierRepository", "SupplierRepository", "SupplierRepository.java", 1, "", 0));
        store.saveEdge(new Edge("r1", EdgeType.REFERENCES_TABLE, "SupplierEntity", "sql:supplier"));
        store.saveEdge(new Edge("r2", EdgeType.REFERENCES_TABLE, "SupplierRepository", "sql:supplier"));

        Set<String> callers = nodeIds(traversal.callers("sql:supplier", 1));
        assertThat(callers).containsExactlyInAnyOrder("SupplierEntity", "SupplierRepository");
    }

    @Test
    void callersFollowsSourcesEdgeForShellScripts() {
        store.saveNode(new Node("lib.sh", NodeType.SHELL_SCRIPT, "lib", "lib.sh", "lib.sh", 1, "", 0));
        store.saveNode(new Node("deploy.sh", NodeType.SHELL_SCRIPT, "deploy", "deploy.sh", "deploy.sh", 1, "", 0));
        store.saveEdge(new Edge("s1", EdgeType.SOURCES, "deploy.sh", "lib.sh"));

        Set<String> callers = nodeIds(traversal.callers("lib.sh", 1));
        assertThat(callers).contains("deploy.sh");
    }

    @Test
    void callersDoesNotEmitDeclaringOwnerAsACaller() {
        // A PY_MODULE "build" declares the function it owns via DECLARES_METHOD, the same free
        // hop Java classes use. The module is containment, not a caller: cg_related direction=in
        // on the function must not list its own declaring module as if it called itself.
        store.saveNode(new Node("tools/build.py", NodeType.PY_MODULE, "build", "build", "tools/build.py", 1, "", 0));
        store.saveNode(new Node("tools/build.py#build_or_update_graph()", NodeType.METHOD,
            "build_or_update_graph", "tools/build.py#build_or_update_graph()", "tools/build.py", 10, "", 0));
        store.saveEdge(new Edge("dm1", EdgeType.DECLARES_METHOD, "tools/build.py",
            "tools/build.py#build_or_update_graph()"));
        store.saveNode(new Node("caller.py", NodeType.PY_MODULE, "caller", "caller", "caller.py", 1, "", 0));
        store.saveNode(new Node("caller.py#run()", NodeType.METHOD, "run", "caller.py#run()", "caller.py", 5, "", 0));
        store.saveEdge(new Edge("dm2", EdgeType.DECLARES_METHOD, "caller.py", "caller.py#run()"));
        store.saveEdge(new Edge("c1", EdgeType.CALLS, "caller.py#run()",
            "tools/build.py#build_or_update_graph()"));

        Set<String> callers = nodeIds(traversal.callers("tools/build.py#build_or_update_graph()", 2));
        assertThat(callers).contains("caller.py#run()");
        assertThat(callers).doesNotContain("tools/build.py");
    }
}
