package com.codenavigator.mcp;

import com.codenavigator.graph.Edge;
import com.codenavigator.graph.EdgeType;
import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.GraphTraversal;
import com.codenavigator.graph.MethodIds;
import com.codenavigator.graph.NodeType;
import com.codenavigator.indexer.ProjectIndexer;
import com.codenavigator.search.SearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Constructors and initializer blocks are callers; {@code new X()} and {@code X::new} are calls. */
class ConstructorCallsTest {

    @TempDir Path tempDir;
    private GraphStore store;
    private CodeNavigatorMcpServer server;

    @BeforeEach
    void setUp() {
        store = new GraphStore(tempDir.resolve("ctor.db"));
        var traversal = new GraphTraversal(store);
        server = new CodeNavigatorMcpServer(store, traversal, new SearchService(store, traversal));
    }

    @AfterEach
    void tearDown() { store.close(); }

    private void index(Map<String, String> sources) throws IOException {
        Path dir = tempDir.resolve("proj/src/main/java/com/sample");
        Files.createDirectories(dir);
        for (var e : sources.entrySet()) {
            Files.writeString(dir.resolve(e.getKey() + ".java"), "package com.sample;\n" + e.getValue());
        }
        new ProjectIndexer(store).indexFull(tempDir.resolve("proj"));
    }

    private List<String> callsFrom(String sourceId) {
        return store.getAllEdges().stream()
            .filter(e -> e.type() == EdgeType.CALLS && e.sourceId().equals(sourceId))
            .map(Edge::targetId).sorted().toList();
    }

    @Test
    void methodIdsCarryTheConstructorName() {
        String id = MethodIds.of("com.sample.Foo", MethodIds.INIT, List.of("int", "String"));
        assertThat(id).isEqualTo("com.sample.Foo#<init>(int,String)");
        assertThat(MethodIds.methodName(id)).isEqualTo("<init>");
        assertThat(MethodIds.declaringClass(id)).isEqualTo("com.sample.Foo");
        assertThat(MethodIds.isConstructor(id)).isTrue();
        assertThat(MethodIds.isConstructor("com.sample.Foo#run()")).isFalse();
        assertThat(MethodIds.shortLabel(id)).isEqualTo("Foo#<init>(int,String)");
    }

    @Test
    void aCallInsideAConstructorHasTheConstructorAsCaller() throws IOException {
        index(Map.of(
            "Repo", "class Repo { void save() {} }",
            "Svc", "class Svc { private Repo repo; Svc(Repo r) { r.save(); } }"));

        assertThat(callsFrom("com.sample.Svc#<init>(Repo)")).containsExactly("com.sample.Repo#save()");
        assertThat(store.getAllEdges()).anyMatch(e -> e.type() == EdgeType.DECLARES_METHOD
            && e.sourceId().equals("com.sample.Svc") && e.targetId().equals("com.sample.Svc#<init>(Repo)"));

        String reply = server.handleCgRelated(Map.of("symbol", "Repo#save()", "direction", "in"));
        assertThat(reply).contains("1 callers found").contains("Svc.java:2 <init> METHOD");
    }

    @Test
    void newFooLinksOnlyTheConstructorOfMatchingArity() throws IOException {
        index(Map.of(
            "Foo", "class Foo { Foo() {} Foo(int a) {} }",
            "Maker", "class Maker { Object make() { return new Foo(1); } }"));

        assertThat(callsFrom("com.sample.Maker#make()")).containsExactly("com.sample.Foo#<init>(int)");
    }

    @Test
    void initializerBlockCallsBelongToEveryDeclaredConstructor() throws IOException {
        index(Map.of(
            "Helper", "class Helper { void warm() {} }",
            "Two", "class Two { Helper h; { h.warm(); } Two() {} Two(int a) {} }"));

        assertThat(callsFrom("com.sample.Two#<init>()")).containsExactly("com.sample.Helper#warm()");
        assertThat(callsFrom("com.sample.Two#<init>(int)")).containsExactly("com.sample.Helper#warm()");
    }

    @Test
    void initializerBlockInAClassWithNoConstructorGetsAnImplicitInit() throws IOException {
        index(Map.of(
            "Helper", "class Helper { void warm() {} }",
            "None", "class None { Helper h; { h.warm(); } }",
            "Plain", "class Plain { void run() {} }"));

        assertThat(callsFrom("com.sample.None#<init>()")).containsExactly("com.sample.Helper#warm()");
        assertThat(store.findNodeById("com.sample.Plain#<init>()")).isEmpty();
    }

    @Test
    void constructorReferenceLinksEveryConstructor() throws IOException {
        index(Map.of(
            "Foo", "class Foo { Foo() {} Foo(int a) {} }",
            "Maker", "class Maker { Object make() { java.util.function.Supplier<Foo> s = Foo::new; return s; } }"));

        assertThat(callsFrom("com.sample.Maker#make()"))
            .containsExactly("com.sample.Foo#<init>()", "com.sample.Foo#<init>(int)");
    }

    @Test
    void superCallLinksTheSuperclassConstructorOfThatArity() throws IOException {
        index(Map.of(
            "Base", "class Base { Base() {} Base(int a) {} }",
            "Derived", "class Derived extends Base { Derived(int x) { super(x); } }"));

        assertThat(callsFrom("com.sample.Derived#<init>(int)")).containsExactly("com.sample.Base#<init>(int)");
    }

    @Test
    void aSubclassConstructorOfTheSameArityDoesNotOverride() throws IOException {
        index(Map.of(
            "Base", "class Base { Base(int a) {} }",
            "Derived", "class Derived extends Base { Derived(int x) { super(x); } }"));

        assertThat(store.getAllEdges()).noneMatch(e -> e.type() == EdgeType.OVERRIDES);
    }

    @Test
    void anUncalledConstructorIsNotADeadCodeCandidate() throws IOException {
        index(Map.of("Lonely", "class Lonely { Lonely(int a) {} }"));

        assertThat(store.findNodeById("com.sample.Lonely#<init>(int)")).isPresent();
        assertThat(store.findDeadNodeCandidates(null))
            .noneMatch(c -> c.node().type() == NodeType.METHOD && MethodIds.isConstructor(c.node().id()));
        assertThat(server.handleCgDead(Map.of("limit", 100))).doesNotContain("<init>");
    }

    @Test
    void aRecordWithACompactConstructorGetsNoMethodNode() throws IOException {
        index(Map.of("Money", "record Money(int cents) { Money { if (cents < 0) throw new IllegalArgumentException(); } }"));

        assertThat(store.getAllNodes()).noneMatch(n -> n.type() == NodeType.METHOD);
    }

    @Test
    void searchForAClassNameRanksTheClassBeforeItsConstructors() throws IOException {
        index(Map.of("Widget", "class Widget { Widget() {} Widget(int a) {} }"));

        String reply = server.handleCgSearch(Map.of("query", "Widget"));
        int classRow = reply.indexOf("Widget CLASS");
        int initRow = reply.indexOf("<init>");
        assertThat(classRow).isGreaterThanOrEqualTo(0);
        if (initRow >= 0) assertThat(classRow).isLessThan(initRow);
    }
}
