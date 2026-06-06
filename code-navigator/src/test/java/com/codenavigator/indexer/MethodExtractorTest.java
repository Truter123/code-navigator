package com.codenavigator.indexer;

import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.Node;
import com.codenavigator.graph.NodeType;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MethodExtractorTest {

    private MethodExtractor extractor;

    @BeforeEach
    void setUp() {
        StaticJavaParser.getParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        extractor = new MethodExtractor();
    }

    @Test
    void extractsControllerHttpMethods() throws Exception {
        var cu = StaticJavaParser.parse(Path.of(
            "src/test/resources/sample-spring/src/main/java/com/sample/controller/GameTypeController.java"));
        var node = new Node("com.sample.controller.GameTypeController", NodeType.CONTROLLER,
            "GameTypeController", "com.sample.controller.GameTypeController", "ctrl.java", 1, "", 0);

        var methods = extractor.extract(cu, List.of(node));

        assertThat(methods).hasSizeGreaterThanOrEqualTo(3);
        assertThat(methods).anyMatch(m -> m.annotations() != null && m.annotations().equals("GET /api/game-types"));
        assertThat(methods).anyMatch(m -> m.annotations() != null && m.annotations().equals("POST /api/game-types"));
        assertThat(methods).anyMatch(m -> m.annotations() != null && m.annotations().equals("DELETE /api/game-types/{id}"));
    }

    @Test
    void extractsServicePublicMethods() throws Exception {
        var cu = StaticJavaParser.parse(Path.of(
            "src/test/resources/sample-spring/src/main/java/com/sample/service/GameTypeService.java"));
        var node = new Node("com.sample.service.GameTypeService", NodeType.SERVICE,
            "GameTypeService", "com.sample.service.GameTypeService", "svc.java", 1, "", 0);

        var methods = extractor.extract(cu, List.of(node));

        assertThat(methods).allMatch(m -> m.visibility().equals("public"));
        assertThat(methods).anyMatch(m -> m.name().equals("findAll"));
    }

    @Test
    void extractsRecordFields() throws Exception {
        var cu = StaticJavaParser.parse(
            "package com.sample;" +
            "public record Habit(java.util.UUID id, String name, int currentStreak) {}");
        var node = new Node("com.sample.Habit", NodeType.RECORD,
            "Habit", "com.sample.Habit", "Habit.java", 1, "", 0);

        var methods = extractor.extract(cu, List.of(node));

        assertThat(methods).hasSize(3);
        assertThat(methods).allMatch(m -> m.visibility().equals("field"));
        assertThat(methods).anyMatch(m -> m.name().equals("id") && m.returnType().equals("UUID"));
        assertThat(methods).anyMatch(m -> m.name().equals("name") && m.returnType().equals("String"));
        assertThat(methods).anyMatch(m -> m.name().equals("currentStreak") && m.returnType().equals("int"));
    }

    @Test
    void skipsPrivateMethods() throws Exception {
        var cu = StaticJavaParser.parse(
            "package com.sample;" +
            "public class Svc {" +
            "  public String doWork() { return helper(); }" +
            "  private String helper() { return \"\"; }" +
            "}");
        var node = new Node("com.sample.Svc", NodeType.SERVICE,
            "Svc", "com.sample.Svc", "Svc.java", 1, "", 0);

        var methods = extractor.extract(cu, List.of(node));

        assertThat(methods).hasSize(1);
        assertThat(methods.get(0).name()).isEqualTo("doWork");
    }
}
