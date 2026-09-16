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

class PythonIndexerTest {

    private final PythonIndexer indexer = new PythonIndexer();

    /** prepare() over every file, then indexFile()+extractMethods() for the target file. */
    private List<Node> index(Path root, Path file, Path... allFiles) {
        List<Path> all = allFiles.length == 0 ? List.of(file) : List.of(allFiles);
        indexer.prepare(all, root);
        var nodes = new ArrayList<>(indexer.indexFile(file, root));
        nodes.addAll(indexer.extractMethods(file, nodes));
        return nodes;
    }

    private List<Edge> edges(Path file, List<Node> nodes) {
        return indexer.extractEdges(file, nodes, KnownNames.empty());
    }

    @Test
    void moduleNodeNameAndQualifiedNameUnderPackage(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("pkg"));
        Files.writeString(dir.resolve("pkg/__init__.py"), "");
        Path mod = dir.resolve("pkg/mod.py");
        Files.writeString(mod, "x = 1\n");
        Path init = dir.resolve("pkg/__init__.py");

        var nodes = index(dir, mod, mod, init);
        var module = nodes.stream().filter(n -> n.type() == NodeType.PY_MODULE).findFirst().orElseThrow();
        assertThat(module.name()).isEqualTo("mod");
        assertThat(module.qualifiedName()).isEqualTo("pkg.mod");
    }

    @Test
    void initModuleNodeUsesParentDirNameAndItsOwnQualifiedName(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("pkg"));
        Path init = dir.resolve("pkg/__init__.py");
        Files.writeString(init, "");

        var nodes = index(dir, init, init);
        var module = nodes.stream().filter(n -> n.type() == NodeType.PY_MODULE).findFirst().orElseThrow();
        assertThat(module.name()).isEqualTo("pkg");
        assertThat(module.qualifiedName()).isEqualTo("pkg");
    }

    @Test
    void classProducesPyClassNode(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("mod.py");
        Files.writeString(f, "class Foo:\n    pass\n");
        var nodes = index(dir, f);
        assertThat(nodes).filteredOn(n -> n.type() == NodeType.PY_CLASS)
            .extracting(Node::name).containsExactly("Foo");
    }

    @Test
    void selfCallProducesCallsEdgeBetweenSiblingMethods(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("mod.py");
        Files.writeString(f, """
            class Foo:
                def bar(self):
                    return self.baz()

                def baz(self):
                    return 1
            """);
        var nodes = index(dir, f);
        var edges = edges(f, nodes);

        var barId = nodes.stream().filter(n -> n.name().equals("bar")).findFirst().orElseThrow().id();
        var bazId = nodes.stream().filter(n -> n.name().equals("baz")).findFirst().orElseThrow().id();
        assertThat(edges).anyMatch(e -> e.type() == EdgeType.CALLS
            && e.sourceId().equals(barId) && e.targetId().equals(bazId));
    }

    @Test
    void moduleLevelFunctionProducesMethodNode(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("mod.py");
        Files.writeString(f, "def helper():\n    return 1\n");
        var nodes = index(dir, f);
        assertThat(nodes).filteredOn(n -> n.type() == NodeType.METHOD)
            .extracting(Node::name).containsExactly("helper");
    }

    @Test
    void asyncDefProducesMethodNode(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("mod.py");
        Files.writeString(f, "async def helper():\n    return 1\n");
        var nodes = index(dir, f);
        assertThat(nodes).filteredOn(n -> n.type() == NodeType.METHOD)
            .extracting(Node::name).containsExactly("helper");
    }

    @Test
    void decoratedDefStillFound(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("mod.py");
        Files.writeString(f, "@staticmethod\ndef helper():\n    return 1\n");
        var nodes = index(dir, f);
        assertThat(nodes).filteredOn(n -> n.type() == NodeType.METHOD)
            .extracting(Node::name).containsExactly("helper");
    }

    @Test
    void multiLineSignatureStillFound(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("mod.py");
        Files.writeString(f, """
            def helper(a,
                       b):
                return a + b
            """);
        var nodes = index(dir, f);
        assertThat(nodes).filteredOn(n -> n.type() == NodeType.METHOD)
            .extracting(Node::name).containsExactly("helper");
    }

    @Test
    void nestedClassAndItsDefsAreSkipped(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("mod.py");
        Files.writeString(f, """
            class Outer:
                class Inner:
                    def innerMethod(self):
                        return 1

                def outerMethod(self):
                    return 2
            """);
        var nodes = index(dir, f);
        assertThat(nodes).filteredOn(n -> n.type() == NodeType.PY_CLASS)
            .extracting(Node::name).containsExactly("Outer");
        assertThat(nodes).filteredOn(n -> n.type() == NodeType.METHOD)
            .extracting(Node::name).containsExactly("outerMethod");
    }

    @Test
    void nestedFunctionInsideFunctionIsSkipped(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("mod.py");
        Files.writeString(f, """
            def outer():
                def inner():
                    return 1
                return inner()
            """);
        var nodes = index(dir, f);
        assertThat(nodes).filteredOn(n -> n.type() == NodeType.METHOD)
            .extracting(Node::name).containsExactly("outer");
    }

    @Test
    void importOsProducesNoEdge(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("mod.py");
        Files.writeString(f, "import os\n");
        var nodes = index(dir, f);
        var edges = edges(f, nodes);
        assertThat(edges).noneMatch(e -> e.type() == EdgeType.IMPORTS);
    }

    @Test
    void fromRelativeImportProducesImportsAndCallsEdges(@TempDir Path dir) throws IOException {
        Path util = dir.resolve("util.py");
        Files.writeString(util, "def helper():\n    return 1\n");
        Path mod = dir.resolve("mod.py");
        Files.writeString(mod, """
            from .util import helper

            def use():
                return helper()
            """);
        var nodes = index(dir, mod, mod, util);
        var edges = edges(mod, nodes);

        var modNode = nodes.stream().filter(n -> n.type() == NodeType.PY_MODULE
            && n.filePath().equals("mod.py")).findFirst().orElseThrow();
        assertThat(edges).anyMatch(e -> e.type() == EdgeType.IMPORTS
            && e.sourceId().equals(modNode.id()) && e.targetId().equals("util.py"));

        var useId = nodes.stream().filter(n -> n.name().equals("use")).findFirst().orElseThrow().id();
        var helperId = "util.py#helper()";
        assertThat(edges).anyMatch(e -> e.type() == EdgeType.CALLS
            && e.sourceId().equals(useId) && e.targetId().equals(helperId));
    }

    @Test
    void fromDoubleDotImportGoesTwoLevelsUp(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("pkg/sub"));
        Files.writeString(dir.resolve("pkg/__init__.py"), "");
        Files.writeString(dir.resolve("pkg/core.py"), "def x():\n    return 1\n");
        Files.writeString(dir.resolve("pkg/sub/__init__.py"), "");
        Path mod = dir.resolve("pkg/sub/mod.py");
        Files.writeString(mod, "from ..core import x\n");

        var all = List.of(
            dir.resolve("pkg/__init__.py"), dir.resolve("pkg/core.py"),
            dir.resolve("pkg/sub/__init__.py"), mod);
        indexer.prepare(all, dir);
        var nodes = new ArrayList<>(indexer.indexFile(mod, dir));
        nodes.addAll(indexer.extractMethods(mod, nodes));
        var edges = edges(mod, nodes);

        var modNode = nodes.stream().filter(n -> n.type() == NodeType.PY_MODULE).findFirst().orElseThrow();
        assertThat(edges).anyMatch(e -> e.type() == EdgeType.IMPORTS
            && e.sourceId().equals(modNode.id()) && e.targetId().equals("pkg/core.py"));
    }

    @Test
    void subclassSameFileProducesExtendsEdge(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("mod.py");
        Files.writeString(f, "class A:\n    pass\n\nclass B(A):\n    pass\n");
        var nodes = index(dir, f);
        var edges = edges(f, nodes);

        var aId = nodes.stream().filter(n -> n.name().equals("A")).findFirst().orElseThrow().id();
        var bId = nodes.stream().filter(n -> n.name().equals("B")).findFirst().orElseThrow().id();
        assertThat(edges).anyMatch(e -> e.type() == EdgeType.EXTENDS
            && e.sourceId().equals(bId) && e.targetId().equals(aId));
    }

    @Test
    void commentedDefProducesNoNode(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("mod.py");
        Files.writeString(f, "# def ghost():\n#     pass\n");
        var nodes = index(dir, f);
        assertThat(nodes).noneMatch(n -> n.type() == NodeType.METHOD);
    }

    @Test
    void classInsideTripleQuotedStringProducesNoNode(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("mod.py");
        Files.writeString(f, "x = \"\"\"\nclass Ghost:\n    pass\n\"\"\"\n");
        var nodes = index(dir, f);
        assertThat(nodes).noneMatch(n -> n.type() == NodeType.PY_CLASS);
    }

    @Test
    void printCallProducesNoEdge(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("mod.py");
        Files.writeString(f, "def use():\n    print(1)\n");
        var nodes = index(dir, f);
        var edges = edges(f, nodes);
        assertThat(edges).filteredOn(e -> e.type() == EdgeType.CALLS).isEmpty();
    }

    @Test
    void twoCallsToSameHelperProduceOneEdge(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("mod.py");
        Files.writeString(f, """
            def helper():
                return 1

            def use():
                helper()
                helper()
            """);
        var nodes = index(dir, f);
        var edges = edges(f, nodes);
        assertThat(edges).filteredOn(e -> e.type() == EdgeType.CALLS).hasSize(1);
    }
}
