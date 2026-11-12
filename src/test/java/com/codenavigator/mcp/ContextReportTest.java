package com.codenavigator.mcp;

import com.codenavigator.graph.Node;
import com.codenavigator.graph.NodeType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextReportTest {

    private static List<Node> nodes(int files, int perFile, String namePad) {
        var list = new ArrayList<Node>();
        for (int f = 0; f < files; f++) {
            for (int i = 0; i < perFile; i++) {
                list.add(new Node("f" + f + "n" + i, NodeType.CLASS, "Sym" + i + namePad, "q",
                    "src/main/java/pkg" + f + "/File" + f + ".java", 1, "", 0L));
            }
        }
        return list;
    }

    @Test
    void aSmallReplyHasNoCapFooterAndAHeaderThatCountsShownFiles() {
        var reply = ContextReport.render("task", nodes(3, 2, ""), 10);
        assertThat(reply).contains("3 file(s)").doesNotContain("node(s)").doesNotContain("not shown")
            .doesNotContain("ranked lower");
    }

    @Test
    void aManyLongLinesReplyStaysUnderTheCapAndEndsWithAFooterNamingLimit() {
        var many = nodes(200, 6, "X".repeat(40));
        var reply = ContextReport.render("task", many, 200);
        assertThat(reply.length()).isLessThanOrEqualTo(6_000);
        var lines = reply.lines().toList();
        assertThat(lines.get(lines.size() - 1)).contains("file line(s) not shown").contains("`limit`");
        // Every file line shown is a complete line of a render that had room for all of it.
        var roomy = ContextReport.render("task", many.subList(0, 6 * 5), 5);
        var full = new java.util.HashSet<>(roomy.lines().toList());
        long fileLines = lines.stream().filter(l -> l.startsWith("  ")).count();
        assertThat(fileLines).isGreaterThan(5);
        lines.stream().filter(l -> l.startsWith("  ")).limit(5)
            .forEach(l -> assertThat(full).contains(l));
    }

    @Test
    void theCappedHeaderCountsTheFilesShown() {
        var reply = ContextReport.render("task", nodes(200, 6, "X".repeat(40)), 200);
        long fileLines = reply.lines().filter(l -> l.startsWith("  ")).count();
        assertThat(reply).contains(fileLines + " file(s)\n");
    }
}
