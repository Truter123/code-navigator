package com.codenavigator.mcp;

import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.GraphTraversal;
import com.codenavigator.indexer.ProjectIndexer;
import com.codenavigator.search.SearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** cg_deps reads USES_LIBRARY: a second indexFull must not change its used-type counts. */
class CgDepsRepeatIndexTest {

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
    void cgDepsReportsTheSameUsedTypeCountsAfterASecondIndexFull() throws IOException {
        Path root = fixture();
        try (var store = new GraphStore(tempDir.resolve("a.db"))) {
            var traversal = new GraphTraversal(store);
            var server = new CodeNavigatorMcpServer(store, traversal, new SearchService(store, traversal));
            var indexer = new ProjectIndexer(store);
            indexer.indexFull(root);
            String first = server.handleCgDeps(Map.of());
            indexer.indexFull(root);
            String second = server.handleCgDeps(Map.of());
            assertThat(first).contains("1 type(s) used");
            assertThat(second).isEqualTo(first);
        }
    }
}
