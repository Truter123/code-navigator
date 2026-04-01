package com.agentmemory.model;

import java.util.List;

public record Memory(
    String id, String key, String value, String agent, String project,
    boolean shared, double importance, String tagsText, String deletedAt,
    String createdAt, String updatedAt, List<String> tags
) {}
