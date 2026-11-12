package com.codenavigator.graph;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The package of a node, derived from its qualified name.
 *
 * <p>A Java nested type has the id {@code <package>.<Outer>.<Inner>}, so "everything before the
 * last dot" names its outer type, not its package. The package of a nested type is the package of
 * its top-level outer type: trailing segments are stripped while the remaining prefix is itself the
 * id of an indexed Java type node. Other languages' ids are never stripped further, so they keep
 * the package they always had.
 */
public final class PackageNames {

    private final Set<String> javaTypeIds = new HashSet<>();

    public PackageNames(List<Node> nodes) {
        for (var n : nodes) {
            if (n.type() != NodeType.METHOD && n.filePath() != null && n.filePath().endsWith(".java")) {
                javaTypeIds.add(n.id());
            }
        }
    }

    public String of(String qualifiedName) {
        String pkg = cut(qualifiedName);
        while (javaTypeIds.contains(pkg)) {
            String outer = cut(pkg);
            if (outer.equals(pkg)) break;
            pkg = outer;
        }
        return pkg;
    }

    private static String cut(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot > 0 ? qualifiedName.substring(0, lastDot) : qualifiedName;
    }
}
