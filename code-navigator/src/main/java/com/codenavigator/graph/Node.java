package com.codenavigator.graph;

public record Node(
    String id, NodeType type, String name, String qualifiedName,
    String filePath, int lineNumber, String codeSnippet, long lastModified
) {}
