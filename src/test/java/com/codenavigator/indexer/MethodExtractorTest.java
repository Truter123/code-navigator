package com.codenavigator.indexer;

import com.codenavigator.graph.GraphStore.MethodRecord;
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

    private List<MethodRecord> records(List<MethodExtractor.ExtractedMethod> methods) {
        return methods.stream().map(MethodExtractor.ExtractedMethod::record).toList();
    }

    @Test
    void extractsControllerHttpMethods() throws Exception {
        var cu = StaticJavaParser.parse(Path.of(
            "src/test/resources/sample-spring/src/main/java/com/sample/controller/GameTypeController.java"));
        var node = new Node("com.sample.controller.GameTypeController", NodeType.CONTROLLER,
            "GameTypeController", "com.sample.controller.GameTypeController", "ctrl.java", 1, "", 0);

        var methods = records(extractor.extract(cu, List.of(node), "ctrl.java", 0L));

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

        var methods = records(extractor.extract(cu, List.of(node), "svc.java", 0L));

        assertThat(methods).anyMatch(m -> m.name().equals("findAll") && m.visibility().equals("public"));
    }

    @Test
    void extractsRecordFieldsButMintsNoNodes() throws Exception {
        var cu = StaticJavaParser.parse(
            "package com.sample;" +
            "public record Habit(java.util.UUID id, String name, int currentStreak) {}");
        var node = new Node("com.sample.Habit", NodeType.RECORD,
            "Habit", "com.sample.Habit", "Habit.java", 1, "", 0);

        var methods = extractor.extract(cu, List.of(node), "Habit.java", 0L);

        assertThat(records(methods)).hasSize(3);
        assertThat(records(methods)).allMatch(m -> m.visibility().equals("field"));
        assertThat(records(methods)).anyMatch(m -> m.name().equals("id") && m.returnType().equals("UUID"));
        assertThat(records(methods)).anyMatch(m -> m.name().equals("currentStreak") && m.returnType().equals("int"));
        // A record component is data: it stays a signature row and never becomes a METHOD node.
        assertThat(methods).allMatch(m -> m.node() == null);
    }

    @Test
    void includesNonPublicMethodsWithVisibilityRecorded() throws Exception {
        // Deliberate change: the extractor used to drop everything but public methods. A private
        // method is often where the logic lives, and omitting it leaves a hole in the call graph
        // exactly where doWork() -> helper() should be. Visibility is now an attribute, not a gate.
        var cu = StaticJavaParser.parse(
            "package com.sample;" +
            "public class Svc {" +
            "  public String doWork() { return helper(); }" +
            "  private String helper() { return \"\"; }" +
            "}");
        var node = new Node("com.sample.Svc", NodeType.SERVICE,
            "Svc", "com.sample.Svc", "Svc.java", 1, "", 0);

        var methods = records(extractor.extract(cu, List.of(node), "Svc.java", 0L));

        assertThat(methods).hasSize(2);
        assertThat(methods).anyMatch(m -> m.name().equals("doWork") && m.visibility().equals("public"));
        assertThat(methods).anyMatch(m -> m.name().equals("helper") && m.visibility().equals("private"));
    }

    @Test
    void mintsMethodNodesForAggregates() {
        // AGGREGATE was absent from the original inclusion list, so no aggregate method was ever
        // indexed — the business behaviour was the one thing the graph could not see.
        var cu = StaticJavaParser.parse(
            "package com.nlp.domain.andon;" +
            "public class Andon {" +
            "  public void resolve(java.util.UUID by) {}" +
            "  public void acknowledge(java.util.UUID by, String note) {}" +
            "}");
        var node = new Node("com.nlp.domain.andon.Andon", NodeType.AGGREGATE,
            "Andon", "com.nlp.domain.andon.Andon", "Andon.java", 3, "", 0);

        var methods = extractor.extract(cu, List.of(node), "Andon.java", 42L);

        assertThat(methods).hasSize(2);
        assertThat(methods).allMatch(m -> m.node() != null && m.node().type() == NodeType.METHOD);
        assertThat(methods).extracting(m -> m.node().id())
            .containsExactlyInAnyOrder(
                "com.nlp.domain.andon.Andon#resolve(UUID)",
                "com.nlp.domain.andon.Andon#acknowledge(UUID,String)");
        assertThat(methods).allMatch(m -> m.node().filePath().equals("Andon.java"));
        assertThat(methods).allMatch(m -> m.node().lastModified() == 42L);
        // The snippet is the declaration only — bodies stay on disk for the agent to Read.
        assertThat(methods).allMatch(m -> !m.node().codeSnippet().contains("{"));
    }

    @Test
    void flagsOverrideCandidates() {
        var cu = StaticJavaParser.parse(
            "package com.sample;" +
            "public class Impl implements Api {" +
            "  @Override public void run() {}" +
            "  public void other() {}" +
            "}");
        var node = new Node("com.sample.Impl", NodeType.CLASS,
            "Impl", "com.sample.Impl", "Impl.java", 1, "", 0);

        var methods = extractor.extract(cu, List.of(node), "Impl.java", 0L);

        assertThat(methods).filteredOn(m -> m.record().name().equals("run"))
            .allMatch(MethodExtractor.ExtractedMethod::overrideCandidate);
        assertThat(methods).filteredOn(m -> m.record().name().equals("other"))
            .noneMatch(MethodExtractor.ExtractedMethod::overrideCandidate);
    }

    @Test
    void genericAndVarargsParamsSimplifyInIds() {
        var cu = StaticJavaParser.parse(
            "package com.sample;" +
            "public class Svc {" +
            "  public void save(java.util.List<java.util.UUID> ids, String... tags) {}" +
            "}");
        var node = new Node("com.sample.Svc", NodeType.SERVICE,
            "Svc", "com.sample.Svc", "Svc.java", 1, "", 0);

        var methods = extractor.extract(cu, List.of(node), "Svc.java", 0L);

        assertThat(methods.get(0).node().id()).isEqualTo("com.sample.Svc#save(List,String)");
    }
}
