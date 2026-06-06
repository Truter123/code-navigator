package com.codenavigator.graph;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class NodeTypeEdgeTypeTest {

    @Test
    void nodeTypeLibraryExists() {
        assertThat(NodeType.LIBRARY).isNotNull();
    }

    @Test
    void edgeTypeUsesLibraryExists() {
        assertThat(EdgeType.USES_LIBRARY).isNotNull();
    }
}
