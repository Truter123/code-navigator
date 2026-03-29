package com.whatdid.model;

import java.time.Instant;

public record Session(
    long id,
    Instant createdAt,
    String note
) {}
