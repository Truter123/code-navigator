package com.codenavigator.mcp;

import com.codenavigator.graph.Node;
import com.codenavigator.search.SearchService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The {@code cg_context} reply: one line per file with its best symbols inline, under its directory
 * line, held under the 6,000-character cap by dropping whole file lines from the lowest-ranked end.
 *
 * <p>The tool handler and the benchmark both build the reply here, so the benchmark measures exactly
 * the text a caller receives.
 */
public final class ContextReport {

    /** Hold the reply to the same cap every tool answer carries. */
    static final int MAX_LENGTH = 6_000;

    private static final int SYMBOLS_PER_FILE = 6;

    /** The reply text and the files its lines name, best first. */
    public record Reply(String text, List<String> files) {}

    private ContextReport() {}

    /** Search for the task and render the result: the one path from query to reply text. */
    public static Reply forTask(SearchService search, String task, int limit) {
        // Ask for one file past the limit: that says whether lower-ranked files were left out.
        var found = search.contextSearch(task, limit == Integer.MAX_VALUE ? limit : limit + 1);
        if (found.isEmpty()) return new Reply("No relevant nodes found for task: " + task, List.of());
        return build(task, found, limit);
    }

    /** The full reply text for already ranked nodes. */
    public static String render(String task, List<Node> rankedNodes, int limit) {
        return build(task, rankedNodes, limit).text();
    }

    private static Reply build(String task, List<Node> rankedNodes, int limit) {
        var foundByFile = rankedNodes.stream().collect(Collectors.groupingBy(
            Node::filePath, LinkedHashMap::new, Collectors.toList()));
        var entries = new ArrayList<>(foundByFile.entrySet().stream().limit(limit).toList());
        boolean moreRanked = foundByFile.size() > limit;

        for (int keep = entries.size(); keep >= 1; keep--) {
            var shown = entries.subList(0, keep);
            String text = text(task, shown, entries.size() - keep, moreRanked);
            if (text.length() <= MAX_LENGTH || keep == 1) {
                return new Reply(text, shown.stream().map(Map.Entry::getKey).toList());
            }
        }
        return new Reply(text(task, List.of(), 0, moreRanked), List.of());
    }

    private static String text(String task, List<Map.Entry<String, List<Node>>> shown, int capped,
                               boolean moreRanked) {
        var sb = new StringBuilder();
        sb.append("## Context for: ").append(task).append("\n");
        sb.append(shown.size()).append(" file(s)\n\n");
        DirectoryRows.append(sb, shown, Map.Entry::getKey,
            (entry, file) -> file + " — "
                + entry.getValue().stream()
                    .map(n -> n.type() + " " + n.name())
                    .limit(SYMBOLS_PER_FILE)
                    .collect(Collectors.joining(", "))
                + (entry.getValue().size() > SYMBOLS_PER_FILE
                    ? ", +" + (entry.getValue().size() - SYMBOLS_PER_FILE) : ""));
        if (capped > 0) {
            sb.append("\n… ").append(capped).append(" file line(s) not shown: a reply is capped at ")
              .append(MAX_LENGTH).append(" characters. Lower `limit`.\n");
        } else if (moreRanked) {
            sb.append("\n… more file(s) ranked lower. Raise `limit` or describe the task more narrowly.\n");
        }
        return sb.toString();
    }
}
