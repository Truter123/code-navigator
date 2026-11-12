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

    /** A type node whose id is a bare simple name, as the TypeScript/Dart indexers store them. */
    private void saveBareTypeScriptClass(String simpleName) {
        store.saveNode(new Node(simpleName, NodeType.CLASS, simpleName, simpleName,
            simpleName + ".ts", 1, "", 0));
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

    @Test
    void anExplicitImportPicksTheJavaTypeOverASameNamedFrontendModel() {
        // SalesOrder exists twice in the indexed project: the Java aggregate and a TypeScript
        // model of the same simple name (nlp / nlp-fe). Without the import, the simple-name match
        // is ambiguous and the call used to be dropped.
        saveClass("com.acme.sales.SalesOrder", NodeType.AGGREGATE);
        saveMethod("com.acme.sales.SalesOrder", "cancel", "");
        saveBareTypeScriptClass("SalesOrder");
        saveClass("com.acme.infra.Svc", NodeType.SERVICE);
        saveMethod("com.acme.infra.Svc", "delete", "SalesOrder");

        var cu = StaticJavaParser.parse(
            "package com.acme.infra;" +
            "import com.acme.sales.SalesOrder;" +
            "public class Svc {" +
            "  void delete(SalesOrder so) { so.cancel(); }" +
            "}");

        var edges = new MethodCallExtractor(store).extract(cu);

        assertThat(edges).extracting(Edge::targetId)
            .containsExactly("com.acme.sales.SalesOrder#cancel()");
    }

    @Test
    void anExplicitImportResolvesATypedLocalFromAMethodReturn() {
        saveClass("com.acme.sales.SalesOrder", NodeType.AGGREGATE);
        saveMethod("com.acme.sales.SalesOrder", "cancel", "");
        saveBareTypeScriptClass("SalesOrder");
        saveClass("com.acme.sales.Repo", NodeType.REPOSITORY);
        saveMethod("com.acme.sales.Repo", "load", "UUID");
        saveClass("com.acme.infra.Svc", NodeType.SERVICE);
        saveMethod("com.acme.infra.Svc", "delete", "UUID");

        var cu = StaticJavaParser.parse(
            "package com.acme.infra;" +
            "import com.acme.sales.SalesOrder;" +
            "import com.acme.sales.Repo;" +
            "public class Svc {" +
            "  private Repo repo;" +
            "  void delete(java.util.UUID id) { SalesOrder so = repo.load(id); so.cancel(); }" +
            "}");

        var edges = new MethodCallExtractor(store).extract(cu);

        assertThat(edges).extracting(Edge::targetId).contains("com.acme.sales.SalesOrder#cancel()");
    }

    @Test
    void aWildcardImportPicksTheJavaTypeOverASameNamedFrontendModel() {
        saveClass("com.acme.sales.SalesOrder", NodeType.AGGREGATE);
        saveMethod("com.acme.sales.SalesOrder", "cancel", "");
        saveBareTypeScriptClass("SalesOrder");
        saveClass("com.acme.infra.Svc", NodeType.SERVICE);
        saveMethod("com.acme.infra.Svc", "delete", "SalesOrder");

        var cu = StaticJavaParser.parse(
            "package com.acme.infra;" +
            "import com.acme.sales.*;" +
            "public class Svc {" +
            "  void delete(SalesOrder so) { so.cancel(); }" +
            "}");

        var edges = new MethodCallExtractor(store).extract(cu);

        assertThat(edges).extracting(Edge::targetId)
            .containsExactly("com.acme.sales.SalesOrder#cancel()");
    }

    @Test
    void twoJavaCandidatesWithNoImportStayAmbiguous() {
        // Ambiguity between two candidates of the *same* language is not resolved by guessing —
        // only the cross-language collision (Java vs. TypeScript/Dart) gets a same-language
        // shortcut.
        saveClass("com.a.SalesOrder", NodeType.AGGREGATE);
        saveMethod("com.a.SalesOrder", "cancel", "");
        saveClass("com.b.SalesOrder", NodeType.AGGREGATE);
        saveMethod("com.b.SalesOrder", "cancel", "");
        saveClass("com.acme.infra.Svc", NodeType.SERVICE);
        saveMethod("com.acme.infra.Svc", "delete", "SalesOrder");

        var cu = StaticJavaParser.parse(
            "package com.acme.infra;" +
            "public class Svc {" +
            "  void delete(SalesOrder so) { so.cancel(); }" +
            "}");

        var edges = new MethodCallExtractor(store).extract(cu);

        assertThat(edges).isEmpty();
    }

    // ---- method references ----

    private java.util.List<Edge> extractRef(String body) {
        saveClass("com.sample.Svc", NodeType.SERVICE);
        saveClass("com.sample.Foo", NodeType.SERVICE);
        saveClass("com.sample.Repo", NodeType.REPOSITORY);
        saveMethod("com.sample.Svc", "run", "");
        saveMethod("com.sample.Svc", "bar", "");
        saveMethod("com.sample.Foo", "bar", "");
        saveMethod("com.sample.Foo", "bar", "String");
        saveMethod("com.sample.Repo", "save", "Object");
        var cu = StaticJavaParser.parse(
            "package com.sample;" +
            "public class Svc {" +
            "  private Repo repo;" +
            "  public void run() { " + body + " }" +
            "  public void bar() {}" +
            "}");
        return new MethodCallExtractor(store).extract(cu);
    }

    @Test
    void typeNameMethodReferenceLinksEveryOverload() {
        var edges = extractRef("list.forEach(Foo::bar);");
        assertThat(edges).extracting(Edge::sourceId, Edge::type, Edge::targetId)
            .containsExactlyInAnyOrder(
                tuple3("com.sample.Svc#run()", EdgeType.CALLS, "com.sample.Foo#bar()"),
                tuple3("com.sample.Svc#run()", EdgeType.CALLS, "com.sample.Foo#bar(String)"));
    }

    @Test
    void thisMethodReferenceLinksOwnMethod() {
        var edges = extractRef("list.forEach(this::bar);");
        assertThat(edges).extracting(Edge::sourceId, Edge::type, Edge::targetId)
            .containsExactly(tuple3("com.sample.Svc#run()", EdgeType.CALLS, "com.sample.Svc#bar()"));
    }

    @Test
    void fieldMethodReferenceLinksFieldTypeMethod() {
        var edges = extractRef("list.forEach(repo::save);");
        assertThat(edges).extracting(Edge::sourceId, Edge::type, Edge::targetId)
            .containsExactly(tuple3("com.sample.Svc#run()", EdgeType.CALLS,
                "com.sample.Repo#save(Object)"));
    }

    @Test
    void unknownScopeMethodReferenceIsDroppedAndCounted() {
        saveClass("com.sample.Svc", NodeType.SERVICE);
        saveMethod("com.sample.Svc", "run", "");
        var cu = StaticJavaParser.parse(
            "package com.sample;" +
            "public class Svc { public void run() { Runnable r = unknown::bar; } }");
        var extractor = new MethodCallExtractor(store);

        var edges = extractor.extract(cu);

        assertThat(edges).isEmpty();
        assertThat(extractor.receiverExternal()).isEqualTo(1);
        assertThat(extractor.topUnresolved(10)).extracting(java.util.Map.Entry::getKey)
            .contains("methodref-scope:unknown::bar");
    }

    // ---- chained calls: a.b().c() resolves through b()'s declared return type ----

    private java.util.List<Edge> extractAll(MethodCallExtractor ex, String... sources) {
        var edges = new java.util.ArrayList<Edge>();
        for (String src : sources) edges.addAll(ex.extract(StaticJavaParser.parse(src)));
        edges.addAll(ex.resolvePending());
        return edges;
    }

    private static final String CALLER =
        "package com.sample; public class Svc { private A a; public void run() { a.b().c(); } }";

    private void chainFixture() {
        saveClass("com.sample.Svc", NodeType.SERVICE);
        saveClass("com.sample.A", NodeType.SERVICE);
        saveClass("com.sample.B", NodeType.SERVICE);
        saveMethod("com.sample.Svc", "run", "");
        saveMethod("com.sample.A", "b", "");
        saveMethod("com.sample.B", "c", "");
    }

    @Test
    void chainedCallBindsThroughIndexedReturnType() {
        chainFixture();
        var ex = new MethodCallExtractor(store);
        var edges = extractAll(ex, CALLER,
            "package com.sample; public class A { public B b() { return null; } }");
        assertThat(edges).extracting(Edge::sourceId, Edge::targetId).containsExactlyInAnyOrder(
            org.assertj.core.api.Assertions.tuple("com.sample.Svc#run()", "com.sample.A#b()"),
            org.assertj.core.api.Assertions.tuple("com.sample.Svc#run()", "com.sample.B#c()"));
        assertThat(ex.callSitesSeen()).isEqualTo(2);
        assertThat(ex.callSitesResolved()).isEqualTo(2);
    }

    @Test
    void returnTypeResolvesInDeclaringFileContext() {
        saveClass("com.sample.Svc", NodeType.SERVICE);
        saveClass("com.sample.A", NodeType.SERVICE);
        saveClass("com.other.B", NodeType.SERVICE);
        saveClass("com.third.B", NodeType.SERVICE);
        saveMethod("com.sample.Svc", "run", "");
        saveMethod("com.sample.A", "b", "");
        saveMethod("com.other.B", "c", "");
        saveMethod("com.third.B", "c", "");
        var edges = extractAll(new MethodCallExtractor(store), CALLER,
            "package com.sample; import com.other.B; public class A { public B b() { return null; } }");
        assertThat(edges).extracting(Edge::targetId).contains("com.other.B#c()")
            .doesNotContain("com.third.B#c()");
    }

    @Test
    void parameterisedReturnTypeIsDroppedAndCounted() {
        chainFixture();
        var ex = new MethodCallExtractor(store);
        var edges = extractAll(ex, CALLER,
            "package com.sample; import java.util.List; public class A { public List<B> b() { return null; } }");
        assertThat(edges).extracting(Edge::targetId).containsExactly("com.sample.A#b()");
        assertThat(ex.receiverExternal()).isEqualTo(1);
        assertThat(ex.topUnresolved(10)).extracting(java.util.Map.Entry::getKey)
            .contains("chain-return:com.sample.A#b()>c");
    }

    @Test
    void overloadsWithDifferentReturnTypesAreDropped() {
        chainFixture();
        saveMethod("com.sample.A", "b", "int");
        var ex = new MethodCallExtractor(store);
        var edges = extractAll(ex,
            "package com.sample; public class Svc { private A a; public void run() { a.b(1).c(); } }",
            "package com.sample; public class A { public B b(int x) { return null; } public C b(int y) { return null; } }");
        assertThat(edges).extracting(Edge::targetId).doesNotContain("com.sample.B#c()");
        assertThat(ex.receiverExternal()).isEqualTo(1);
    }

    @Test
    void unindexedReturnTypeEmitsNoChainEdge() {
        chainFixture();
        var ex = new MethodCallExtractor(store);
        var edges = extractAll(ex, CALLER,
            "package com.sample; public class A { public String b() { return null; } }");
        assertThat(edges).extracting(Edge::targetId).containsExactly("com.sample.A#b()");
        assertThat(ex.receiverExternal()).isEqualTo(1);
    }

    @Test
    void threeLinkChainEmitsThreeEdges() {
        chainFixture();
        saveClass("com.sample.C", NodeType.SERVICE);
        saveMethod("com.sample.B", "c", "");
        saveMethod("com.sample.C", "d", "");
        var edges = extractAll(new MethodCallExtractor(store),
            "package com.sample; public class Svc { private A a; public void run() { a.b().c().d(); } }",
            "package com.sample; public class A { public B b() { return null; } }",
            "package com.sample; public class B { public C c() { return null; } }");
        assertThat(edges).extracting(Edge::targetId).containsExactlyInAnyOrder(
            "com.sample.A#b()", "com.sample.B#c()", "com.sample.C#d()");
    }

    @Test
    void newExpressionScopeBindsOnItsType() {
        saveClass("com.sample.Svc", NodeType.SERVICE);
        saveClass("com.sample.Foo", NodeType.SERVICE);
        saveMethod("com.sample.Svc", "run", "");
        saveMethod("com.sample.Foo", "bar", "");
        var edges = extractAll(new MethodCallExtractor(store),
            "package com.sample; public class Svc { public void run() { new Foo().bar(); } }");
        assertThat(edges).extracting(Edge::targetId).contains("com.sample.Foo#bar()");
    }

    @Test
    void longChainOnUnindexedTypeFinishesAndEmitsNothing() {
        saveClass("com.sample.Svc", NodeType.SERVICE);
        saveMethod("com.sample.Svc", "run", "");
        var chain = new StringBuilder("Thing.start()");
        for (int i = 0; i < 30; i++) chain.append(".step").append(i).append("()");
        var ex = new MethodCallExtractor(store);
        var edges = extractAll(ex,
            "package com.sample; public class Svc { public void run() { " + chain + "; } }");
        assertThat(edges).isEmpty();
        assertThat(ex.callSitesSeen()).isEqualTo(31);
    }

    // ── Precedence: nested/own type, single-type import, same package (JLS 6.4.1) ──

    private void saveFooPair() {
        saveClass("a.Foo", NodeType.SERVICE);
        saveMethod("a.Foo", "run", "");
        saveClass("b.Foo", NodeType.SERVICE);
        saveMethod("b.Foo", "run", "");
        saveClass("a.Caller", NodeType.SERVICE);
        saveMethod("a.Caller", "go", "");
    }

    @Test
    void anExplicitImportBeatsASameNamedTypeOfTheSamePackage() {
        saveFooPair();
        var cu = StaticJavaParser.parse(
            "package a; import b.Foo;" +
            "public class Caller { private Foo foo; void go() { foo.run(); } }");

        var edges = new MethodCallExtractor(store).extract(cu);

        assertThat(edges).extracting(Edge::targetId).containsExactly("b.Foo#run()");
    }

    @Test
    void withoutTheImportTheSamePackageTypeIsBound() {
        saveFooPair();
        var cu = StaticJavaParser.parse(
            "package a;" +
            "public class Caller { private Foo foo; void go() { foo.run(); } }");

        var edges = new MethodCallExtractor(store).extract(cu);

        assertThat(edges).extracting(Edge::targetId).containsExactly("a.Foo#run()");
    }

    @Test
    void anImportedLibraryTypeShadowsASameNamedTypeOfThePackage() {
        saveFooPair();
        var cu = StaticJavaParser.parse(
            "package a; import lib.Foo;" +
            "public class Caller { private Foo foo; void go() { foo.run(); } }");

        var edges = new MethodCallExtractor(store).extract(cu);

        assertThat(edges).extracting(Edge::targetId).doesNotContain("a.Foo#run()");
    }

    @Test
    void aNestedTypeShadowsAnImportOfTheSameSimpleName() {
        saveClass("a.Caller", NodeType.SERVICE);
        saveMethod("a.Caller", "go", "");
        saveClass("a.Caller.Entry", NodeType.SERVICE);
        saveMethod("a.Caller.Entry", "touch", "");
        var cu = StaticJavaParser.parse(
            "package a; import java.util.Map.Entry;" +
            "public class Caller { private Entry e; void go() { e.touch(); }" +
            "  static class Entry { void touch() {} } }");

        var edges = new MethodCallExtractor(store).extract(cu);

        assertThat(edges).extracting(Edge::targetId).containsExactly("a.Caller.Entry#touch()");
    }

    private void saveNestedChains() {
        for (String c : new String[]{"p.A", "p.B"}) {
            saveClass(c, NodeType.SERVICE);
            saveMethod(c, "go", "");
            saveClass(c + ".Chain", NodeType.SERVICE);
            saveMethod(c + ".Chain", "next", "");
        }
        saveClass("q.Q", NodeType.SERVICE);
        saveMethod("q.Q", "go", "");
        saveClass("p.C", NodeType.SERVICE);
        saveMethod("p.C", "go", "");
    }

    private java.util.List<String> targetsOf(String source) {
        return new MethodCallExtractor(store).extract(StaticJavaParser.parse(source)).stream()
            .map(Edge::targetId).toList();
    }

    @Test
    void aNestedTypeOfOneSimpleNameInTwoFilesBindsToItsOwnOuterOnly() {
        saveNestedChains();

        assertThat(targetsOf("package p; public class A { private Chain c; void go() { c.next(); }"
            + " static class Chain { void next() {} } }")).containsExactly("p.A.Chain#next()");
        assertThat(targetsOf("package p; public class B { private Chain c; void go() { c.next(); }"
            + " static class Chain { void next() {} } }")).containsExactly("p.B.Chain#next()");
    }

    @Test
    void aMemberTypeWrittenWithItsOuterBindsFromAThirdFile() {
        saveNestedChains();

        assertThat(targetsOf("package p; class C { private A.Chain c; void go() { c.next(); } }"))
            .containsExactly("p.A.Chain#next()");
    }

    @Test
    void anImportOfANestedTypeBindsFromAnotherPackage() {
        saveNestedChains();

        assertThat(targetsOf("package q; import p.B.Chain; class Q { private Chain c; void go() { c.next(); } }"))
            .containsExactly("p.B.Chain#next()");
    }

    @Test
    void aWildcardImportOfAnOuterTypeBindsItsNestedType() {
        saveNestedChains();

        assertThat(targetsOf("package q; import p.B.*; class Q { private Chain c; void go() { c.next(); } }"))
            .containsExactly("p.B.Chain#next()");
    }

    @Test
    void aFullyQualifiedNestedTypeBinds() {
        saveNestedChains();

        assertThat(targetsOf("package q; class Q { private p.A.Chain c; void go() { c.next(); } }"))
            .containsExactly("p.A.Chain#next()");
    }

    private void saveInterceptorTypes() {
        for (String c : new String[]{"p.CmdInterceptor", "p.QryInterceptor"}) {
            saveClass(c, NodeType.SERVICE);
            saveClass(c + ".Chain", NodeType.SERVICE);
            saveMethod(c + ".Chain", "proceed", "");
        }
    }

    private static final String TWO_INTERCEPTORS =
        "package p; interface CmdInterceptor { interface Chain { void proceed(); } }"
        + " interface QryInterceptor { interface Chain { void proceed(); } } ";

    @Test
    void aMemberTypeInheritedFromAnImplementedInterfaceBindsInsteadOfTheAmbiguousSimpleName() {
        saveInterceptorTypes();
        saveClass("p.Audit", NodeType.SERVICE);
        saveMethod("p.Audit", "intercept", "Chain");

        assertThat(targetsOf(TWO_INTERCEPTORS
            + "class Audit implements CmdInterceptor { void intercept(Chain chain) { chain.proceed(); } }"))
            .containsExactly("p.CmdInterceptor.Chain#proceed()");
    }

    @Test
    void aMemberTypeInheritedThroughASuperclassDeclaredInTheSameFileBinds() {
        saveInterceptorTypes();
        saveClass("p.Base", NodeType.SERVICE);
        saveClass("p.Audit2", NodeType.SERVICE);
        saveMethod("p.Audit2", "intercept", "Chain");

        assertThat(targetsOf(TWO_INTERCEPTORS + "class Base implements CmdInterceptor {}"
            + " class Audit2 extends Base { void intercept(Chain chain) { chain.proceed(); } }"))
            .containsExactly("p.CmdInterceptor.Chain#proceed()");
    }

    @Test
    void aMemberTypeInheritedFromTwoSupertypesAtOneLevelResolvesToNothing() {
        saveInterceptorTypes();
        saveClass("p.Both", NodeType.SERVICE);
        saveMethod("p.Both", "intercept", "Chain");

        assertThat(targetsOf(TWO_INTERCEPTORS
            + "class Both implements CmdInterceptor, QryInterceptor { void intercept(Chain chain) { chain.proceed(); } }"))
            .isEmpty();
    }

    @Test
    void aDottedReturnTypeBindsAChainedCall() {
        saveClass("p.Maker", NodeType.SERVICE);
        saveMethod("p.Maker", "make", "");
        saveMethod("p.Maker", "use", "Maker");
        saveClass("p.Outer", NodeType.SERVICE);
        saveClass("p.Outer.Inner", NodeType.SERVICE);
        saveMethod("p.Outer.Inner", "run", "");

        var extractor = new MethodCallExtractor(store);
        var edges = new java.util.ArrayList<>(extractor.extract(StaticJavaParser.parse(
            "package p; class Outer { static class Inner { void run() {} } }"
            + " class Maker { Outer.Inner make() { return null; } void use(Maker x) { x.make().run(); } }")));
        edges.addAll(extractor.resolvePending());

        assertThat(edges).extracting(Edge::targetId).contains("p.Outer.Inner#run()");
    }
}
