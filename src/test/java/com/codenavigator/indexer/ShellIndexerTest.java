package com.codenavigator.indexer;

import com.codenavigator.graph.Edge;
import com.codenavigator.graph.EdgeType;
import com.codenavigator.graph.Node;
import com.codenavigator.graph.NodeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShellIndexerTest {

    private final ShellIndexer indexer = new ShellIndexer();

    private List<Node> index(Path file, Path root) {
        var nodes = new ArrayList<>(indexer.indexFile(file, root));
        nodes.addAll(indexer.extractMethods(file, nodes));
        return nodes;
    }

    @Test
    void indexesOneScriptNode(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("deploy.sh");
        Files.writeString(f, "#!/bin/bash\necho hi\n");
        var nodes = indexer.indexFile(f, dir);
        assertThat(nodes).extracting("type").containsExactly(NodeType.SHELL_SCRIPT);
        assertThat(nodes.getFirst().name()).isEqualTo("deploy");
    }

    @Test
    void bothFunctionFormsProduceMethodNodes(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("deploy.sh");
        Files.writeString(f, """
            #!/bin/bash
            confirm() {
              echo confirming
            }

            function build {
              echo building
            }
            """);
        var nodes = index(f, dir);
        assertThat(nodes).filteredOn(n -> n.type() == NodeType.METHOD)
            .extracting("name").containsExactlyInAnyOrder("confirm", "build");
    }

    @Test
    void siblingCallInsideFunctionAndTopLevelCallFromScript(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("deploy.sh");
        Files.writeString(f, """
            #!/bin/bash
            confirm() {
              build
            }

            build() {
              echo building
            }

            confirm
            """);
        var nodes = index(f, dir);
        var edges = indexer.extractEdges(f, nodes, KnownNames.empty());

        var script = nodes.stream().filter(n -> n.type() == NodeType.SHELL_SCRIPT).findFirst().orElseThrow();
        var confirmId = nodes.stream().filter(n -> n.name().equals("confirm")).findFirst().orElseThrow().id();
        var buildId = nodes.stream().filter(n -> n.name().equals("build")).findFirst().orElseThrow().id();

        assertThat(edges).anyMatch(e -> e.type() == EdgeType.CALLS
            && e.sourceId().equals(confirmId) && e.targetId().equals(buildId));
        assertThat(edges).anyMatch(e -> e.type() == EdgeType.CALLS
            && e.sourceId().equals(script.id()) && e.targetId().equals(confirmId));
        assertThat(edges).filteredOn(e -> e.type() == EdgeType.DECLARES_METHOD).hasSize(2);
    }

    @Test
    void sourcesRelativeDotSlashScript(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("lib.sh"), "helper() { echo hi; }\n");
        Path f = dir.resolve("deploy.sh");
        Files.writeString(f, "#!/bin/bash\nsource ./lib.sh\n");
        assertSourcesLibSh(f, dir);
    }

    @Test
    void sourcesDotSpaceDotSlashScript(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("lib.sh"), "helper() { echo hi; }\n");
        Path f = dir.resolve("deploy.sh");
        Files.writeString(f, "#!/bin/bash\n. ./lib.sh\n");
        assertSourcesLibSh(f, dir);
    }

    @Test
    void sourcesViaBashInterpreter(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("lib.sh"), "helper() { echo hi; }\n");
        Path f = dir.resolve("deploy.sh");
        Files.writeString(f, "#!/bin/bash\nbash lib.sh\n");
        assertSourcesLibSh(f, dir);
    }

    @Test
    void sourcesViaDirectInvocation(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("lib.sh"), "helper() { echo hi; }\n");
        Path f = dir.resolve("deploy.sh");
        Files.writeString(f, "#!/bin/bash\n./lib.sh\n");
        assertSourcesLibSh(f, dir);
    }

    @Test
    void sourcesScriptDirPrefixedPath(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("lib.sh"), "helper() { echo hi; }\n");
        Path f = dir.resolve("deploy.sh");
        Files.writeString(f, "#!/bin/bash\nsource \"$SCRIPT_DIR/lib.sh\"\n");
        assertSourcesLibSh(f, dir);
    }

    private void assertSourcesLibSh(Path f, Path dir) {
        var nodes = index(f, dir);
        var edges = indexer.extractEdges(f, nodes, KnownNames.empty());
        var script = nodes.stream().filter(n -> n.type() == NodeType.SHELL_SCRIPT).findFirst().orElseThrow();
        assertThat(edges).anyMatch(e -> e.type() == EdgeType.SOURCES
            && e.sourceId().equals(script.id()) && e.targetId().equals("lib.sh"));
    }

    @Test
    void sourcingDotEnvProducesNoEdge(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(".env"), "FOO=bar\n");
        Path f = dir.resolve("deploy.sh");
        Files.writeString(f, "#!/bin/bash\nsource .env\n");
        var nodes = index(f, dir);
        var edges = indexer.extractEdges(f, nodes, KnownNames.empty());
        assertThat(edges).noneMatch(e -> e.type() == EdgeType.SOURCES);
    }

    @Test
    void sourcingMissingFileProducesNoEdge(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("deploy.sh");
        Files.writeString(f, "#!/bin/bash\nsource ./missing.sh\n");
        var nodes = index(f, dir);
        var edges = indexer.extractEdges(f, nodes, KnownNames.empty());
        assertThat(edges).noneMatch(e -> e.type() == EdgeType.SOURCES);
    }

    @Test
    void dockerComposeRunsEdgeNotDocker(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("deploy.sh");
        Files.writeString(f, "#!/bin/bash\ndocker-compose up -d\n");
        var nodes = index(f, dir);
        var edges = indexer.extractEdges(f, nodes, KnownNames.empty());
        assertThat(edges).filteredOn(e -> e.type() == EdgeType.RUNS)
            .extracting(Edge::targetId).containsExactly("docker-compose");
    }

    @Test
    void gitCalledThreeTimesProducesOneRunsEdge(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("deploy.sh");
        Files.writeString(f, "#!/bin/bash\ngit fetch\ngit pull\ngit push\n");
        var nodes = index(f, dir);
        var edges = indexer.extractEdges(f, nodes, KnownNames.empty());
        assertThat(edges).filteredOn(e -> e.type() == EdgeType.RUNS).hasSize(1);
    }

    @Test
    void commentedAndHeredocCommandsProduceNoRunsEdge(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("deploy.sh");
        Files.writeString(f, """
            #!/bin/bash
            # git push
            cat <<'EOF'
            curl http://x
            EOF
            """);
        var nodes = index(f, dir);
        var edges = indexer.extractEdges(f, nodes, KnownNames.empty());
        assertThat(edges).noneMatch(e -> e.type() == EdgeType.RUNS);
    }
}
