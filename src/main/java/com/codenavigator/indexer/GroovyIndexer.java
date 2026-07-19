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

/**
 * Regex-based indexer for Groovy CI/CD scripts. Each named class becomes a
 * {@link NodeType#GROOVY_SCRIPT} node (symbol = class name); a script file with no
 * class declaration becomes a single GROOVY_SCRIPT node (symbol = filename without
 * extension). No edges are extracted.
 */
public class GroovyIndexer {

    private static final Pattern CLASS_PATTERN = Pattern.compile(
            "(?:abstract\\s+)?\\bclass\\s+(\\w+)");

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

        Matcher cls = CLASS_PATTERN.matcher(content);
        while (cls.find()) {
            String name = cls.group(1);
            nodes.add(new Node(name, NodeType.GROOVY_SCRIPT, name, name, filePath,
                    lineAt(content, cls.start()), buildSnippet(content, cls.start()), lastModified));
        }

        if (nodes.isEmpty()) {
            String name = scriptName(file);
            nodes.add(new Node(name, NodeType.GROOVY_SCRIPT, name, name, filePath,
                    1, buildSnippet(content, 0), lastModified));
        }

        return nodes;
    }

    private static String scriptName(Path file) {
        String fn = file.getFileName().toString();
        int dot = fn.lastIndexOf('.');
        return dot > 0 ? fn.substring(0, dot) : fn;
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
