package com.whatdid.model;

import java.time.Instant;

public record Commit(
    long id,
    long repoId,
    String hash,
    String author,
    String message,
    int filesChanged,
    int insertions,
    int deletions,
    Instant committedAt,
    Instant scannedAt
) {}
