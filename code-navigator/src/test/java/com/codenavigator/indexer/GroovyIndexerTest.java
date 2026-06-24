package com.codenavigator.indexer;

import com.codenavigator.graph.NodeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GroovyIndexerTest {

    private final GroovyIndexer indexer = new GroovyIndexer();

    @Test
    void namedClassYieldsClassSymbol(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("Pipeline.groovy");
        Files.writeString(f, "abstract class Pipeline {\n  void run() {}\n}\n");
        var nodes = indexer.indexFile(f);
        assertThat(nodes).extracting("type").containsExactly(NodeType.GROOVY_SCRIPT);
        assertThat(nodes.getFirst().name()).isEqualTo("Pipeline");
    }

    @Test
    void scriptWithoutClassYieldsFilenameSymbol(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("Jenkinsfile.groovy");
        Files.writeString(f, "node {\n  echo 'building'\n}\n");
        var nodes = indexer.indexFile(f);
        assertThat(nodes).extracting("type").containsExactly(NodeType.GROOVY_SCRIPT);
        assertThat(nodes.getFirst().name()).isEqualTo("Jenkinsfile");
    }
}
