package com.codenavigator.indexer;

import com.codenavigator.graph.NodeType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class TypeScriptIndexerTest {

    private final TypeScriptIndexer indexer = new TypeScriptIndexer();
    private final Path sampleDir = Paths.get("src/test/resources/sample-ts");

    @Test
    void detectsService() {
        var nodes = indexer.indexFile(sampleDir.resolve("worker.service.ts"));
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().type()).isEqualTo(NodeType.FE_SERVICE);
        assertThat(nodes.getFirst().name()).isEqualTo("WorkerService");
    }

    @Test
    void detectsComponent() {
        var nodes = indexer.indexFile(sampleDir.resolve("worker-list.component.ts"));
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().type()).isEqualTo(NodeType.FE_COMPONENT);
        assertThat(nodes.getFirst().name()).isEqualTo("WorkerListComponent");
    }

    @Test
    void detectsModel() {
        var nodes = indexer.indexFile(sampleDir.resolve("worker.model.ts"));
        assertThat(nodes).hasSize(2);
        assertThat(nodes).extracting("name").containsExactly("Worker", "CreateWorkerRequest");
        assertThat(nodes).allMatch(n -> n.type() == NodeType.FE_MODEL);
    }

    @Test
    void extractsApiUrl() {
        var nodes = indexer.indexFile(sampleDir.resolve("worker.service.ts"));
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().codeSnippet()).contains("/workers");
    }
}
