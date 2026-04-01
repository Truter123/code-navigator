package com.agentmemory.model;

import java.util.List;

public record Anomaly(String type, String severity, String description, List<String> affectedKeys, String detectedAt) {}
