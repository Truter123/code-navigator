package com.codenavigator.indexer;

import com.codenavigator.graph.EdgeType;
import com.codenavigator.graph.GraphSnapshot;
import com.codenavigator.graph.GraphStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** indexFull twice on one store, and once on a brand-new store, must give the same graph. */
class IndexFullRepeatableTest {

    @TempDir Path tempDir;

    private Path fixture() throws IOException {
        Path root = tempDir.resolve("fx");
        Path pkg = root.resolve("src/main/java/com/acme");
        Files.createDirectories(pkg);
        Files.writeString(root.resolve("build.gradle"), """
            dependencies {
                implementation 'org.springframework.boot:spring-boot-starter-web:3.2.0'
            }
            """);
        Files.writeString(pkg.resolve("Repo.java"), """
            package com.acme;
            public class Repo { public String find(String id) { return id; } public static int count() { return 1; } }
            """);
        Files.writeString(pkg.resolve("Helper.java"), """
            package com.acme;
            public class Helper { public static String up(String s) { return s.toUpperCase(); } }
            """);
        Files.writeString(pkg.resolve("Service.java"), """
            package com.acme;
            import org.springframework.stereotype.Service;
            import org.springframework.web.bind.annotation.RestController;
            @Service
            public class Service {
                private final Repo repo;
                private final Repo second;
                public Service(Repo repo, Repo second) { this.repo = repo; this.second = second; }
                public Repo make() { return new Repo(); }
                public String run(String id) { return Helper.up(repo.find(id)) + Repo.count() + new Repo().find(id); }
            }
            """);
        Files.writeString(pkg.resolve("Web.java"), """
            package com.acme;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class Web {
                private final Service service;
                public Web(Service service) { this.service = service; }
                public String get(String id) { return service.run(id); }
            }
            """);
        return root;
    }

    @Test
    void secondIndexFullGivesTheSameGraphAsTheFirstAndAsAFreshStore() throws IOException {
        Path root = fixture();
        try (var store = new GraphStore(tempDir.resolve("a.db")); var fresh = new GraphStore(tempDir.resolve("b.db"))) {
            var indexer = new ProjectIndexer(store);
            indexer.indexFull(root);
            var first = GraphSnapshot.of(store);
            indexer.indexFull(root);
            var second = GraphSnapshot.of(store);
            new ProjectIndexer(fresh).indexFull(root);
            var other = GraphSnapshot.of(fresh);

            assertThat(first.edgeCounts()).containsKey(EdgeType.USES_LIBRARY.name());
            assertThat(first.edgeCounts()).containsKey(EdgeType.CALLS_METHOD.name());
            assertThat(first.diff(second)).as("first vs second run").isEmpty();
            assertThat(first.diff(other)).as("first vs fresh store").isEmpty();
        }
    }

    @Test
    void oneSourceTargetTypeTripleIsStoredOnce() throws IOException {
        Path root = fixture();
        try (var store = new GraphStore(tempDir.resolve("a.db"))) {
            new ProjectIndexer(store).indexFull(root);
            var triples = GraphSnapshot.of(store).edgeTriples();
            assertThat(triples).doesNotHaveDuplicates();
        }
    }

    /** Two files declaring a nested type of one simple name each own an outer-qualified node; the tree order changes nothing. */
    private Path collidingNestedTypes(String name, String first, String second) throws IOException {
        Path root = tempDir.resolve(name);
        Path pkg = root.resolve("src/com/acme");
        Files.createDirectories(pkg);
        String[][] files = {
            {"CommandBus", "public interface Chain { <C> Object proceed(C command); }"},
            {"QueryBus", "public interface Chain { <Q> Object proceed(Q query); }"}};
        for (String outer : new String[]{first, second}) {
            String nested = outer.equals("CommandBus") ? files[0][1] : files[1][1];
            Files.writeString(pkg.resolve(outer + ".java"),
                "package com.acme;\npublic class " + outer + " {\n    " + nested + "\n    public void run(Chain c) { c.proceed(null); }\n}\n");
        }
        return root;
    }

    @Test
    void nestedTypesOfOneSimpleNameGiveTheSameGraphAndShapesWhateverTheCreationOrder() throws IOException {
        Path a = collidingNestedTypes("a", "CommandBus", "QueryBus");
        Path b = collidingNestedTypes("b", "QueryBus", "CommandBus");
        try (var s1 = new GraphStore(tempDir.resolve("s1.db")); var s2 = new GraphStore(tempDir.resolve("s2.db"))) {
            var i1 = new ProjectIndexer(s1);
            i1.indexFull(a);
            var shapesFirst = s1.getAllFileShapes();
            i1.indexFull(a);
            new ProjectIndexer(s2).indexFull(b);

            assertThat(s1.getAllFileShapes()).as("two runs, one store").isEqualTo(shapesFirst);
            assertThat(s1.getAllFileShapes().values()).as("same shapes in another tree").containsExactlyInAnyOrderElementsOf(
                s2.getAllFileShapes().values());
            assertThat(GraphSnapshot.of(s1).diff(GraphSnapshot.of(s2))).isEmpty();
            assertThat(s1.findNodesByFilePath("src/com/acme/QueryBus.java")).extracting("id").contains("com.acme.QueryBus.Chain");
            assertThat(s1.findNodesByFilePath("src/com/acme/CommandBus.java")).extracting("id").contains("com.acme.CommandBus.Chain");
        }
    }
}
