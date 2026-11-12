package com.codenavigator.indexer;

import com.codenavigator.graph.Edge;
import com.codenavigator.graph.EdgeType;
import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.Node;
import com.codenavigator.graph.NodeType;
import com.codenavigator.graph.Project;
import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stored facts a single-file re-extraction relies on: the return class of each method, the
 * shape hash of each file, an extractor that sees the same maps whatever LIBRARY nodes exist, and
 * edge picks that do not depend on row order.
 */
class ReturnClassAndShapeStoreTest {

    @TempDir Path tempDir;

    private Path root;

    /** B sorts after A, so a full run parses it later than the file whose method returns it. */
    private Path fixture() throws IOException {
        root = tempDir.resolve("fx");
        Path acme = root.resolve("src/main/java/com/acme");
        Path zzz = root.resolve("src/main/java/com/zzz");
        Files.createDirectories(acme);
        Files.createDirectories(zzz);
        Files.writeString(root.resolve("build.gradle"), """
            dependencies {
                implementation 'org.springframework.boot:spring-boot-starter-web:3.2.0'
            }
            """);
        Files.writeString(acme.resolve("A.java"), """
            package com.acme;
            import com.zzz.B;
            import java.util.List;
            public class A {
                public B b() { return new B(); }
                public List<B> list() { return null; }
                public String s() { return ""; }
                public void v() { }
                public <T> T t() { return null; }
            }
            """);
        Files.writeString(zzz.resolve("B.java"), """
            package com.zzz;
            public class B { public void c() { } }
            """);
        Files.writeString(acme.resolve("D.java"), """
            package com.acme;
            import org.springframework.stereotype.Service;
            @Service
            public class D {
                private final A a;
                public D(A a) { this.a = a; }
                public void run() { a.b().c(); }
            }
            """);
        return root;
    }

    private static List<String> triples(List<Edge> edges) {
        var out = new ArrayList<String>();
        for (Edge e : edges) out.add(e.sourceId() + " -> " + e.targetId() + " [" + e.type() + "]");
        out.sort(null);
        return out;
    }

    private List<Edge> storedCallEdges(GraphStore store, String sourcePrefix) {
        return store.getAllEdges().stream()
            .filter(e -> e.type() == EdgeType.CALLS && e.sourceId().startsWith(sourcePrefix)).toList();
    }

    @Test
    void returnClassIsStoredEvenWhenTheClassIsParsedLaterAndOnlyForASingleIndexedType() throws IOException {
        fixture();
        try (var store = new GraphStore(tempDir.resolve("a.db"))) {
            var indexer = new ProjectIndexer(store);
            indexer.indexFull(root);
            var first = store.getAllReturnClasses();

            assertThat(first).containsEntry("com.acme.A#b()", "com.zzz.B");
            assertThat(first).doesNotContainKeys("com.acme.A#list()", "com.acme.A#s()",
                "com.acme.A#v()", "com.acme.A#t()");

            indexer.indexFull(root);
            assertThat(store.getAllReturnClasses()).isEqualTo(first);
        }
    }

    @Test
    void anExtractorThatParsesOnlyTheCallerBindsTheSameChainEdgesAsAFullRun() throws IOException {
        fixture();
        try (var store = new GraphStore(tempDir.resolve("a.db"))) {
            new ProjectIndexer(store).indexFull(root);
            var full = triples(storedCallEdges(store, "com.acme.D#"));
            assertThat(full).contains("com.acme.D#run() -> com.zzz.B#c() [CALLS]");
            assertThat(store.findNodesByType(NodeType.LIBRARY)).isNotEmpty();

            var extractor = new MethodCallExtractor(store);
            var edges = new ArrayList<>(extractor.extract(StaticJavaParser.parse(
                root.resolve("src/main/java/com/acme/D.java"))));
            edges.addAll(extractor.resolvePending());

            assertThat(triples(edges.stream().filter(e -> e.type() == EdgeType.CALLS).toList()))
                .isEqualTo(full);
        }
    }

    @Test
    void libraryAndShellNodesDoNotChangeWhatTheExtractorBinds() throws IOException {
        fixture();
        try (var store = new GraphStore(tempDir.resolve("a.db"))) {
            new ProjectIndexer(store).indexFull(root);
            var fullRun = triples(store.getAllEdges().stream()
                .filter(e -> e.type() == EdgeType.CALLS || e.type() == EdgeType.OVERRIDES).toList());

            store.saveNode(new Node("lib:ghost.B", NodeType.LIBRARY, "B", "ghost.B", "", 0, "", 0L));
            store.saveNode(new Node("shell:b", NodeType.SHELL_COMMAND, "B", "b", "ops/b.sh", 1, "", 0L));
            var withExtras = allCalls(store);

            for (Node n : store.findNodesByType(NodeType.LIBRARY)) store.deleteNode(n.id());
            for (Node n : store.findNodesByType(NodeType.SHELL_COMMAND)) store.deleteNode(n.id());
            var without = allCalls(store);

            assertThat(withExtras).isEqualTo(without).isEqualTo(fullRun);
        }
    }

