package com.codenavigator.indexer;

import com.codenavigator.graph.*;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeExtractorLibraryTest {

    @TempDir
    Path tempDir;

    private GraphStore store;

    @BeforeAll
    static void configureParser() {
        StaticJavaParser.getParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
    }

    @BeforeEach
    void setUp() {
        store = new GraphStore(tempDir.resolve("test.db"));
    }

    private List<Dependency> springDeps() {
        return List.of(new Dependency("org.springframework.boot", "spring-boot-starter-web", "3.2.0"));
    }

    private Node controllerNode(String fqn) {
        String name = fqn.substring(fqn.lastIndexOf('.') + 1);
        var node = new Node(fqn, NodeType.CONTROLLER, name, fqn, "test.java", 1, "", 0L);
        store.saveNode(node);
        return node;
    }

    @Test
    void mintsLibraryNodeForUnresolvedSpringImport() {
        var source = controllerNode("com.example.OrderController");

        String code = """
            package com.example;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            class OrderController {}
            """;
        var cu = StaticJavaParser.parse(code);
        new EdgeExtractor(Project.CRUD, store, springDeps()).extract(cu, source);

        var libraryNodes = store.findNodesByType(NodeType.LIBRARY);
        assertThat(libraryNodes).anyMatch(n ->
            n.id().equals("org.springframework.web.bind.annotation.RestController"));
    }

    @Test
    void mintsUsesLibraryEdgeFromControllerToLibraryNode() {
        var source = controllerNode("com.example.OrderController");

        String code = """
            package com.example;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            class OrderController {}
            """;
        var cu = StaticJavaParser.parse(code);
        new EdgeExtractor(Project.CRUD, store, springDeps()).extract(cu, source);

        var edges = store.findEdgesFrom(source.id());
        assertThat(edges).anyMatch(e ->
            e.type() == EdgeType.USES_LIBRARY
            && e.targetId().equals("org.springframework.web.bind.annotation.RestController"));
    }

    @Test
    void libraryNodeSnippetContainsArtifactCoordinate() {
        var source = controllerNode("com.example.OrderController");

        String code = """
            package com.example;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            class OrderController {}
            """;
        var cu = StaticJavaParser.parse(code);
        new EdgeExtractor(Project.CRUD, store, springDeps()).extract(cu, source);

        var libraryNodes = store.findNodesByType(NodeType.LIBRARY);
        assertThat(libraryNodes).anyMatch(n ->
            n.codeSnippet() != null
            && n.codeSnippet().contains("org.springframework.boot:spring-boot-starter-web"));
    }

    @Test
    void doesNotMintLibraryNodeForInternalImport() {
        var source = controllerNode("com.example.OrderController");
        store.saveNode(new Node("com.example.OrderService", NodeType.SERVICE, "OrderService",
            "com.example.OrderService", "OrderService.java", 1, "", 0L));

        String code = """
            package com.example;
            import com.example.OrderService;
            class OrderController {
                private OrderService service;
            }
            """;
        var cu = StaticJavaParser.parse(code);
        new EdgeExtractor(Project.CRUD, store, springDeps()).extract(cu, source);

        assertThat(store.findNodesByType(NodeType.LIBRARY)).isEmpty();
    }

    @Test
    void deduplicatesLibraryNodeAcrossMultipleSources() {
        var source1 = controllerNode("com.example.OrderController");
        var source2 = new Node("com.example.PaymentController", NodeType.CONTROLLER,
            "PaymentController", "com.example.PaymentController", "test.java", 1, "", 0L);
        store.saveNode(source2);

        String code1 = """
            package com.example;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            class OrderController {}
            """;
        String code2 = """
            package com.example;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            class PaymentController {}
            """;
        var extractor = new EdgeExtractor(Project.CRUD, store, springDeps());
        extractor.extract(StaticJavaParser.parse(code1), source1);
        extractor.extract(StaticJavaParser.parse(code2), source2);

        long libraryNodeCount = store.findNodesByType(NodeType.LIBRARY).stream()
            .filter(n -> n.id().equals("org.springframework.web.bind.annotation.RestController"))
            .count();
        assertThat(libraryNodeCount).isEqualTo(1);
    }

    @Test
    void emptyDependencyListMintsNoLibraryNodes() {
        var source = controllerNode("com.example.OrderController");

        String code = """
            package com.example;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            class OrderController {}
            """;
        var cu = StaticJavaParser.parse(code);
        new EdgeExtractor(Project.CRUD, store, List.of()).extract(cu, source);

        assertThat(store.findNodesByType(NodeType.LIBRARY)).isEmpty();
    }
}
