package com.codenavigator.indexer;

import com.codenavigator.graph.EdgeType;
import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.MethodIds;
import com.codenavigator.graph.NodeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** A type name of any length resolves; two parameter types sharing a 40-character prefix stay distinct. */
class LongTypeNamesTest {

    @TempDir Path tempDir;

    private static final String A = "AVeryLongClassNameThatGoesOnAndOnForSixtyCharsAlphaOne";
    private static final String B = "AVeryLongClassNameThatGoesOnAndOnForSixtyCharsAlphaTwo";
    private static final String ONE = "AVeryLongClassNameThatGoesOnAndOnForSixtyCharsParamOne";
    private static final String TWO = "AVeryLongClassNameThatGoesOnAndOnForSixtyCharsParamTwo";

    private GraphStore index() throws IOException {
        Path pkg = tempDir.resolve("fx/src/main/java/p");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve(A + ".java"), "package p;\npublic class " + A
            + " { public void m() {} public void take(" + B + " b) {} }\n");
        Files.writeString(pkg.resolve(B + ".java"), "package p;\npublic class " + B + " { public void m() {} }\n");
        Files.writeString(pkg.resolve(ONE + ".java"), "package p;\npublic class " + ONE + " {}\n");
        Files.writeString(pkg.resolve(TWO + ".java"), "package p;\npublic class " + TWO + " {}\n");
        Files.writeString(pkg.resolve("Caller.java"), "package p;\npublic class Caller {\n"
            + "  private " + A + " field = new " + A + "();\n"
            + "  public void viaField() { field.m(); }\n"
            + "  public void viaNew() { new " + B + "().m(); }\n"
            + "  public void viaParam(" + A + " a) { a.m(); }\n"
            + "  public void m(" + ONE + " x) {}\n"
            + "  public void m(" + TWO + " x) {}\n}\n");
        var store = new GraphStore(tempDir.resolve("t.db"));
        new ProjectIndexer(store).indexFull(tempDir.resolve("fx"));
        return store;
    }

    @Test
    void callsThroughFieldNewAndParameterOfASixtyCharacterTypeAreBound() throws IOException {
        try (var store = index()) {
            var calls = store.getAllEdges().stream().filter(e -> e.type() == EdgeType.CALLS)
                .map(e -> e.sourceId().substring(e.sourceId().indexOf('#') + 1) + " -> "
                    + e.targetId().substring(e.targetId().lastIndexOf('.') + 1)).toList();
            assertThat(calls).anyMatch(c -> c.startsWith("viaField()") && c.contains(A + "#m"));
            assertThat(calls).anyMatch(c -> c.startsWith("viaNew()") && c.contains(B + "#m"));
            assertThat(calls).anyMatch(c -> c.startsWith("viaParam(") && c.contains(A + "#m"));
        }
    }

    @Test
    void twoOverloadsWhoseParameterTypesShareFortyCharactersGetTwoMethodIds() throws IOException {
        try (var store = index()) {
            var ids = store.findNodesByType(NodeType.METHOD).stream().map(n -> n.id())
                .filter(id -> id.contains("Caller#m(")).toList();
            assertThat(ids).containsExactlyInAnyOrder(
                MethodIds.of("p.Caller", "m", java.util.List.of(ONE)),
                MethodIds.of("p.Caller", "m", java.util.List.of(TWO)));
        }
    }
}