    private List<String> allCalls(GraphStore store) throws IOException {
        var extractor = new MethodCallExtractor(store);
        var edges = new ArrayList<Edge>();
        try (var files = Files.walk(root.resolve("src"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                edges.addAll(extractor.extract(StaticJavaParser.parse(p)));
            }
        }
        edges.addAll(extractor.resolvePending());
        edges.addAll(extractor.deriveOverrides());
        return triples(edges.stream()
            .filter(e -> e.type() == EdgeType.CALLS || e.type() == EdgeType.OVERRIDES).toList());
    }

    @Test
    void anAggregateClashIsResolvedByIdNotByRow() {
        var cu = StaticJavaParser.parse("""
            package com.acme;
            public class PlaceHandler { void handle(String id) { salesOrderRepository.findById(id); } }
            """);
        var handler = new Node("com.acme.PlaceHandler", NodeType.COMMAND_HANDLER, "PlaceHandler",
            "com.acme.PlaceHandler", "PlaceHandler.java", 1, "", 0L);
        var order = new Node("com.acme.Order", NodeType.AGGREGATE, "Order", "com.acme.Order", "Order.java", 1, "", 0L);
        var salesOrder = new Node("com.acme.SalesOrder", NodeType.AGGREGATE, "SalesOrder",
            "com.acme.SalesOrder", "SalesOrder.java", 1, "", 0L);

        try (var one = new GraphStore(tempDir.resolve("one.db")); var two = new GraphStore(tempDir.resolve("two.db"))) {
            one.saveNode(handler); one.saveNode(order); one.saveNode(salesOrder);
            var before = loadsAggregate(one, cu, handler);
            one.saveNode(order);                       // INSERT OR REPLACE: Order now sits after SalesOrder
            var afterResave = loadsAggregate(one, cu, handler);

            two.saveNode(handler); two.saveNode(salesOrder); two.saveNode(order);
            var reversed = loadsAggregate(two, cu, handler);

            assertThat(before).containsExactly("com.acme.Order");
            assertThat(afterResave).isEqualTo(before);
            assertThat(reversed).isEqualTo(before);
        }
    }

    private static List<String> loadsAggregate(GraphStore store, com.github.javaparser.ast.CompilationUnit cu, Node handler) {
        return new EdgeExtractor(Project.DDD, store).extract(cu, handler).stream()
            .filter(e -> e.type() == EdgeType.LOADS_AGGREGATE).map(Edge::targetId).sorted().toList();
    }

    @Test
    void fileShapesAreStoredPerJavaFileAndFollowSignatureNotBodyEdits() throws IOException {
        fixture();
        Path aFile = root.resolve("src/main/java/com/acme/A.java");
        Path bFile = root.resolve("src/main/java/com/zzz/B.java");
        try (var store = new GraphStore(tempDir.resolve("a.db"))) {
            var indexer = new ProjectIndexer(store);
            indexer.indexFull(root);
            var shapes = store.getAllFileShapes();
            assertThat(shapes).containsOnlyKeys("src/main/java/com/acme/A.java",
                "src/main/java/com/acme/D.java", "src/main/java/com/zzz/B.java");

            indexer.indexFull(root);
            assertThat(store.getAllFileShapes()).isEqualTo(shapes);

            // body-only edit: synced shape unchanged
            Files.writeString(bFile, Files.readString(bFile).replace("{ }", "{ int x = 1; }"));
            Files.setLastModifiedTime(bFile, FileTime.fromMillis(System.currentTimeMillis() + 10_000));
            indexer.indexIncremental(root);
            assertThat(store.getAllFileShapes()).isEqualTo(shapes);

            // new method: shape of that file moves, the others stay
            Files.writeString(aFile, Files.readString(aFile).replace("public void v() { }", "public void v() { }\n public void w() { }"));
            Files.setLastModifiedTime(aFile, FileTime.fromMillis(System.currentTimeMillis() + 20_000));
            indexer.indexIncremental(root);
            var after = store.getAllFileShapes();
            assertThat(after.get("src/main/java/com/acme/A.java")).isNotEqualTo(shapes.get("src/main/java/com/acme/A.java"));
            assertThat(after.get("src/main/java/com/acme/D.java")).isEqualTo(shapes.get("src/main/java/com/acme/D.java"));

            // a deleted file loses its row, and its return classes
            Files.delete(bFile);
            indexer.indexIncremental(root);
            assertThat(store.getAllFileShapes()).doesNotContainKey("src/main/java/com/zzz/B.java");
            assertThat(store.getAllReturnClasses()).doesNotContainKey("com.acme.A#b()");
        }
    }
}
