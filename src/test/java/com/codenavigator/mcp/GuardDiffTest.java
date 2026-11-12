package com.codenavigator.mcp;

import com.codenavigator.cli.ProjectPaths;
import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.GraphTraversal;
import com.codenavigator.indexer.ProjectIndexer;
import com.codenavigator.search.SearchService;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GuardDiffTest {

    private static final String REPO = """
        package com.x;

        public class Repo {
            public int save() {
                return 1;
            }

            public int other() {
                return 3;
            }
        }
        """;
    private static final String REPO_EDITED = REPO.replace("return 1;", "return 2;");
    private static final String SVC = """
        package com.x;

        public class Svc {
            private Repo repo = new Repo();

            public int run() {
                return repo.save();
            }
        }
        """;
    private static final String SVC_TEST = """
        package com.x;

        public class SvcTest {
            private Repo repo = new Repo();

            public void saves() {
                repo.save();
            }
        }
        """;

    @TempDir Path tempDir;
    private Path root;
    private Git git;
    private GraphStore defaultStore;
    private CodeNavigatorMcpServer server;

    @BeforeEach
    void setUp() throws Exception {
        defaultStore = new GraphStore(tempDir.resolve("default.db"));
        var traversal = new GraphTraversal(defaultStore);
        server = new CodeNavigatorMcpServer(defaultStore, traversal, new SearchService(defaultStore, traversal));
        root = tempDir.resolve("proj");
        Files.createDirectories(root);
        git = Git.init().setDirectory(root.toFile()).call();
        write("src/main/java/com/x/Repo.java", REPO);
        write("src/main/java/com/x/Svc.java", SVC);
        write("src/test/java/com/x/SvcTest.java", SVC_TEST);
        write("README.md", "hello\n");
        write(".gitignore", "navigators/\n");
        commit("init");
        index();
    }

    @AfterEach
    void tearDown() {
        git.close();
        defaultStore.close();
    }

    private void write(String rel, String content) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private RevCommit commit(String msg) throws Exception {
        git.add().addFilepattern(".").call();
        return git.commit().setMessage(msg).setSign(false).call();
    }

    private void index() throws Exception {
        Files.createDirectories(ProjectPaths.codeGraphDir(root));
        try (var store = new GraphStore(ProjectPaths.graphDb(root))) {
            new ProjectIndexer(store).indexFull(root);
        }
    }

    private String guard(Path project, Map<String, Object> more) {
        var args = new HashMap<String, Object>(more);
        args.put("projectPath", project.toString());
        return server.reply("cg_guard", args);
    }

    private String guard(Map<String, Object> more) { return guard(root, more); }

    /** The text from a header to the next ### header (or the end). */
    private static String section(String reply, String header) {
        int from = reply.indexOf(header);
        assertThat(from).as("section " + header).isGreaterThanOrEqualTo(0);
        int to = reply.indexOf("\n###", from + header.length());
        return to < 0 ? reply.substring(from) : reply.substring(from, to);
    }

    private static String callerClass(String name, int line) {
        return "package com.x;\n\npublic class " + name + " {\n    private Repo repo = new Repo();\n\n"
            + "    public int run() {\n        return repo.save();\n    }\n}\n";
    }

    @Test
    void uncommittedMethodEditNamesTheMethodItsCallersAndTheTestClass() throws Exception {
        write("src/main/java/com/x/Repo.java", REPO_EDITED);

        String reply = guard(Map.of("diff", "true"));

        assertThat(reply).startsWith("## Guard: changes against HEAD\n");
        assertThat(reply).contains("### Changed (1)").contains("Repo.java:4 save METHOD");
        assertThat(section(reply, "### Direct callers")).contains("Svc.java:6 run METHOD").doesNotContain("SvcTest");
        assertThat(section(reply, "### Tests reached")).contains("### Tests reached (1)").contains("SvcTest");
        assertThat(reply).contains("### Blast radius (callers, depth 2)");
        assertThat(reply).contains("More: cg_related(symbol ");
    }

    @Test
    void aRefCoversACommittedChangeAndACleanTreeSaysSo() throws Exception {
        RevCommit parent = git.log().call().iterator().next();
        write("src/main/java/com/x/Repo.java", REPO_EDITED);
        commit("edit");

        String reply = guard(Map.of("diff", parent.getName()));
        assertThat(reply).contains("### Changed (1)").contains("save METHOD");
        assertThat(section(reply, "### Direct callers")).contains("Svc.java:6 run METHOD").doesNotContain("SvcTest");
        assertThat(section(reply, "### Tests reached")).contains("### Tests reached (1)").contains("SvcTest");

        assertThat(guard(Map.of("diff", "true"))).endsWith("\n\nNo changes against HEAD.");
    }

    @Test
    void notAGitRepositoryAndAnUnknownRefAreOneLineAnswers() throws Exception {
        Path plain = tempDir.resolve("plain");
        Files.createDirectories(plain.resolve("src/main/java/com/x"));
        Files.writeString(plain.resolve("src/main/java/com/x/A.java"), "package com.x;\npublic class A {}\n");
        Files.createDirectories(ProjectPaths.codeGraphDir(plain));
        try (var store = new GraphStore(ProjectPaths.graphDb(plain))) {
            new ProjectIndexer(store).indexFull(plain);
        }

        assertThat(guard(plain, Map.of("diff", "true")))
            .isEqualTo("Not a git repository: " + plain
                + ". `diff` needs git; pass `symbol` instead.");
        assertThat(guard(Map.of("diff", "no-such-ref")))
            .isEqualTo("Unknown git ref 'no-such-ref'. Pass `diff=true` for uncommitted changes or a branch, tag or sha.");
    }

    @Test
    void aReadmeOnlyChangeHoldsNoSymbol() throws Exception {
        write("README.md", "changed\n");

        assertThat(guard(Map.of("diff", "true")))
            .isEqualTo("1 changed file(s) against HEAD, none holds an indexed symbol.");
    }

    @Test
    void aDeletedMethodIsMarkedRemovedAndItsCallerIsInTheBlastRadius() throws Exception {
        write("src/main/java/com/x/Repo.java", """
            package com.x;

            public class Repo {
                public int other() {
                    return 3;
                }
            }
            """);

        String reply = guard(Map.of("diff", "true"));

        assertThat(reply).contains("save METHOD — removed");
        assertThat(section(reply, "### Direct callers")).contains("Svc.java:6 run METHOD");
    }

    @Test
    void manyChangedFilesStayUnderTheCapAndEndOnTheFooter() throws Exception {
        for (int i = 0; i < 40; i++) {
            String name = "VeryLongGeneratedClassNameNumber" + i + "ForTheCapCheckOfTheGuardReport".repeat(3);
            write("src/main/java/com/x/bulk/directory/" + name + ".java",
                "package com.x.bulk.directory;\npublic class " + name + " {\n    int m() {\n        return 0;\n    }\n}\n");
        }
        commit("many");
        index();
        for (int i = 0; i < 40; i++) {
            String name = "VeryLongGeneratedClassNameNumber" + i + "ForTheCapCheckOfTheGuardReport".repeat(3);
            write("src/main/java/com/x/bulk/directory/" + name + ".java",
                "package com.x.bulk.directory;\npublic class " + name + " {\n    int m() {\n        return " + (i + 1) + ";\n    }\n}\n");
        }

        String reply = guard(Map.of("diff", "true", "limit", 1000));

        assertThat(reply.length()).isLessThanOrEqualTo(6_000);
        assertThat(reply).contains("### Changed (40)").contains("more changed symbol(s)").contains("`limit`");
        assertThat(reply).endsWith(" (repeat per changed symbol).\n");
        assertThat(reply.lines().toList().getLast()).startsWith("More: cg_related(");
    }

    @Test
    void limitShowsThatManyChangedRowsAndCountsTheRest() throws Exception {
        write("src/main/java/com/x/Repo.java", REPO_EDITED.replace("return 3;", "return 4;"));
        write("src/main/java/com/x/Svc.java", SVC.replace("repo.save()", "repo.save() + 1"));

        String reply = guard(Map.of("diff", "true", "limit", 1));

        assertThat(reply).contains("### Changed (3)").contains("… 2 more changed symbol(s). Raise `limit`.");
        String changed = reply.substring(reply.indexOf("### Changed"), reply.indexOf("### Blast radius"));
        assertThat(changed.lines().filter(l -> l.contains("METHOD")).count()).isEqualTo(1);
    }

    @Test
    void withoutDiffTheSingleSymbolReplyIsUnchangedAndNeitherArgumentIsExplained() {
        assertThat(guard(Map.of("symbol", "Repo#save")))
            .startsWith("## Guard: `Repo#save()`\nMETHOD — src/main/java/com/x/Repo.java:4\n");
        assertThat(guard(Map.of())).isEqualTo("Missing required parameter: symbol");
    }

    @Test
    void aStaleIndexLineComesBeforeTheGuardHeader() throws Exception {
        write("README.md", "second\n");
        commit("after init");
        write("src/main/java/com/x/Repo.java", REPO_EDITED);

        String reply = guard(Map.of("diff", "true"));

        assertThat(reply).startsWith("Index stale: 1 commit(s) behind, run sync.\n\n## Guard: changes against HEAD\n");
    }

    @Test
    void aChangedMethodCalledOnlyByATestHasNoProductionCaller() throws Exception {
        write("src/main/java/com/x/Repo.java", REPO_EDITED.replace("return 3;", "return 4;"));
        commit("x");
        write("src/main/java/com/x/Repo.java", REPO_EDITED.replace("return 3;", "return 5;"));
        index();
        write("src/test/java/com/x/OtherTest.java", SVC_TEST.replace("SvcTest", "OtherTest").replace("repo.save()", "repo.other()"));
        commit("t");
        index();
        write("src/main/java/com/x/Repo.java", REPO_EDITED.replace("return 3;", "return 6;"));

        String reply = guard(Map.of("diff", "true"));

        assertThat(section(reply, "### Direct callers"))
            .contains("### Direct callers (0)")
            .contains("No production code calls the changed symbols directly.");
        assertThat(section(reply, "### Tests reached")).contains("OtherTest");
    }

    @Test
    void twoChangedMethodsWithOneSharedCallerListTheCallerOnce() throws Exception {
        write("src/main/java/com/x/Svc.java", SVC.replace("return repo.save();", "return repo.save() + repo.other();"));
        commit("both");
        index();
        write("src/main/java/com/x/Repo.java", REPO_EDITED.replace("return 3;", "return 4;"));

        String reply = guard(Map.of("diff", "true"));

        String callers = section(reply, "### Direct callers");
        assertThat(callers).contains("### Direct callers (1)");
        assertThat(callers.split("Svc.java:6 run METHOD", -1)).hasSize(2);
    }

    @Test
    void aChangedMethodWithTwelveProductionCallersShowsTenAndCountsTwo() throws Exception {
        for (int i = 0; i < 12; i++) write("src/main/java/com/x/Caller" + i + ".java", callerClass("Caller" + i, 0));
        commit("callers");
        index();
        write("src/main/java/com/x/Repo.java", REPO_EDITED);

        String callers = section(guard(Map.of("diff", "true")), "### Direct callers");

        assertThat(callers).contains("### Direct callers (13)");
        assertThat(callers.lines().filter(l -> l.contains(" run METHOD")).count()).isEqualTo(10);
        assertThat(callers).contains("… 3 more direct caller(s).");
    }

    @Test
    void aChangedTypeNodeListsItsDirectDependents() throws Exception {
        write("src/main/java/com/x/Repo.java", REPO.replace("public int save", "int field = 1;\n\n    public int save"));
        commit("field");
        index();
        write("src/main/java/com/x/Repo.java", REPO.replace("public int save", "int field = 2;\n\n    public int save"));

        String reply = guard(Map.of("diff", "true"));

        assertThat(reply).contains("CLASS");
        assertThat(section(reply, "### Direct callers")).contains("Svc.java:").doesNotContain("SvcTest");
    }

    @Test
    void moreThanFiveTestClassesShowFiveRowsAndACountedRemainder() throws Exception {
        for (int i = 0; i < 7; i++) {
            write("src/test/java/com/x/T" + i + "Test.java", SVC_TEST.replace("SvcTest", "T" + i + "Test"));
        }
        commit("tests");
        index();
        write("src/main/java/com/x/Repo.java", REPO_EDITED);

        String reply = guard(Map.of("diff", "true"));

        String tests = section(reply, "### Tests reached");
        assertThat(tests).contains("### Tests reached (8)").contains("… 3 more test class(es).");
        assertThat(tests.lines().filter(l -> l.contains("Test.java:")).count()).isEqualTo(5);
        assertThat(reply.lines().filter(l -> l.startsWith("- …"))).isEmpty();
    }

    @Test
    void fortyChangedMethodsWithDistinctCallersStayUnderTheCapAndEndOnTheFooter() throws Exception {
        String pad = "ForTheCapCheckOfTheGuardReport"; // long names fill the cap
        for (int i = 0; i < 40; i++) {
            String callee = "Callee" + i + pad, caller = "Caller" + i + pad;
            write("src/main/java/com/x/bulk/directory/" + callee + ".java",
                "package com.x.bulk.directory;\npublic class " + callee + " {\n    public int m() {\n        return 0;\n    }\n}\n");
            write("src/main/java/com/x/bulk/directory/" + caller + ".java",
                "package com.x.bulk.directory;\npublic class " + caller + " {\n    private " + callee + " c = new " + callee
                    + "();\n    public int go() {\n        return c.m();\n    }\n}\n");
        }
        commit("many");
        index();
        for (int i = 0; i < 40; i++) {
            String callee = "Callee" + i + pad;
            write("src/main/java/com/x/bulk/directory/" + callee + ".java",
                "package com.x.bulk.directory;\npublic class " + callee + " {\n    public int m() {\n        return " + (i + 1) + ";\n    }\n}\n");
        }

        String reply = guard(Map.of("diff", "true", "limit", 1000));

        assertThat(reply.length()).isLessThanOrEqualTo(6_000);
        assertThat(reply).contains("### Changed (40)").contains("### Direct callers (40)").contains("more direct caller(s)");
        assertThat(reply.lines().toList().getLast()).startsWith("More: cg_related(");
    }

    @Test
    void typeChangeDoesNotCountSiblingMethodsOfDependentClassesAsCallers() throws Exception {
        write("src/main/java/com/x/Kind.java", "package com.x;\n\n// note\npublic enum Kind { A, B }\n");
        for (String n : new String[] {"DepOne", "DepTwo", "DepThree"}) {
            write("src/main/java/com/x/" + n + ".java", "package com.x;\n\npublic class " + n + " {\n"
                + "    private Kind kind;\n"
                + "    public " + n + "(Kind kind) { this.kind = kind; }\n"
                + "    public Kind kind() { return kind; }\n"
                + "    public int m1() { return 1; }\n"
                + "    public int m2() { return 2; }\n"
                + "    public int m3() { return 3; }\n"
                + "    public int m4() { return 4; }\n}\n");
        }
        commit("kind");
        index();
        write("src/main/java/com/x/Kind.java", "package com.x;\n\n// note edited\npublic enum Kind { A, B }\n");

        String reply = guard(Map.of("diff", "true"));

        String direct = section(reply, "### Direct callers");
        assertThat(direct).contains("DepOne").contains("DepTwo").contains("DepThree")
            .doesNotContain("m1").doesNotContain("m2").doesNotContain("m3").doesNotContain("m4");
        assertThat(direct).startsWith("### Direct callers (3)");
        assertThat(section(reply, "### Blast radius")).doesNotContain("METHOD");
    }
}
