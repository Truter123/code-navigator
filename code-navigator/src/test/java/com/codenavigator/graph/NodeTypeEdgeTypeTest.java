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

    @Test
    void includes_extended_frontend_and_script_types() {
        for (String n : new String[]{"FE_CLASS", "FE_ENUM", "FE_PIPE", "FE_GUARD",
                "FE_INTERCEPTOR", "FE_VALIDATOR", "FE_CONSTANT", "GROOVY_SCRIPT"}) {
            assertThat(NodeType.valueOf(n)).isNotNull();
        }
    }
}
