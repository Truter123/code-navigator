package com.codenavigator.indexer;

import com.codenavigator.graph.Edge;
import com.codenavigator.graph.Node;
import com.codenavigator.graph.NodeType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TypeScriptMethodExtractorTest {

    private final TypeScriptMethodExtractor extractor = new TypeScriptMethodExtractor();

    private static Node cls(String name, NodeType type) {
        return new Node(name, type, name, name, name + ".ts", 1, "", 0);
    }

    private List<TypeScriptMethodExtractor.TsMethod> extract(String src, Node... classes) {
        return extractor.extract(src, List.of(classes), "x.ts", 0L);
    }

    @Test
    void extractsMethodsWithTypedParameters() {
        String src = """
            @Injectable()
            export class WorkerService {
              constructor(private http: HttpClient) {}
              load(id: string): Observable<Worker> { return this.http.get(id); }
              save(worker: Worker, force: boolean): void { }
            }
            """;

        var methods = extract(src, cls("WorkerService", NodeType.FE_SERVICE));

        assertThat(methods).extracting(m -> m.node().id())
            .contains("WorkerService#load(string)", "WorkerService#save(Worker,boolean)");
        assertThat(methods).allMatch(m -> m.node().type() == NodeType.METHOD);
    }

    @Test
    void indexesArrowFunctionProperties() {
        // `handle = (e) => {}` is a method in everything but syntax, and Angular code is full of them.
        String src = """
            export class Widget {
              handle = (event: MouseEvent): void => { this.reset(); };
              reset() { }
            }
            """;

        var methods = extract(src, cls("Widget", NodeType.FE_CLASS));

        assertThat(methods).extracting(m -> m.node().id())
            .containsExactlyInAnyOrder("Widget#handle(MouseEvent)", "Widget#reset()");
    }

    @Test
    void ignoresControlFlowThatLooksLikeADeclaration() {
        // `if (ready) {` matches the shape of a method declaration exactly.
        String src = """
            export class Guarded {
              run(): void {
                if (this.ready) { }
                for (const x of items) { }
                while (waiting) { }
                switch (mode) { }
              }
            }
            """;

        var methods = extract(src, cls("Guarded", NodeType.FE_CLASS));

        assertThat(methods).extracting(m -> m.node().name()).containsExactly("run");
    }

    @Test
    void resolvesCallsToSiblingMethods() {
        String src = """
            export class Svc {
              outer(): void { this.inner(); }
              inner(): void { }
            }
            """;
        var methods = extract(src, cls("Svc", NodeType.FE_CLASS));

        var edges = extractor.resolveCalls(methods, Set.of("Svc"));

        assertThat(edges).extracting(Edge::sourceId, Edge::targetId)
            .containsExactly(org.assertj.core.api.Assertions.tuple("Svc#outer()", "Svc#inner()"));
    }

    @Test
    void resolvesCallsThroughAnInjectedService() {
        String component = """
            export class WorkerListComponent {
              constructor(private workers: WorkerService) {}
              refresh(): void { this.workers.load('1'); }
            }
            """;
        String service = """
            export class WorkerService {
              load(id: string): void { }
            }
            """;

        var all = new java.util.ArrayList<>(
            extract(component, cls("WorkerListComponent", NodeType.FE_COMPONENT)));
        all.addAll(extract(service, cls("WorkerService", NodeType.FE_SERVICE)));

        var edges = extractor.resolveCalls(all, Set.of("WorkerListComponent", "WorkerService"));

        assertThat(edges).extracting(Edge::sourceId, Edge::targetId)
            .contains(org.assertj.core.api.Assertions.tuple(
                "WorkerListComponent#refresh()", "WorkerService#load(string)"));
    }

    @Test
    void dropsCallsThroughAnUntypedOrUnknownReceiver() {
        String src = """
            export class Svc {
              run(): void { this.mystery.load(); this.untyped.go(); }
            }
            """;
        var methods = extract(src, cls("Svc", NodeType.FE_CLASS));

        var edges = extractor.resolveCalls(methods, Set.of("Svc"));

        assertThat(edges).isEmpty();
        assertThat(extractor.callSitesSeen()).isGreaterThan(0);
        assertThat(extractor.callSitesResolved()).isZero();
    }

    @Test
    void bracesInsideStringsAndCommentsDoNotEndTheClassBody() {
        // A naive brace counter stops at the `}` in the template string and loses every method
        // after it.
        String src = """
            export class Svc {
              first(): string { return `a } b`; }
              // a comment with }
              /* and a block } comment */
              second(): void { }
            }
            """;

        var methods = extract(src, cls("Svc", NodeType.FE_CLASS));

        assertThat(methods).extracting(m -> m.node().name())
            .containsExactlyInAnyOrder("first", "second");
    }

    @Test
    void handlesGenericsAndDefaultsInParameterLists() {
        String src = """
            export class Svc {
              query(filter: Map<string, number>, page: number = 0): void { }
            }
            """;

        var methods = extract(src, cls("Svc", NodeType.FE_CLASS));

        // Map<string, number> is one parameter, not two, and the default value is stripped.
        assertThat(methods.get(0).node().id()).isEqualTo("Svc#query(Map,number)");
    }

    @Test
    void skipsClassesThatAreNotIndexedNodes() {
        String src = """
            export class Known { a(): void { } }
            class Internal { b(): void { } }
            """;

        var methods = extract(src, cls("Known", NodeType.FE_CLASS));

        assertThat(methods).extracting(m -> m.ownerName()).containsExactly("Known");
    }

    @Test
    void interfacesGetNoMethodsSinceTheyDeclareNoBehaviour() {
        String src = """
            export interface Worker { id: string; name: string; }
            """;

        var methods = extract(src, cls("Worker", NodeType.FE_MODEL));

        assertThat(methods).isEmpty();
    }

    @Test
    void asyncAndAccessorsAreIndexed() {
        String src = """
            export class Svc {
              async fetch(url: string): Promise<void> { }
              get ready(): boolean { return true; }
            }
            """;

        var methods = extract(src, cls("Svc", NodeType.FE_CLASS));

        assertThat(methods).extracting(m -> m.node().id())
            .containsExactlyInAnyOrder("Svc#fetch(string)", "Svc#ready()");
        assertThat(methods).anyMatch(m -> m.node().codeSnippet().contains("async"));
    }
}
