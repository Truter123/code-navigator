package com.whatdid.scanner;

import com.whatdid.model.Repo;
import com.whatdid.store.WhatDidStore;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

public class GitScanner {

    private final WhatDidStore store;

    public GitScanner(WhatDidStore store) {
        this.store = store;
    }

    public int scan(Repo repo) {
        int count = 0;
        try {
            var process = new ProcessBuilder(
                "git", "log", "--format=%H|%an|%aI|%s", "--shortstat", "--no-merges"
            ).directory(Path.of(repo.path()).toFile())
             .redirectErrorStream(true)
             .start();

            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                ParsedLogEntry currentEntry = null;

                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    if (line.contains("|") && !line.startsWith(" ")) {
                        if (currentEntry != null) {
                            store.saveCommit(repo.id(), currentEntry.hash, currentEntry.author,
                                currentEntry.message, currentEntry.filesChanged,
                                currentEntry.insertions, currentEntry.deletions, currentEntry.committedAt);
                            count++;
                        }
                        currentEntry = parseLogLine(line);
                    } else if (currentEntry != null && line.contains("changed")) {
                        parseShortstat(currentEntry, line);
                    }
                }
                if (currentEntry != null) {
                    store.saveCommit(repo.id(), currentEntry.hash, currentEntry.author,
                        currentEntry.message, currentEntry.filesChanged,
                        currentEntry.insertions, currentEntry.deletions, currentEntry.committedAt);
                    count++;
                }
            }
            process.waitFor();
        } catch (Exception e) {
            System.err.println("Failed to scan repo " + repo.path() + ": " + e.getMessage());
        }
        return count;
    }

    public int scanAll() {
        int total = 0;
        for (var repo : store.getAllRepos()) {
            total += scan(repo);
        }
        return total;
    }

    static ParsedLogEntry parseLogLine(String line) {
        var parts = line.split("\\|", 4);
        if (parts.length < 4) return null;

        try {
            String hash = parts[0].trim();
            String author = parts[1].trim();
            String dateStr = parts[2].trim();
            String rest = parts[3];

            String message;
            int filesChanged = 0, insertions = 0, deletions = 0;

            var lastParts = rest.split("\\|");
            if (lastParts.length >= 4) {
                try {
                    deletions = Integer.parseInt(lastParts[lastParts.length - 1].trim());
                    insertions = Integer.parseInt(lastParts[lastParts.length - 2].trim());
                    filesChanged = Integer.parseInt(lastParts[lastParts.length - 3].trim());
                    var msgParts = new String[lastParts.length - 3];
                    System.arraycopy(lastParts, 0, msgParts, 0, msgParts.length);
                    message = String.join("|", msgParts);
                } catch (NumberFormatException e) {
                    message = rest;
                }
            } else {
                message = rest;
            }

            Instant committedAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(dateStr, Instant::from);
            return new ParsedLogEntry(hash, author, message.trim(), filesChanged, insertions, deletions, committedAt);
        } catch (Exception e) {
            return null;
        }
    }

    private static void parseShortstat(ParsedLogEntry entry, String line) {
        var parts = line.split(",");
        for (var part : parts) {
            part = part.trim();
            if (part.contains("file")) {
                try { entry.filesChanged = Integer.parseInt(part.split(" ")[0]); } catch (NumberFormatException ignored) {}
            } else if (part.contains("insertion")) {
                try { entry.insertions = Integer.parseInt(part.split(" ")[0]); } catch (NumberFormatException ignored) {}
            } else if (part.contains("deletion")) {
                try { entry.deletions = Integer.parseInt(part.split(" ")[0]); } catch (NumberFormatException ignored) {}
            }
        }
    }

    static class ParsedLogEntry {
        String hash, author, message;
        int filesChanged, insertions, deletions;
        Instant committedAt;

        ParsedLogEntry(String hash, String author, String message, int filesChanged, int insertions, int deletions, Instant committedAt) {
            this.hash = hash;
            this.author = author;
            this.message = message;
            this.filesChanged = filesChanged;
            this.insertions = insertions;
            this.deletions = deletions;
            this.committedAt = committedAt;
        }

        String hash() { return hash; }
        String author() { return author; }
        String message() { return message; }
        int filesChanged() { return filesChanged; }
        int insertions() { return insertions; }
        int deletions() { return deletions; }
        Instant committedAt() { return committedAt; }
    }
}
