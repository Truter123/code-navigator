package com.codenavigator.mcp;

import com.codenavigator.graph.Node;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * List rows under their directory: the directory once, then one short row per file.
 *
 * <p>On {@code nlp-be} the repo-relative path was 53-85 % of a list reply, a quarter of that the
 * same directory repeated row after row, and the {@code **TYPE**} and backtick markup another
 * 9-15 %. A row now carries only {@code File.java:line}; the directory line above it plus the file
 * name rebuild the full repo-relative path, so nothing a caller could type back in is lost.
 *
 * <p>Only <em>consecutive</em> rows share a directory line. Lists are ranked best-first, and
 * gathering a directory's rows together would lift a weak row above a better one; a directory that
 * comes back after another one interrupted it simply gets its line again.
 */
final class DirectoryRows {

    /** Directory line of a file that sits in the repository root. */
    private static final String ROOT = "./";

    private DirectoryRows() {}

    /**
     * Node rows, {@code File.java:line Name}, each followed by its type as a plain word unless the
     * list is already grouped by type and says it once per group.
     */
    static void appendNodes(StringBuilder sb, List<Node> nodes, boolean withType) {
        append(sb, nodes, Node::filePath, (node, file) -> nodeRow(node, file, withType));
    }

    /** One node's row text. A node with no file (a LIBRARY, say) has no location to print. */
    static String nodeRow(Node node, String file, boolean withType) {
        String where = file.isEmpty() ? "" : file + ":" + node.lineNumber() + " ";
        return where + node.name() + (withType ? " " + node.type() : "");
    }

    /**
     * One row per item, in the order given. {@code row} gets the item and its file name (the path
     * after the last separator) and returns the row text without indent or newline. An item with
     * no path gets no directory line and no indent, and ends the group above it.
     */
    static <T> void append(StringBuilder sb, Iterable<T> items, Function<T, String> pathOf,
                           BiFunction<T, String, String> row) {
        String current = null;
        for (T item : items) {
            String path = pathOf.apply(item);
            if (path == null || path.isEmpty()) {
                sb.append(row.apply(item, "")).append('\n');
                current = null;
                continue;
            }
            int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1;
            String dir = path.substring(0, cut);
            if (!dir.equals(current)) {
                sb.append(dir.isEmpty() ? ROOT : dir).append('\n');
                current = dir;
            }
            sb.append("  ").append(row.apply(item, path.substring(cut))).append('\n');
        }
    }
}
