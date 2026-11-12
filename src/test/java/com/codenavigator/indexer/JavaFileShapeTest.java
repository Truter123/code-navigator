package com.codenavigator.indexer;

import com.codenavigator.graph.Project;
import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The shape hash ignores what no other file can see and reacts to everything they can. */
class JavaFileShapeTest {

    private static final String BASE = """
        package com.acme;
        import java.util.List;
        import org.x.Foo;
        /** The doc. */
        @Service
        public class A extends Base implements Runnable, Other {
            private int count;
            public Foo get(String id, int n) { return null; }
            static void util() { }
            public void run() { int x = 1; }
        }
        """;

    private static String hash(String source) {
        var cu = StaticJavaParser.parse(source);
        var nodeExtractor = new NodeExtractor(Project.DDD);
        var nodes = nodeExtractor.extract(cu, "A.java");
        var methods = new MethodExtractor().extract(cu, nodes, "A.java", 0L);
        return JavaFileShape.compute(cu, nodes, methods, nodeExtractor.entryPointIds(cu));
    }

    private static void sameAsBase(String edited) {
        assertThat(hash(edited)).isEqualTo(hash(BASE));
    }

    private static void differsFromBase(String edited) {
        assertThat(hash(edited)).isNotEqualTo(hash(BASE));
    }

    @Test
    void hashIsStableForTheSameSource() {
        assertThat(hash(BASE)).hasSize(64).isEqualTo(hash(BASE));
    }

    @Test
    void bodyEditDoesNotChangeTheShape() {
        sameAsBase(BASE.replace("int x = 1;", "int x = 2; helper(x); new Foo();"));
    }

    @Test
    void commentsAndJavadocDoNotChangeTheShape() {
        sameAsBase(BASE.replace("/** The doc. */", "/** Something else entirely. */ // and a line comment"));
    }

    @Test
    void reorderingMethodsDoesNotChangeTheShape() {
        sameAsBase("""
            package com.acme;
            import java.util.List;
            import org.x.Foo;
            @Service
            public class A extends Base implements Runnable, Other {
                private int count;
                public void run() { int x = 1; }
                static void util() { }
                public Foo get(String id, int n) { return null; }
            }
            """);
    }

    @Test
    void reorderingImportsDoesNotChangeTheShape() {
        sameAsBase(BASE.replace("import java.util.List;\nimport org.x.Foo;", "import org.x.Foo;\nimport java.util.List;"));
    }

    @Test
    void fieldChangesDoNotChangeTheShape() {
        sameAsBase(BASE.replace("private int count;", "private String count; private final long other = 3;"));
    }

    @Test
    void aLineShiftDoesNotChangeTheShape() {
        sameAsBase(BASE.replace("package com.acme;", "package com.acme;\n\n\n\n"));
    }

    @Test
    void anAddedRemovedOrRenamedMethodChangesTheShape() {
        differsFromBase(BASE.replace("public void run()", "public void extra() { }\n    public void run()"));
        differsFromBase(BASE.replace("    public void run() { int x = 1; }\n", ""));
        differsFromBase(BASE.replace("public Foo get(", "public Foo fetch("));
    }

    @Test
    void aChangedSignatureChangesTheShape() {
        differsFromBase(BASE.replace("String id", "Long id"));
        differsFromBase(BASE.replace("public Foo get(", "public Bar get("));
    }

    @Test
    void aChangedSupertypeChangesTheShape() {
        differsFromBase(BASE.replace("extends Base", "extends Base2"));
        differsFromBase(BASE.replace("implements Runnable, Other", "implements Runnable"));
    }

    @Test
    void anImportOrThePackageChangesTheShape() {
        differsFromBase(BASE.replace("import org.x.Foo;", "import org.x.Foo;\nimport org.y.Bar;"));
        differsFromBase(BASE.replace("package com.acme;", "package com.other;"));
    }

    @Test
    void anAnnotationChangesTheShape() {
        differsFromBase(BASE.replace("@Service", "@Component"));
        differsFromBase(BASE.replace("static void util()", "@Deprecated static void util()"));
    }

    @Test
    void staticAndVisibilityChangeTheShape() {
        differsFromBase(BASE.replace("static void util()", "void util()"));
        differsFromBase(BASE.replace("public Foo get(", "protected Foo get("));
    }

    @Test
    void aBodyEditThatFlipsTheNodeTypeChangesTheShape() {
        String plain = """
            package com.acme;
            @Component
            public class H { @EventHandler public void on(E e) { log(e); } }
            """;
        String projection = plain.replace("log(e);", "viewRepository.save(e);");
        String dispatching = plain.replace("log(e);", "commandBus.dispatch(e);");
        assertThat(hash(projection)).isNotEqualTo(hash(plain));
        assertThat(hash(dispatching)).isNotEqualTo(hash(plain)).isNotEqualTo(hash(projection));
    }

    @Test
    void gainingOrLosingMainChangesTheShape() {
        differsFromBase(BASE.replace("public void run()", "public static void main(String[] args) { }\n    public void run()"));
    }

    @Test
    void entryPointFlagIsPartOfTheShape() {
        var cu = StaticJavaParser.parse("package p;\npublic class M { }\n");
        var nodes = new NodeExtractor(Project.DDD).extract(cu, "M.java");
        var methods = new MethodExtractor().extract(cu, nodes, "M.java", 0L);
        assertThat(JavaFileShape.compute(cu, nodes, methods, java.util.Set.of("p.M")))
            .isNotEqualTo(JavaFileShape.compute(cu, nodes, methods, java.util.Set.of()));
    }

    private static final String NESTED = """
        package com.acme;
        public class N {
            public static class Inner {
                public void a() { int x = 1; }
            }
        }
        """;

    @Test
    void aFileWithANestedTypeHasAStableShapeThatChangesWithItsMethods() {
        assertThat(hash(NESTED)).isEqualTo(hash(NESTED));
        assertThat(hash(NESTED.replace("int x = 1;", "int x = 2;"))).isEqualTo(hash(NESTED));
        assertThat(hash(NESTED.replace("public void a() { int x = 1; }",
            "public void a() { int x = 1; }\n        public void b() { }"))).isNotEqualTo(hash(NESTED));
    }
}
