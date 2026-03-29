package com.whatdid.model;

import java.time.Instant;

public record Repo(
    long id,
    String path,
    String name,
    Instant registeredAt
) {}
