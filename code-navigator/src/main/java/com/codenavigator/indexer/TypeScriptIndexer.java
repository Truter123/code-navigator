package com.codenavigator.indexer;

import com.codenavigator.graph.Node;
import com.codenavigator.graph.NodeType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TypeScriptIndexer {

    private static final Pattern SERVICE_PATTERN = Pattern.compile(
            "@Injectable\\s*\\([^)]*\\)\\s*\\n?\\s*export\\s+class\\s+(\\w+)");

    private static final Pattern COMPONENT_PATTERN = Pattern.compile(
            "@Component\\s*\\(\\s*\\{[\\s\\S]*?\\}\\s*\\)\\s*export\\s+class\\s+(\\w+)", Pattern.DOTALL);

    private static final Pattern MODEL_PATTERN = Pattern.compile(
            "export\\s+interface\\s+(\\w+)");

    private static final Pattern API_URL_PATTERN = Pattern.compile(
            "apiUrl\\s*=\\s*[`'\"].*?(/[\\w-]+)[`'\"]");

    public List<Node> indexFile(Path file) {
        var nodes = new ArrayList<Node>();
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            return nodes;
        }

        var filePath = file.toString();
        long lastModified;
        try {
            lastModified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            lastModified = 0L;
        }

        // Detect services (Injectable + HttpClient)
        Matcher serviceMatcher = SERVICE_PATTERN.matcher(content);
        while (serviceMatcher.find()) {
            String className = serviceMatcher.group(1);
            if (content.contains("HttpClient")) {
                String snippet = buildSnippet(content, serviceMatcher.start());
                // Extract apiUrl if present
                Matcher urlMatcher = API_URL_PATTERN.matcher(content);
                if (urlMatcher.find()) {
                    snippet = snippet + "\napiUrl: " + urlMatcher.group(1);
                }
                nodes.add(new Node(className, NodeType.FE_SERVICE, className, className,
                        filePath, lineAt(content, serviceMatcher.start()), snippet, lastModified));
            }
        }

        // Detect components
        Matcher componentMatcher = COMPONENT_PATTERN.matcher(content);
        while (componentMatcher.find()) {
            String className = componentMatcher.group(1);
            String snippet = buildSnippet(content, componentMatcher.start());
            nodes.add(new Node(className, NodeType.FE_COMPONENT, className, className,
                    filePath, lineAt(content, componentMatcher.start()), snippet, lastModified));
        }

        // Detect models
        Matcher modelMatcher = MODEL_PATTERN.matcher(content);
        while (modelMatcher.find()) {
            String interfaceName = modelMatcher.group(1);
            String snippet = buildSnippet(content, modelMatcher.start());
            nodes.add(new Node(interfaceName, NodeType.FE_MODEL, interfaceName, interfaceName,
                    filePath, lineAt(content, modelMatcher.start()), snippet, lastModified));
        }

        return nodes;
    }

    private String buildSnippet(String content, int startPos) {
        var lines = content.substring(startPos).lines().limit(10).toList();
        return String.join("\n", lines);
    }

    private int lineAt(String content, int charIndex) {
        int line = 1;
        for (int i = 0; i < charIndex && i < content.length(); i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }
}
