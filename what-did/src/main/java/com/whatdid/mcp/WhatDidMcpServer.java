package com.whatdid.mcp;

import com.whatdid.model.Commit;
import com.whatdid.model.Repo;
import com.whatdid.scanner.GitScanner;
import com.whatdid.store.WhatDidStore;
import tools.jackson.databind.json.JsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

public class WhatDidMcpServer {

    private static final int MAX_OUTPUT_LENGTH = 15_000;

    private final WhatDidStore store;
    private final GitScanner scanner;

    public WhatDidMcpServer(WhatDidStore store, GitScanner scanner) {
        this.store = store;
        this.scanner = scanner;
    }

    public void start() {
        var transportProvider = new StdioServerTransportProvider(new JacksonMcpJsonMapper(JsonMapper.builder().build()));

        var server = McpServer.sync(transportProvider)
            .serverInfo("what-did", "0.1.0")
            .toolCall(
                Tool.builder()
                    .name("whatdid_register")
                    .description("Register a git repo to track. Provide the absolute path.")
                    .inputSchema(jsonSchema(
                        Map.of("path", propString("Absolute path to the git repo"),
                               "name", propString("Optional short name for the repo")),
                        List.of("path")))
                    .build(),
                (exchange, request) -> textResult(handleRegister(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("whatdid_repos")
                    .description("List all registered repos with last scan info.")
                    .inputSchema(jsonSchema(Map.of(), List.of()))
                    .build(),
                (exchange, request) -> textResult(handleRepos())
            )
            .toolCall(
                Tool.builder()
                    .name("whatdid_scan")
                    .description("Scan all registered repos for new commits.")
                    .inputSchema(jsonSchema(Map.of(), List.of()))
                    .build(),
                (exchange, request) -> textResult(handleScan())
            )
            .toolCall(
                Tool.builder()
                    .name("whatdid_save")
                    .description("Create a session checkpoint. Groups all unsaved commits into a session.")
                    .inputSchema(jsonSchema(
                        Map.of("note", propString("Optional note for this session")),
                        List.of()))
                    .build(),
                (exchange, request) -> textResult(handleSave(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("whatdid_session")
                    .description("View changes grouped by session. Shows latest session if no id given.")
                    .inputSchema(jsonSchema(
                        Map.of("session_id", propInt("Optional session ID to view")),
                        List.of()))
                    .build(),
                (exchange, request) -> textResult(handleSession(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("whatdid_repo")
                    .description("View changes grouped by repo. Optionally filter by repo name or time range.")
                    .inputSchema(jsonSchema(
                        Map.of("name", propString("Optional repo name filter"),
                               "since", propString("Optional date filter (e.g. '2026-03-28' or '3d' for 3 days ago)")),
                        List.of()))
                    .build(),
                (exchange, request) -> textResult(handleRepo(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("whatdid_timeline")
                    .description("Chronological view of all commits across repos.")
                    .inputSchema(jsonSchema(
                        Map.of("since", propString("Optional date filter (e.g. '2026-03-28' or '7d' for 7 days ago)"),
                               "limit", propInt("Max commits to show (default 50)")),
                        List.of()))
                    .build(),
                (exchange, request) -> textResult(handleTimeline(request.arguments()))
            )
            .toolCall(
                Tool.builder()
                    .name("whatdid_meeting")
                    .description("Meeting-ready summary. Combines all views with stats.")
                    .inputSchema(jsonSchema(
                        Map.of("since", propString("Optional date filter (e.g. '2026-03-28' or '7d' for 7 days ago)")),
                        List.of()))
                    .build(),
                (exchange, request) -> textResult(handleMeeting(request.arguments()))
            )
            .build();

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            try { store.close(); } catch (Exception ignored) {}
            server.close();
        }
    }

    String handleRegister(Map<String, Object> args) {
        String path = (String) args.get("path");
        String name = args.containsKey("name") ? (String) args.get("name") : inferRepoName(path);
        var repo = store.saveRepo(path, name);
        int count = scanner.scan(repo);
        return "Registered repo **" + repo.name() + "** at `" + repo.path() + "`\nScanned " + count + " commit(s).";
    }

    String handleRepos() {
        var repos = store.getAllRepos();
        if (repos.isEmpty()) return "No repos registered. Use `whatdid_register` to add one.";

        var sb = new StringBuilder("## Registered Repos\n\n");
        for (var repo : repos) {
            var commits = store.getCommitsByRepo(repo.id());
            sb.append("- **").append(repo.name()).append("** — `").append(repo.path()).append("`")
              .append(" (").append(commits.size()).append(" commits)\n");
        }
        return sb.toString();
    }

    String handleScan() {
        int count = scanner.scanAll();
        return "Scanned all repos. Found " + count + " new commit(s).";
    }

    String handleSave(Map<String, Object> args) {
        scanner.scanAll();
        String note = args.containsKey("note") ? (String) args.get("note") : null;
        var session = store.createSession(note);
        store.linkUnsavedCommitsToSession(session.id());
        var commits = store.getCommitsBySession(session.id());
        return "Session #" + session.id() + " saved with " + commits.size() + " commit(s)."
            + (note != null ? " Note: " + note : "");
    }

    String handleSession(Map<String, Object> args) {
        scanner.scanAll();
        var sessions = store.getAllSessions();
        if (sessions.isEmpty()) return "No sessions yet. Use `whatdid_save` to create one.";

        long sessionId;
        if (args.containsKey("session_id")) {
            sessionId = ((Number) args.get("session_id")).longValue();
        } else {
            sessionId = sessions.get(0).id();
        }

        var session = sessions.stream().filter(s -> s.id() == sessionId).findFirst().orElse(null);
        if (session == null) return "Session #" + sessionId + " not found.";

        var commits = store.getCommitsBySession(sessionId);
        var sb = new StringBuilder("## Session #" + sessionId);
        if (session.note() != null) sb.append(" — ").append(session.note());
        sb.append("\n").append("_").append(formatDate(session.createdAt())).append("_\n\n");

        if (commits.isEmpty()) {
            sb.append("No commits in this session.\n");
        } else {
            appendCommitsByRepo(sb, commits);
        }
        return sb.toString();
    }

    String handleRepo(Map<String, Object> args) {
        scanner.scanAll();
        String nameFilter = args.containsKey("name") ? (String) args.get("name") : null;
        Instant since = parseSince(args.containsKey("since") ? (String) args.get("since") : null);

        var repos = store.getAllRepos();
        if (nameFilter != null) {
            String lower = nameFilter.toLowerCase();
            repos = repos.stream().filter(r -> r.name().toLowerCase().contains(lower)).toList();
        }

        if (repos.isEmpty()) return "No matching repos found.";

        var sb = new StringBuilder("## Changes by Repo\n\n");
        for (var repo : repos) {
            var commits = since != null
                ? store.getCommitsByRepo(repo.id()).stream().filter(c -> c.committedAt().isAfter(since)).toList()
                : store.getCommitsByRepo(repo.id());

            if (commits.isEmpty()) continue;
            sb.append("### ").append(repo.name()).append("\n");
            appendCommitList(sb, commits);
            sb.append("\n");
        }
        return sb.toString();
    }

    String handleTimeline(Map<String, Object> args) {
        scanner.scanAll();
        Instant since = parseSince(args.containsKey("since") ? (String) args.get("since") : null);
        int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 50;

        var commits = since != null ? store.getCommitsSince(since) : store.getAllCommits();
        if (commits.size() > limit) commits = commits.subList(0, limit);

        if (commits.isEmpty()) return "No commits found.";

        var sb = new StringBuilder("## Timeline\n\n");
        for (var commit : commits) {
            var repo = store.getRepoById(commit.repoId());
            String repoName = repo != null ? repo.name() : "unknown";
            sb.append("- `").append(formatDate(commit.committedAt())).append("` ")
              .append("**[").append(repoName).append("]** ")
              .append(commit.hash(), 0, Math.min(7, commit.hash().length())).append(" ")
              .append(commit.message())
              .append(" (+").append(commit.insertions()).append("/-").append(commit.deletions()).append(")")
              .append("\n");
        }
        return sb.toString();
    }

    String handleMeeting(Map<String, Object> args) {
        scanner.scanAll();
        Instant since = parseSince(args.containsKey("since") ? (String) args.get("since") : null);
        if (since == null) since = Instant.now().minus(1, ChronoUnit.DAYS);

        var commits = store.getCommitsSince(since);
        if (commits.isEmpty()) return "No activity found since " + formatDate(since) + ".";

        var sb = new StringBuilder("## Meeting Summary\n");
        sb.append("_Since ").append(formatDate(since)).append("_\n\n");

        int totalFiles = commits.stream().mapToInt(Commit::filesChanged).sum();
        int totalInsertions = commits.stream().mapToInt(Commit::insertions).sum();
        int totalDeletions = commits.stream().mapToInt(Commit::deletions).sum();
        var repoIds = commits.stream().map(Commit::repoId).collect(Collectors.toSet());

        sb.append("**Stats:** ").append(commits.size()).append(" commits across ")
          .append(repoIds.size()).append(" repo(s) | ")
          .append(totalFiles).append(" files changed | ")
          .append("+").append(totalInsertions).append("/-").append(totalDeletions).append("\n\n");

        sb.append("### By Repo\n\n");
        var byRepo = commits.stream().collect(Collectors.groupingBy(Commit::repoId, LinkedHashMap::new, Collectors.toList()));
        for (var entry : byRepo.entrySet()) {
            var repo = store.getRepoById(entry.getKey());
            String repoName = repo != null ? repo.name() : "unknown";
            sb.append("**").append(repoName).append("** (").append(entry.getValue().size()).append(" commits)\n");
            appendCommitList(sb, entry.getValue());
            sb.append("\n");
        }

        var sessions = store.getAllSessions();
        if (!sessions.isEmpty()) {
            Instant finalSince = since;
            var recentSessions = sessions.stream()
                .filter(s -> s.createdAt().isAfter(finalSince))
                .toList();
            if (!recentSessions.isEmpty()) {
                sb.append("### Sessions\n\n");
                for (var session : recentSessions) {
                    var sessionCommits = store.getCommitsBySession(session.id());
                    sb.append("- **Session #").append(session.id()).append("**");
                    if (session.note() != null) sb.append(" — ").append(session.note());
                    sb.append(" (").append(sessionCommits.size()).append(" commits, ")
                      .append(formatDate(session.createdAt())).append(")\n");
                }
                sb.append("\n");
            }
        }

        sb.append("### Timeline\n\n");
        for (var commit : commits.stream().limit(20).toList()) {
            var repo = store.getRepoById(commit.repoId());
            String repoName = repo != null ? repo.name() : "unknown";
            sb.append("- `").append(formatDate(commit.committedAt())).append("` ")
              .append("**[").append(repoName).append("]** ")
              .append(commit.message()).append("\n");
        }
        if (commits.size() > 20) {
            sb.append("- _... and ").append(commits.size() - 20).append(" more commits_\n");
        }

        return sb.toString();
    }

    private void appendCommitsByRepo(StringBuilder sb, List<Commit> commits) {
        var byRepo = commits.stream().collect(Collectors.groupingBy(Commit::repoId, LinkedHashMap::new, Collectors.toList()));
        for (var entry : byRepo.entrySet()) {
            var repo = store.getRepoById(entry.getKey());
            String repoName = repo != null ? repo.name() : "unknown";
            sb.append("**").append(repoName).append(":**\n");
            appendCommitList(sb, entry.getValue());
            sb.append("\n");
        }
    }

    private void appendCommitList(StringBuilder sb, List<Commit> commits) {
        for (var c : commits) {
            sb.append("- ").append(c.hash(), 0, Math.min(7, c.hash().length()))
              .append(" ").append(c.message())
              .append(" (+").append(c.insertions()).append("/-").append(c.deletions()).append(")")
              .append("\n");
        }
    }

    private static String inferRepoName(String path) {
        var p = java.nio.file.Path.of(path);
        return p.getFileName().toString();
    }

    static Instant parseSince(String since) {
        if (since == null) return null;
        if (since.matches("\\d+d")) {
            int days = Integer.parseInt(since.replace("d", ""));
            return Instant.now().minus(days, ChronoUnit.DAYS);
        }
        if (since.matches("\\d+h")) {
            int hours = Integer.parseInt(since.replace("h", ""));
            return Instant.now().minus(hours, ChronoUnit.HOURS);
        }
        try {
            return LocalDate.parse(since).atStartOfDay(ZoneId.systemDefault()).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private static String formatDate(Instant instant) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .format(instant.atZone(ZoneId.systemDefault()));
    }

    private static String truncateOutput(String text) {
        if (text.length() <= MAX_OUTPUT_LENGTH) return text;
        return text.substring(0, MAX_OUTPUT_LENGTH) + "\n\n... (output truncated at " + MAX_OUTPUT_LENGTH + " chars)\n";
    }

    private static CallToolResult textResult(String text) {
        return new CallToolResult(List.of(new TextContent(truncateOutput(text))), false, null, null);
    }

    private static JsonSchema jsonSchema(Map<String, Object> properties, List<String> required) {
        return new JsonSchema("object", properties, required, null, null, null);
    }

    private static Map<String, Object> propString(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> propInt(String description) {
        return Map.of("type", "integer", "description", description);
    }
}
