package com.codenavigator.indexer;

import com.codenavigator.graph.Edge;
import com.codenavigator.graph.EdgeType;
import com.codenavigator.graph.GraphStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Member types inherited from supertypes declared in other files and packages, through a full index. */
class InheritedMemberTypeTest {

    @TempDir Path tempDir;

    private List<Edge> index(String... pathsAndSources) throws IOException {
        Path root = tempDir.resolve("proj");
        for (int i = 0; i < pathsAndSources.length; i += 2) {
            Path f = root.resolve("src").resolve(pathsAndSources[i]);
            Files.createDirectories(f.getParent());
            Files.writeString(f, pathsAndSources[i + 1]);
        }
        try (var store = new GraphStore(tempDir.resolve("g.db"))) {
            new ProjectIndexer(store).indexFull(root);
            return store.getAllEdges();
        }
    }

    private static List<String> targets(List<Edge> edges, EdgeType type, String sourcePrefix) {
        return edges.stream().filter(e -> e.type() == type && e.sourceId().startsWith(sourcePrefix))
            .map(Edge::targetId).toList();
    }

    @Test
    void aClassBindsTheMemberTypesOfAnInterfaceFromAnotherPackageOverItsOwnPackagesNestedType() throws IOException {
        var edges = index(
            "api/MaterialReads.java", "package api; public interface MaterialReads { record LineEnd(int day) {} }",
            "impl/Other.java", "package impl; public class Other { static class LineEnd {} }",
            "impl/Adapter.java", "package impl; import api.MaterialReads; public class Adapter implements MaterialReads {"
                + " Object make() { return new LineEnd(1); } LineEnd get() { return null; } }");

        assertThat(targets(edges, EdgeType.CALLS_METHOD, "impl.Adapter")).containsExactly("api.MaterialReads.LineEnd");
        assertThat(targets(edges, EdgeType.RETURNS_TYPE, "impl.Adapter")).containsExactly("api.MaterialReads.LineEnd");
    }

    @Test
    void aMemberTypeInheritedThroughASuperclassInAnotherFileBindsTheCall() throws IOException {
        var edges = index(
            "p/CmdInterceptor.java", "package p; public interface CmdInterceptor { interface Chain { void proceed(); } }",
            "p/QryInterceptor.java", "package p; public interface QryInterceptor { interface Chain { void proceed(); } }",
            "p/Base.java", "package p; public class Base implements CmdInterceptor {}",
            "p/Audit2.java", "package p; public class Audit2 extends Base {"
                + " void intercept(Chain chain) { chain.proceed(); } }");

        assertThat(targets(edges, EdgeType.CALLS, "p.Audit2#intercept")).containsExactly("p.CmdInterceptor.Chain#proceed()");
    }

    @Test
    void anInheritedMemberTypeBeatsAnExplicitImportAndAnOwnMemberTypeBeatsBoth() throws IOException {
        var edges = index(
            "api/Reads.java", "package api; public interface Reads { interface Entry { void touch(); } }",
            "other/Entry.java", "package other; public class Entry { public void touch() {} }",
            "c/Impl.java", "package c; import api.Reads; import other.Entry; public class Impl implements Reads {"
                + " Entry e; void go() { e.touch(); } }",
            "c/Own.java", "package c; import api.Reads; import other.Entry; public class Own implements Reads {"
                + " Entry e; void go() { e.touch(); } static class Entry { void touch() {} } }");

        assertThat(targets(edges, EdgeType.CALLS, "c.Impl#go")).containsExactly("api.Reads.Entry#touch()");
        assertThat(targets(edges, EdgeType.CALLS, "c.Own#go")).containsExactly("c.Own.Entry#touch()");
    }
}
