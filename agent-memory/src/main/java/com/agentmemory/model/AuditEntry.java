package com.agentmemory.model;

public record AuditEntry(String id, String agent, String operation, String memoryKey, String details, double latencyMs, String createdAt) {}
