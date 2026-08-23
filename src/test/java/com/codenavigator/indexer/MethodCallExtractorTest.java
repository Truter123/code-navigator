package com.codenavigator.indexer;

import com.codenavigator.graph.*;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MethodCallExtractorTest {

    @TempDir Path tempDir;
    private GraphStore store;

    @BeforeEach
    void setUp() {
        StaticJavaParser.getParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        store = new GraphStore(tempDir.resolve("calls.db"));
    }

    @AfterEach
    void tearDown() { store.close(); }

    private void saveClass(String fqn, NodeType type) {
        String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
        store.saveNode(new Node(fqn, type, simple, fqn, simple + ".java", 1, "", 0));
    }

    private void saveMethod(String fqn, String name, String params) {
        String id = fqn + "#" + name + "(" + params + ")";
        store.saveNode(new Node(id, NodeType.METHOD, name, id, "x.java", 1, "", 0));
    }

    @Test
    void resolvesCallThroughInjectedField() {
        saveClass("com.sample.Handler", NodeType.COMMAND_HANDLER);
        saveClass("com.sample.Andon", NodeType.AGGREGATE);
        saveMethod("com.sample.Handler", "handle", "Cmd");
        saveMethod("com.sample.Andon", "resolve", "UUID");

        var cu = StaticJavaParser.parse(
            "package com.sample;" +
            "public class Handler {" +
            "  private Andon andon;" +
            "  public void handle(Cmd c) { andon.resolve(c.id()); }" +
            "}");

        var edges = new MethodCallExtractor(store).extract(cu);

        assertThat(edges).extracting(Edge::sourceId, Edge::type, Edge::targetId)
            .contains(tuple3("com.sample.Handler#handle(Cmd)", EdgeType.CALLS,
                "com.sample.Andon#resolve(UUID)"));
    }

    private static org.assertj.core.groups.Tuple tuple3(String a, EdgeType b, String c) {
        return org.assertj.core.api.Assertions.tuple(a, b, c);
    }

    @Test
    void resolvesUnqualifiedSameClassCall() {
        saveClass("com.sample.Svc", NodeType.SERVICE);
        saveMethod("com.sample.Svc", "doWork", "");
        saveMethod("com.sample.Svc", "helper", "");

        var cu = StaticJavaParser.parse(
            "package com.sample;" +
            "public class Svc {" +
            "  public String doWork() { return helper(); }" +
            "  private String helper() { return \"\"; }" +
            "}");

        var edges = new MethodCallExtractor(store).extract(cu);

        assertThat(edges).extracting(Edge::sourceId, Edge::targetId)
            .contains(org.assertj.core.api.Assertions.tuple(
                "com.sample.Svc#doWork()", "com.sample.Svc#helper()"));
    }

    @Test
    void picksOverloadByArgumentCount() {
        saveClass("com.sample.Svc", NodeType.SERVICE);
        saveClass("com.sample.Repo", NodeType.REPOSITORY);
        saveMethod("com.sample.Svc", "run", "");
        saveMethod("com.sample.Repo", "find", "UUID");
        saveMethod("com.sample.Repo", "find", "UUID,String");

        var cu = StaticJavaParser.parse(
            "package com.sample;" +
            "public class Svc {" +
            "  private Repo repo;" +
            "  public void run() { repo.find(id, name); }" +
            "}");

        var edges = new MethodCallExtractor(store).extract(cu);

        assertThat(edges).extracting(Edge::targetId)
            .containsExactly("com.sample.Repo#find(UUID,String)");
    }

    @Test
    void dropsCallsWithAnUnknownReceiverRatherThanGuessing() {
        saveClass("com.sample.Svc", NodeType.SERVICE);
        saveMethod("com.sample.Svc", "run", "");
        saveClass("com.sample.Other", NodeType.SERVICE);
        saveMethod("com.sample.Other", "mystery", "");

        // The receiver comes out of a chained expression, so its type is not knowable from source
        // text alone. A guessed edge would be worse than none.
        var cu = StaticJavaParser.parse(
            "package com.sample;" +
            "public class Svc {" +
            "  public void run() { lookup().mystery(); }" +
            "}");

        var extractor = new MethodCallExtractor(store);
        var edges = extractor.extract(cu);

        assertThat(edges).isEmpty();
        assertThat(extractor.callSitesSeen()).isGreaterThan(0);
        assertThat(extractor.callSitesResolved()).isZero();
    }

    @Test
    void countsResolutionRate() {
        saveClass("com.sample.Svc", NodeType.SERVICE);
        saveMethod("com.sample.Svc", "a", "");
        saveMethod("com.sample.Svc", "b", "");

        var cu = StaticJavaParser.parse(
            "package com.sample;" +
            "public class Svc {" +
            "  public void a() { b(); unknownThing().z(); }" +
            "  public void b() {}" +
            "}");

        var extractor = new MethodCallExtractor(store);
        extractor.extract(cu);

        assertThat(extractor.callSitesSeen()).isEqualTo(3);   // b(), unknownThing(), z()
        assertThat(extractor.callSitesResolved()).isEqualTo(1);
    }

    @Test
    void derivesOverridesFromSupertypeEdges() {
        saveClass("com.sample.Api", NodeType.INTERFACE);
        saveClass("com.sample.Impl", NodeType.CLASS);
        saveMethod("com.sample.Api", "run", "String");
        saveMethod("com.sample.Impl", "run", "String");
        store.saveEdge(new Edge("i1", EdgeType.IMPLEMENTS, "com.sample.Impl", "com.sample.Api"));

        var overrides = new MethodCallExtractor(store).deriveOverrides();

        assertThat(overrides).extracting(Edge::sourceId, Edge::type, Edge::targetId)
            .containsExactly(tuple3("com.sample.Impl#run(String)", EdgeType.OVERRIDES,
                "com.sample.Api#run(String)"));
    }

    @Test
    void doesNotEmitSelfRecursionEdges() {
        saveClass("com.sample.Svc", NodeType.SERVICE);
        saveMethod("com.sample.Svc", "loop", "int");

        var cu = StaticJavaParser.parse(
            "package com.sample;" +
            "public class Svc {" +
            "  public void loop(int n) { if (n > 0) loop(n - 1); }" +
            "}");

        var edges = new MethodCallExtractor(store).extract(cu);

        assertThat(edges).isEmpty();
    }

    @Test
    void staticallyImportedCallIsNotChargedToTheEnclosingClass() {
        // assertThat() belongs to AssertJ. Treating an unqualified call as always-this made every
        // statically imported call in every test look like an unresolved internal call — it was
        // the single largest source of missing edges on the real project.
        saveClass("com.sample.SvcTest", NodeType.CLASS);
        saveMethod("com.sample.SvcTest", "shouldWork", "");

        var cu = StaticJavaParser.parse(
            "package com.sample;" +
            "import static org.assertj.core.api.Assertions.assertThat;" +
            "public class SvcTest {" +
            "  public void shouldWork() { assertThat(1); }" +
            "}");

        var extractor = new MethodCallExtractor(store);
        var edges = extractor.extract(cu);

        assertThat(edges).isEmpty();
        assertThat(extractor.receiverExternal()).isEqualTo(1);
        assertThat(extractor.receiverBehavioural()).isZero();
    }

    @Test
    void staticImportFromAnIndexedTypeStillResolves() {
        saveClass("com.sample.Helpers", NodeType.SERVICE);
        saveClass("com.sample.Svc", NodeType.SERVICE);
        saveMethod("com.sample.Helpers", "normalise", "String");
        saveMethod("com.sample.Svc", "run", "");

        var cu = StaticJavaParser.parse(
            "package com.sample;" +
            "import static com.sample.Helpers.normalise;" +
            "public class Svc {" +
            "  public void run() { normalise(\"x\"); }" +
            "}");

        var edges = new MethodCallExtractor(store).extract(cu);

        assertThat(edges).extracting(Edge::targetId)
            .containsExactly("com.sample.Helpers#normalise(String)");
    }

    @Test
    void ownMethodWinsOverAStaticImportOfTheSameName() {
        saveClass("com.sample.Helpers", NodeType.SERVICE);
        saveClass("com.sample.Svc", NodeType.SERVICE);
        saveMethod("com.sample.Helpers", "check", "");
        saveMethod("com.sample.Svc", "run", "");
        saveMethod("com.sample.Svc", "check", "");

        var cu = StaticJavaParser.parse(
            "package com.sample;" +
            "import static com.sample.Helpers.check;" +
            "public class Svc {" +
            "  public void run() { check(); }" +
            "  public void check() {}" +
            "}");

        var edges = new MethodCallExtractor(store).extract(cu);

        assertThat(edges).extracting(Edge::targetId).containsExactly("com.sample.Svc#check()");
    }

    @Test
    void sameArityOverloadsAllLinkRatherThanTheCallBeingDropped() {
        // ProductionOrderId.of(String) vs of(UUID): the receiver and name are certain, only the
        // signature is not. Linking both over-approximates which runs; dropping the call would
        // lose the caller entirely and answer "nobody calls this".
        saveClass("com.sample.OrderId", NodeType.CLASS);
        saveClass("com.sample.Svc", NodeType.SERVICE);
        saveMethod("com.sample.OrderId", "of", "String");
        saveMethod("com.sample.OrderId", "of", "UUID");
        saveMethod("com.sample.Svc", "run", "");

        var cu = StaticJavaParser.parse(
            "package com.sample;" +
            "public class Svc {" +
            "  public void run() { OrderId.of(raw); }" +
            "}");

        var edges = new MethodCallExtractor(store).extract(cu);

        assertThat(edges).extracting(Edge::targetId)
            .containsExactlyInAnyOrder("com.sample.OrderId#of(String)", "com.sample.OrderId#of(UUID)");
    }

    @Test
    void resolvesStaticCallOnAnIndexedType() {
        saveClass("com.sample.OrderId", NodeType.CLASS);
        saveClass("com.sample.Svc", NodeType.SERVICE);
        saveMethod("com.sample.OrderId", "generate", "");
        saveMethod("com.sample.Svc", "run", "");

        var cu = StaticJavaParser.parse(
            "package com.sample;" +
            "public class Svc {" +
            "  public void run() { OrderId.generate(); }" +
            "}");

        var edges = new MethodCallExtractor(store).extract(cu);

        assertThat(edges).extracting(Edge::targetId).containsExactly("com.sample.OrderId#generate()");
    }

    @Test
    void resolvesThroughAnInterfaceDeclaringTheMethod() {
        saveClass("com.sample.Repo", NodeType.INTERFACE);
        saveClass("com.sample.Svc", NodeType.SERVICE);
        saveClass("com.sample.RepoImpl", NodeType.REPOSITORY);
        saveMethod("com.sample.Repo", "save", "Order");
        saveMethod("com.sample.Svc", "run", "");
        saveMethod("com.sample.RepoImpl", "store", "Order");
        store.saveEdge(new Edge("x1", EdgeType.EXTENDS, "com.sample.RepoImpl", "com.sample.Repo"));

        var cu = StaticJavaParser.parse(
            "package com.sample;" +
            "public class Svc {" +
            "  private RepoImpl repo;" +
            "  public void run() { repo.save(order); }" +
            "}");

        var extractor = new MethodCallExtractor(store);
        extractor.extract(cu);
        var edges = extractor.resolvePending();

        assertThat(edges).extracting(Edge::sourceId, Edge::targetId)
            .containsExactly(org.assertj.core.api.Assertions.tuple(
                "com.sample.Svc#run()", "com.sample.Repo#save(Order)"));
    }

    @Test
    void countsGeneratedAccessorsSeparatelyFromRealMisses() {
        // Lombok's @Getter output is absent from the AST, so view.getStatus() finds the receiver
        // but no method. That is field access, not a resolution failure.
        saveClass("com.sample.OrderView", NodeType.VIEW);
        saveClass("com.sample.Svc", NodeType.SERVICE);
        saveMethod("com.sample.OrderView", "recalculate", "");
        saveMethod("com.sample.Svc", "run", "");

        var cu = StaticJavaParser.parse(
            "package com.sample;" +
            "public class Svc {" +
            "  private OrderView view;" +
            "  public void run() { view.getStatus(); }" +
            "}");

        var extractor = new MethodCallExtractor(store);
        extractor.extract(cu);
        extractor.resolvePending();

        assertThat(extractor.accessorMiss()).isEqualTo(1);
    }

    @Test
    void localVariableShadowsFieldOfSameName() {
        saveClass("com.sample.Svc", NodeType.SERVICE);
        saveClass("com.sample.Repo", NodeType.REPOSITORY);
        saveClass("com.sample.Cache", NodeType.SERVICE);
        saveMethod("com.sample.Svc", "run", "");
        saveMethod("com.sample.Repo", "get", "");
        saveMethod("com.sample.Cache", "get", "");

        var cu = StaticJavaParser.parse(
            "package com.sample;" +
            "public class Svc {" +
            "  private Repo target;" +
            "  public void run() { Cache target = null; target.get(); }" +
            "}");

        var edges = new MethodCallExtractor(store).extract(cu);

        assertThat(edges).extracting(Edge::targetId).containsExactly("com.sample.Cache#get()");
    }
}
