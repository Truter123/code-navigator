package com.codenavigator.git;

import com.codenavigator.git.DiffSymbols.Status;
import com.codenavigator.graph.GraphStore;
import com.codenavigator.indexer.ProjectIndexer;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DiffSymbolsTest {

    private static final String SVC = """
        package com.x;

        import java.util.List;

        public class Svc {
            private int count = 0;

            public Svc(int start) {
                this.count = start;
            }

            public int first() {
                return 1;
            }

            public int second(String s) {
                return 2;
            }
        }
        """;

    @TempDir Path repo;
    private Path root;
    private Git git;
    private GraphStore store;

    private void setUp(Path projectRoot) throws Exception {
        root = projectRoot;
        git = Git.init().setDirectory(repo.toFile()).call();
        write("src/main/java/com/x/Svc.java", SVC);
        write("src/main/java/com/x/Point.java", "package com.x;\n\npublic record Point(int x, int y) {\n}\n");
        write("tools/job.py", "def run():\n    return 1\n");
        write(".gitignore", "ignored.txt\nnavigators/\n");
        commit("init");
        index();
    }

    private void index() throws Exception {
        if (store != null) store.close();
        Files.createDirectories(root.resolve("navigators/code"));
        store = new GraphStore(root.resolve("navigators/code/code-navigator.db"));
        new ProjectIndexer(store).indexFull(root);
    }

    private void write(String rel, String content) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private RevCommit commit(String msg) throws Exception {
        git.add().addFilepattern(".").call();
        return git.commit().setMessage(msg).call();
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
        if (git != null) git.close();
    }

    private DiffSymbols.Result diff(String arg) {
        return DiffSymbols.collect(root, store, arg);
    }

    @Test
    void editingOneMethodBodySelectsOnlyThatMethod() throws Exception {
        setUp(repo);
        write("src/main/java/com/x/Svc.java", SVC.replace("return 1;", "return 11;"));
        var r = diff("true");
        assertThat(r.status()).isEqualTo(Status.OK);
        assertThat(r.nodeIds()).containsExactly("com.x.Svc#first()");
        assertThat(r.reasons()).containsEntry("com.x.Svc#first()", "modified");
        assertThat(r.changedFiles()).isEqualTo(1);
    }

    @Test
    void linesAddedAboveDoNotUseIndexLineNumbers() throws Exception {
        setUp(repo);
        String shifted = SVC.replace("public Svc(int start)", "// a\n// b\n// c\n// d\n    public Svc(int start)");
        write("src/main/java/com/x/Svc.java", shifted.replace("return 2;", "return 22;"));
        var r = diff("true");
        assertThat(r.nodeIds()).contains("com.x.Svc#second(String)");
        assertThat(r.nodeIds()).doesNotContain("com.x.Svc#first()");
    }

    @Test
    void editingFieldOrImportSelectsType() throws Exception {
        setUp(repo);
        write("src/main/java/com/x/Svc.java", SVC.replace("count = 0", "count = 5"));
        assertThat(diff("true").nodeIds()).containsExactly("com.x.Svc");
        write("src/main/java/com/x/Svc.java", SVC.replace("java.util.List", "java.util.Set"));
        assertThat(diff("true").nodeIds()).containsExactly("com.x.Svc");
    }

    @Test
    void deletingAMethodSelectsItsIdAsRemoved() throws Exception {
        setUp(repo);
        write("src/main/java/com/x/Svc.java", SVC.replace("    public int first() {\n        return 1;\n    }\n\n", ""));
        var r = diff("true");
        assertThat(r.nodeIds()).contains("com.x.Svc#first()");
        assertThat(r.reasons()).containsEntry("com.x.Svc#first()", "removed");
        assertThat(r.nodeIds()).doesNotContain("com.x.Svc#second(String)");
    }

    @Test
    void changingParameterListRemovesOldIdAndCountsNewOne() throws Exception {
        setUp(repo);
        write("src/main/java/com/x/Svc.java", SVC.replace("second(String s)", "second(String s, int n)"));
        var r = diff("true");
        assertThat(r.reasons()).containsEntry("com.x.Svc#second(String)", "removed");
        assertThat(r.nodeIds()).doesNotContain("com.x.Svc#second(String,int)");
        assertThat(r.newMethods()).isEqualTo(1);
    }

    @Test
    void editingConstructorBodySelectsInit() throws Exception {
        setUp(repo);
        write("src/main/java/com/x/Svc.java", SVC.replace("this.count = start;", "this.count = start + 1;"));
        assertThat(diff("true").nodeIds()).containsExactly("com.x.Svc#<init>(int)");
    }

    @Test
    void editingInsideRecordSelectsRecordType() throws Exception {
        setUp(repo);
        write("src/main/java/com/x/Point.java", "package com.x;\n\npublic record Point(int x, int y, int z) {\n}\n");
        assertThat(diff("true").nodeIds()).containsExactly("com.x.Point");
    }

    @Test
    void untrackedUnindexedFileIsCountedIgnoredFileIsNot() throws Exception {
        setUp(repo);
        write("src/main/java/com/x/Fresh.java", "package com.x;\npublic class Fresh { void a() {} }\n");
        write("ignored.txt", "x");
        var r = diff("true");
        assertThat(r.status()).isEqualTo(Status.OK);
        assertThat(r.nodeIds()).isEmpty();
        assertThat(r.changedFiles()).isEqualTo(1);
        assertThat(r.filesWithoutNode()).isEqualTo(1);
    }

    @Test
    void deletedFileSelectsAllItsNodes() throws Exception {
        setUp(repo);
        Files.delete(root.resolve("src/main/java/com/x/Svc.java"));
        var r = diff("true");
        assertThat(r.nodeIds()).contains("com.x.Svc", "com.x.Svc#first()", "com.x.Svc#second(String)",
            "com.x.Svc#<init>(int)");
    }

    @Test
    void changedPythonFileSelectsFileLevelNodes() throws Exception {
        setUp(repo);
        write("tools/job.py", "def run():\n    return 2\n");
        var r = diff("true");
        assertThat(r.nodeIds()).isNotEmpty();
        assertThat(r.filesWithoutNode()).isZero();
    }

    @Test
    void refCoversCommittedChangeAndCleanTreeHasNone() throws Exception {
        setUp(repo);
        write("src/main/java/com/x/Svc.java", SVC.replace("return 1;", "return 11;"));
        commit("change");
        assertThat(diff("true").status()).isEqualTo(Status.NO_CHANGES);
        var r = diff("HEAD~1");
        assertThat(r.nodeIds()).containsExactly("com.x.Svc#first()");
        assertThat(r.ref()).isEqualTo("HEAD~1");
    }

    @Test
    void unknownRefAndNonRepoDoNotThrow(@TempDir Path other) throws Exception {
        setUp(repo);
        assertThat(diff("no-such-ref").status()).isEqualTo(Status.UNKNOWN_REF);
        assertThat(DiffSymbols.collect(other, store, "true").status()).isEqualTo(Status.NOT_A_GIT_REPO);
    }

    @Test
    void projectRootInsideRepoIgnoresChangesOutside() throws Exception {
        Path sub = repo.resolve("app");
        Files.createDirectories(sub);
        root = sub;
        git = Git.init().setDirectory(repo.toFile()).call();
        write("src/main/java/com/x/Svc.java", SVC);
        Files.writeString(repo.resolve("README.md"), "hi");
        Files.writeString(repo.resolve(".gitignore"), "navigators/\n");
        commit("init");
        index();
        write("src/main/java/com/x/Svc.java", SVC.replace("return 1;", "return 11;"));
        Files.writeString(repo.resolve("README.md"), "changed");
        var r = diff("true");
        assertThat(r.nodeIds()).containsExactly("com.x.Svc#first()");
        assertThat(r.changedFiles()).isEqualTo(1);
    }

    @Test
    void syntaxErrorFallsBackToFileLevel() throws Exception {
        setUp(repo);
        write("src/main/java/com/x/Svc.java", SVC.replace("return 1;", "return 1"));
        var r = diff("true");
        assertThat(r.status()).isEqualTo(Status.OK);
        assertThat(r.nodeIds()).containsExactly("com.x.Svc");
    }

    @Test
    void aChangedLineInsideAMethodOfANestedTypeMapsToTheNestedTypesMethodId() throws Exception {
        setUp(repo);
        String nest = "package com.x;\n\npublic class Nest {\n    public static class Inner {\n"
            + "        public int work() {\n            return 1;\n        }\n    }\n}\n";
        write("src/main/java/com/x/Nest.java", nest);
        commit("nest");
        index();
        write("src/main/java/com/x/Nest.java", nest.replace("return 1;", "return 2;"));

        assertThat(diff("true").nodeIds()).containsExactly("com.x.Nest.Inner#work()");
    }
}
