package com.codenavigator.mcp;

import com.codenavigator.graph.Node;
import com.codenavigator.graph.NodeType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DirectoryRowsTest {

    private static final String SALES = "src/main/java/com/nlp/domain/sales/salesorder/";

    private static Node node(NodeType type, String name, String path, int line) {
        return new Node("id:" + name, type, name, "id:" + name, path, line, "", 0);
    }

    private static String render(boolean withType, Node... nodes) {
        var sb = new StringBuilder();
        DirectoryRows.appendNodes(sb, List.of(nodes), withType);
        return sb.toString();
    }

    @Test
    void rowsSharingADirectorySitUnderOneDirectoryLine() {
        var reply = render(true,
            node(NodeType.AGGREGATE, "SalesOrder", SALES + "SalesOrder.java", 21),
            node(NodeType.RECORD, "SalesOrderId", SALES + "SalesOrderId.java", 7));

        assertThat(reply).isEqualTo(SALES + "\n"
            + "  SalesOrder.java:21 SalesOrder AGGREGATE\n"
            + "  SalesOrderId.java:7 SalesOrderId RECORD\n");
    }

    @Test
    void aListAlreadyGroupedByTypeLeavesTheTypeWordOffTheRow() {
        var reply = render(false, node(NodeType.AGGREGATE, "SalesOrder", SALES + "SalesOrder.java", 21));

        assertThat(reply).isEqualTo(SALES + "\n  SalesOrder.java:21 SalesOrder\n");
    }

    @Test
    void rankOrderSurvivesGroupingAndADirectoryThatComesBackGetsItsLineAgain() {
        var reply = render(true,
            node(NodeType.CLASS, "First", "a/b/First.java", 1),
            node(NodeType.CLASS, "Second", "c/Second.java", 2),
            node(NodeType.CLASS, "Third", "a/b/Third.java", 3));

        assertThat(reply).isEqualTo(
            "a/b/\n  First.java:1 First CLASS\n"
            + "c/\n  Second.java:2 Second CLASS\n"
            + "a/b/\n  Third.java:3 Third CLASS\n");
    }

    @Test
    void aFileInTheRepositoryRootSitsUnderDotSlash() {
        var reply = render(true,
            node(NodeType.CLASS, "Root", "Root.java", 4),
            node(NodeType.CLASS, "Other", "Other.java", 5));

        assertThat(reply).isEqualTo("./\n  Root.java:4 Root CLASS\n  Other.java:5 Other CLASS\n");
    }

    @Test
    void aNodeWithNoFileHasNoLocationAndEndsTheGroupAboveIt() {
        var reply = render(true,
            node(NodeType.CLASS, "First", "a/First.java", 1),
            node(NodeType.LIBRARY, "RequestMapping", "", 0),
            node(NodeType.CLASS, "Second", "a/Second.java", 2));

        assertThat(reply).isEqualTo(
            "a/\n  First.java:1 First CLASS\n"
            + "RequestMapping LIBRARY\n"
            + "a/\n  Second.java:2 Second CLASS\n");
    }
}
