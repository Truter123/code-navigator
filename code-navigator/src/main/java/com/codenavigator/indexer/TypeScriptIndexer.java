package com.codenavigator.indexer;

import com.codenavigator.graph.Node;
import com.codenavigator.graph.NodeType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex-based indexer for Angular/TypeScript source. Detection is file-content
 * scanned in a fixed priority order (see the 2026-06-24 full-coverage design):
 *
 *   1 @Pipe                                   -> FE_PIPE
 *   2 @Injectable + Interceptor marker        -> FE_INTERCEPTOR
 *   3 @Injectable + Guard/CanActivate marker  -> FE_GUARD
 *   4/5 @Injectable (any remaining)           -> FE_SERVICE
 *   6 @Component                              -> FE_COMPONENT
 *   7 export interface                        -> FE_MODEL
 *   8 export class (no decorator in file)     -> FE_CLASS
 *   9 export enum                             -> FE_ENUM
 *  10 export const (module level)             -> FE_CONSTANT
 *  11 ValidatorFn body OR *.validator.ts      -> FE_VALIDATOR
 *
 * Rules 1-7 are exclusive per class; rules 8-11 are non-exclusive (one file may
 * yield several nodes). A validator symbol is never also emitted as FE_CONSTANT.
 */
public class TypeScriptIndexer {

    private static final Pattern PIPE_PATTERN = Pattern.compile(
            "@Pipe\\s*(?:\\([\\s\\S]*?\\))?\\s*export\\s+class\\s+(\\w+)");
    private static final Pattern INJECTABLE_PATTERN = Pattern.compile(
            "@Injectable\\s*(?:\\([\\s\\S]*?\\))?\\s*export\\s+class\\s+(\\w+)([^{]*)\\{");
    private static final Pattern COMPONENT_PATTERN = Pattern.compile(
            "@Component\\s*\\(\\s*\\{[\\s\\S]*?\\}\\s*\\)\\s*export\\s+class\\s+(\\w+)", Pattern.DOTALL);
    private static final Pattern MODEL_PATTERN = Pattern.compile(
            "export\\s+interface\\s+(\\w+)");
    private static final Pattern CLASS_PATTERN = Pattern.compile(
            "export\\s+class\\s+(\\w+)");
    private static final Pattern ENUM_PATTERN = Pattern.compile(
            "export\\s+enum\\s+(\\w+)");
    private static final Pattern CONST_PATTERN = Pattern.compile(
            "export\\s+const\\s+(\\w+)");
    private static final Pattern VALIDATOR_DECL_PATTERN = Pattern.compile(
            "export\\s+(?:const|function)\\s+(\\w+)([^=;{]*)");
    private static final Pattern EXPORT_SYMBOL_PATTERN = Pattern.compile(
            "export\\s+(?:const|function|class)\\s+(\\w+)");
    private static final Pattern ANGULAR_DECORATOR_PATTERN = Pattern.compile(
            "@(?:Component|Injectable|Pipe|Directive|NgModule)\\b");
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

        boolean fileHasDecorator = ANGULAR_DECORATOR_PATTERN.matcher(content).find();

        // 1. @Pipe -> FE_PIPE
        Matcher pipe = PIPE_PATTERN.matcher(content);
        while (pipe.find()) {
            nodes.add(node(pipe.group(1), NodeType.FE_PIPE, content, pipe.start(), filePath, lastModified));
        }

        // 2-5. @Injectable -> FE_INTERCEPTOR / FE_GUARD / FE_SERVICE
        Matcher inj = INJECTABLE_PATTERN.matcher(content);
        while (inj.find()) {
            String name = inj.group(1);
            String clause = inj.group(2);
            NodeType type;
            if (name.contains("Interceptor") || clause.contains("Interceptor")) {
                type = NodeType.FE_INTERCEPTOR;
            } else if (name.contains("Guard") || clause.contains("Guard") || clause.contains("CanActivate")) {
                type = NodeType.FE_GUARD;
            } else {
                type = NodeType.FE_SERVICE;
            }
            String snippet = buildSnippet(content, inj.start());
            if (type == NodeType.FE_SERVICE) {
                Matcher url = API_URL_PATTERN.matcher(content);
                if (url.find()) {
                    snippet = snippet + "\napiUrl: " + url.group(1);
                }
            }
            nodes.add(new Node(name, type, name, name, filePath,
                    lineAt(content, inj.start()), snippet, lastModified));
        }

