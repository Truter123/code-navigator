package com.agentmemory.model;

public record MemoryLink(String id, String sourceId, String targetId, String relation, String createdAt) {}
