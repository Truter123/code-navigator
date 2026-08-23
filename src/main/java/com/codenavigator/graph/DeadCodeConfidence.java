package com.codenavigator.graph;

/**
 * How confident {@link GraphStore#findDeadNodeCandidates(NodeType)} is that a candidate
 * is actually unused, ordered from "worth investigating first" to "expected noise".
 * This is a ranking, not a verdict — every tier still needs a human to confirm before deleting
 * anything.
 */
public enum DeadCodeConfidence {
    /**
     * No incoming edges, no live supertype/interface, not a framework entry point, not test
     * source. The node type is one the indexer reliably captures call/injection edges for
     * (e.g. SERVICE, REPOSITORY, plain CLASS), so a miss here is the strongest dead-code signal
     * this tool can produce.
     */
    HIGH,

    /**
     * No incoming edges, no live supertype, not an entry point — but the node type is commonly
     * constructed in ways the indexer doesn't model as an edge (JSON/JPA (de)serialization,
     * generic repository lookups, interfaces referenced only by type). Worth a look, but false
     * positives are meaningfully more likely here than in HIGH.
     */
    MEDIUM,

    /**
     * The node's type is invoked by a framework via reflection or auto-registration (HTTP
     * dispatch, an event/command/query bus, DI configuration classes). Nothing in the graph will
     * ever point at these even when they're fully live, so an empty incoming-edge set carries
     * almost no signal.
     */
    LOW,

    /**
     * The node itself lives under test sources. JUnit/Vitest invoke tests reflectively, so they
     * never have incoming graph edges by construction — this is not dead code, it's how tests
     * are shaped.
     */
    TEST_SOURCE,

    /**
     * A main-source node that DOES have incoming edges, but every one of them originates in test
     * code. Distinct from "dead" (it has edges), but worth surfacing separately: it may be
     * intentional test scaffolding, or it may be the last thing keeping otherwise-dead production
     * code reachable.
     */
    USED_ONLY_BY_TESTS
}