        // 6. @Component -> FE_COMPONENT
        Matcher comp = COMPONENT_PATTERN.matcher(content);
        while (comp.find()) {
            nodes.add(node(comp.group(1), NodeType.FE_COMPONENT, content, comp.start(), filePath, lastModified));
        }

        // 7. export interface -> FE_MODEL
        Matcher model = MODEL_PATTERN.matcher(content);
        while (model.find()) {
            nodes.add(node(model.group(1), NodeType.FE_MODEL, content, model.start(), filePath, lastModified));
        }

        // 8. export class with no Angular decorator anywhere in the file -> FE_CLASS
        if (!fileHasDecorator) {
            Matcher cls = CLASS_PATTERN.matcher(content);
            while (cls.find()) {
                nodes.add(node(cls.group(1), NodeType.FE_CLASS, content, cls.start(), filePath, lastModified));
            }
        }

        // 9. export enum -> FE_ENUM
        Matcher en = ENUM_PATTERN.matcher(content);
        while (en.find()) {
            nodes.add(node(en.group(1), NodeType.FE_ENUM, content, en.start(), filePath, lastModified));
        }

        // 11. validators (resolved before consts so a validator is never also FE_CONSTANT)
        Set<String> validatorNames = new HashSet<>();
        Matcher vd = VALIDATOR_DECL_PATTERN.matcher(content);
        while (vd.find()) {
            if (vd.group(2).contains("ValidatorFn")) {
                validatorNames.add(vd.group(1));
                nodes.add(node(vd.group(1), NodeType.FE_VALIDATOR, content, vd.start(), filePath, lastModified));
            }
        }
        if (filePath.endsWith(".validator.ts") && validatorNames.isEmpty()) {
            Matcher sym = EXPORT_SYMBOL_PATTERN.matcher(content);
            if (sym.find()) {
                validatorNames.add(sym.group(1));
                nodes.add(node(sym.group(1), NodeType.FE_VALIDATOR, content, sym.start(), filePath, lastModified));
            } else {
                String derived = filenameValidatorSymbol(file);
                nodes.add(new Node(derived, NodeType.FE_VALIDATOR, derived, derived, filePath, 1, "", lastModified));
            }
        }

        // 10. export const at module level -> FE_CONSTANT (one per const, excluding validators)
        Matcher cst = CONST_PATTERN.matcher(content);
        while (cst.find()) {
            String name = cst.group(1);
            if (validatorNames.contains(name)) {
                continue;
            }
            nodes.add(node(name, NodeType.FE_CONSTANT, content, cst.start(), filePath, lastModified));
        }

        return nodes;
    }

    private Node node(String name, NodeType type, String content, int start, String filePath, long lastModified) {
        return new Node(name, type, name, name, filePath, lineAt(content, start),
                buildSnippet(content, start), lastModified);
    }

    /** Derives a PascalCase symbol from a *.validator.ts filename, e.g. split.validator.ts -> SplitValidator. */
    private static String filenameValidatorSymbol(Path file) {
        String fn = file.getFileName().toString();
        int cut = fn.indexOf(".validator.ts");
        String base = cut >= 0 ? fn.substring(0, cut) : fn;
        var sb = new StringBuilder();
        boolean upper = true;
        for (char c : base.toCharArray()) {
            if (c == '-' || c == '_' || c == '.') {
                upper = true;
                continue;
            }
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        sb.append("Validator");
        return sb.toString();
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
