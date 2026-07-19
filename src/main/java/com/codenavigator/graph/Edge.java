package com.codenavigator.graph;

public record Edge(
    String id, EdgeType type, String sourceId, String targetId
) {}
