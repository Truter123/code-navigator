package com.codenavigator.indexer;

import com.codenavigator.graph.EdgeType;
import com.codenavigator.graph.GraphSnapshot;
import com.codenavigator.graph.GraphStore;
import com.codenavigator.graph.Node;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract of the incremental sync: after any edit, the graph it leaves equals the one a
 * brand-new store gets from indexFull over the same sources (edge ids ignored).
 */
class FastSyncEqualsFullIndexTest {

    @TempDir
    Path tempDir;

    private Path proj;
    private GraphStore store;
    private ProjectIndexer indexer;
    private long clock = 2_000_000_000_000L;
    private int oracleRuns;

    private static final String ORDER = "package com.acme.model;\npublic record Order(String id) {}\n";
    private static final String CUSTOMER = """
        package com.acme.model;
        public class Customer {
            private String name;
            public String getName() { return name; }
        }
        """;
    private static final String ORDER_REPO = """
        package com.acme.repo;
        import com.acme.model.Order;
        public interface OrderRepository {
            Order find(String id);
            void save(Order o);
            default String describe() {
                // body
                return "";
            }
        }
        """;
    private static final String JDBC_REPO = """
        package com.acme.repo;
        import com.acme.model.Order;
        public class JdbcOrderRepository implements OrderRepository {
            public Order find(String id) {
                // body
                return null;
            }
            public void save(Order o) { }
        }
        """;
    private static final String BASE = """
        package com.acme.service;
        public class BaseService {
            public void audit(String m) {
                // body
            }
        }
        """;
    private static final String ORDER_SERVICE = """
        package com.acme.service;

        import com.acme.model.Order;
        import com.acme.repo.OrderRepository;

        public class OrderService extends BaseService {
            private OrderRepository repo;

            public void process(String id) {
                Order o = repo.find(id);
                repo.save(o);
                audit("x");
                // body
            }

            public Order lookup(String id) {
                return repo.find(id);
            }
        }
        """;
    private static final String REPORT = """
        package com.acme.service;

        import com.acme.model.Customer;
        import com.google.gson.Gson;

        public class ReportService {
            private OrderService orders;
            private Gson gson;

            public void report(String id) {
                orders.lookup(id).id();
                java.util.List.of(id).forEach(this::print);
                gson.toJson(id);
            }

            public void print(String s) {
                // body
            }
        }
        """;
    private static final String AUDITED = """
        package com.acme.service;
        public class AuditedService extends BaseService {
            @Override
            public void audit(String m) {
                // body
            }
        }
        """;
    private static final String HANDLER = """
        package com.acme.service;
        @Component
        public class Handler {
            @EventHandler
            public void on(Object e) { log(e); }
            void log(Object e) { }
        }
        """;
    private static final String APP = """
        package com.acme.app;
        import com.acme.service.ReportService;
        // AggregateRoot: the marker that makes the project tier DDD, where the node-type flip applies
        public class App {
            public static void main(String[] args) { new ReportService().report("1"); }
        }
        """;

    @BeforeEach
    void setUp() throws IOException {
        proj = tempDir.resolve("proj");
        write("src/com/acme/model/Order.java", ORDER);
        write("src/com/acme/model/Customer.java", CUSTOMER);
        write("src/com/acme/repo/OrderRepository.java", ORDER_REPO);
        write("src/com/acme/repo/JdbcOrderRepository.java", JDBC_REPO);
        write("src/com/acme/service/BaseService.java", BASE);
        write("src/com/acme/service/OrderService.java", ORDER_SERVICE);
        write("src/com/acme/service/ReportService.java", REPORT);
        write("src/com/acme/service/AuditedService.java", AUDITED);
        write("src/com/acme/service/Handler.java", HANDLER);
        write("src/com/acme/app/App.java", APP);
        write("db/schema.sql", "CREATE TABLE orders (id INT);\n");
        write("build.gradle", "dependencies {\n    implementation 'com.google.code.gson:gson:2.10'\n}\n");
        store = new GraphStore(tempDir.resolve("s1.db"));
        indexer = new ProjectIndexer(store);
        indexer.indexFull(proj);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    // ---- fast path ----

    @Test
    void bodyEditThatChangesNothingStructural() {
        edit("OrderService", "// body", "int x = 1;");
        assertFast(1);
    }

    @Test
    void newCallToAMethodOfAnotherFile() {
        edit("ReportService", "// body", "orders.lookup(s);");
        assertFast(1);
    }

    @Test
    void removedCall() {
        edit("OrderService", "audit(\"x\");", "");
        assertFast(1);
    }

    @Test
    void newChainedCallThroughAnotherFilesMethod() {
        edit("ReportService", "// body", "orders.lookup(s).id();");
        assertFast(1);
    }

    @Test
    void linesAddedAboveEveryMethodShiftEveryLineNumber() {
        edit("OrderService", "public class OrderService", "// one\n// two\n// three\npublic class OrderService");
        assertFast(1);
    }

    @Test
    void newConstructorCallAndMethodReference() {
        edit("ReportService", "// body", "new Customer(); java.util.List.of(s).forEach(orders::lookup);");
        assertFast(1);
    }

    @Test
    void bodyEditInTheOverridingSubclass() {
        edit("AuditedService", "// body", "super.audit(m);");
        assertFast(1);
    }

    @Test
    void bodyEditInTwoFilesAtOnce() {
        edit("OrderService", "// body", "int x = 1;");
        edit("ReportService", "// body", "orders.process(s);");
        assertFast(2);
    }

    @Test
    void inboundEdgesSurviveAFastSyncOfTheCalledFile() {
        long calls = countEdges(EdgeType.CALLS, "OrderService#process", "OrderRepository#find");
        long injects = store.getAllEdges().stream()
            .filter(e -> e.type() == EdgeType.INJECTS && e.targetId().endsWith("OrderRepository")).count();
        assertThat(calls).isPositive();
        edit("OrderRepository", "// body", "int x = 1;");
        assertFast(1);
        assertThat(countEdges(EdgeType.CALLS, "OrderService#process", "OrderRepository#find")).isEqualTo(calls);
        assertThat(store.getAllEdges().stream()
            .filter(e -> e.type() == EdgeType.INJECTS && e.targetId().endsWith("OrderRepository")).count())
            .isEqualTo(injects);
    }

    @Test
    void tenFastSyncsInARowDoNotDrift() {
        String[][] targets = {
            {"OrderService", "int q = 1;", "repo.find(id);", "audit(id);"},
            {"ReportService", "orders.lookup(s);", "orders.process(s);", "new Customer();"},
            {"JdbcOrderRepository", "int z = 1;", "save(null);", "find(\"a\");"},
            {"AuditedService", "audit(m);", "super.audit(m);", "int w = 2;"},
            {"BaseService", "int a = 1;", "audit(\"b\");", "int c = 3;"},
        };
        var random = new Random(42);
        for (int i = 0; i < 10; i++) {
            String[] t = targets[random.nextInt(targets.length)];
            edit(t[0], "// body", t[1 + random.nextInt(3)] + " // body");
            assertFast(1);
        }
    }

    @Test
    void aSecondSyncWithNothingChangedDoesNoWork() {
        edit("OrderService", "// body", "int x = 1;");
        assertFast(1);
        var again = indexer.indexIncremental(proj);
        assertThat(again.fastPath()).isFalse();
        assertThat(again.fallbackReason()).isNull();
        assertThat(again.filesReextracted()).isZero();
        assertEqualsFull();
    }

    // ---- fallback ----

    @Test
    void removedMethodFallsBack() {
        edit("ReportService", "public void print(String s) {\n        // body\n    }", "");
        assertFallback("shape changed: ReportService.java");
    }

    @Test
    void addedMethodFallsBack() {
        edit("ReportService", "public void print(String s) {", "public void extra() { }\n    public void print(String s) {");
        assertFallback("shape changed: ReportService.java");
    }

    @Test
    void changedParameterTypeFallsBack() {
        edit("OrderService", "lookup(String id)", "lookup(Object id)");
        assertFallback("shape changed: OrderService.java");
    }

    @Test
    void changedReturnTypeFallsBackAndTheChainRebindsAsInAFullIndex() {
        edit("OrderService", "public Order lookup", "public com.acme.model.Customer lookup");
        assertFallback("shape changed: OrderService.java");
    }

    @Test
    void newSubclassFileFallsBack() {
        write("src/com/acme/service/RushService.java", """
            package com.acme.service;
            public class RushService extends OrderService {
                public void process(String id) { audit(id); }
            }
            """);
        assertFallback("new file");
    }

    @Test
    void renamedClassFallsBack() throws IOException {
        Path old = file("ReportService");
        String text = Files.readString(old).replace("ReportService", "ReportingService");
        Files.delete(old);
        write("src/com/acme/service/ReportingService.java", text);
        assertFallback("new file");
    }

    @Test
    void deletedFileFallsBack() throws IOException {
        Files.delete(file("AuditedService"));
        assertFallback("deleted file");
    }

    @Test
    void addedImportFallsBack() {
        edit("ReportService", "import com.google.gson.Gson;", "import com.google.gson.Gson;\nimport java.util.Map;");
        assertFallback("shape changed: ReportService.java");
    }

    @Test
    void bodyEditThatFlipsTheNodeTypeFallsBack() {
        edit("Handler", "log(e);", "viewRepository.save(e);");
        assertFallback("shape changed: Handler.java");
    }

    @Test
    void changedNonJavaFileFallsBack() {
        write("db/schema.sql", "CREATE TABLE orders (id INT, total INT);\n");
        assertFallback("non-Java change");
    }

    @Test
    void changedBuildFileDependencyListFallsBack() {
        write("build.gradle", "dependencies {\n    implementation 'com.google.code.gson:gson:2.11'\n}\n");
        edit("OrderService", "// body", "int x = 1;");
        assertFallback("build file changed");
    }

    @Test
    void moreThanThirtyPercentOfJavaFilesFallsBack() {
        edit("OrderService", "// body", "int x = 1;");
        edit("ReportService", "// body", "int x = 1;");
        edit("AuditedService", "// body", "int x = 1;");
        edit("BaseService", "// body", "int x = 1;");
        assertFallback("over 30% of files changed");
    }

    @Test
    void indexWithoutStoredShapesFallsBack() {
        store.deleteAllFileShapes();
        edit("OrderService", "// body", "int x = 1;");
        assertFallback("no stored shape");
    }

    @Test
    void fileWithASyntaxErrorFallsBack() {
        String path = "src/com/acme/service/OrderService.java";
        assertThat(store.findNodesByFilePath(path)).as("it had nodes before it broke").isNotEmpty();
        assertThat(store.findFileShape(path)).isPresent();
        edit("OrderService", "// body", "int x = ;");
        assertFallback("parse error: OrderService.java");
        // indexFull keeps nothing for a file that does not parse: no nodes, no shape.
        assertThat(store.findNodesByFilePath(path)).isEmpty();
        assertThat(store.findFileShape(path)).isEmpty();
    }

    @Test
    void fileThatBrokeAndWasFixedEndsLikeAFreshIndex() {
        edit("OrderService", "// body", "int x = ;");
        assertFallback("parse error: OrderService.java");
        edit("OrderService", "int x = ;", "int x = 2;");
        assertFallback("no stored shape");
        assertThat(store.findFileShape("src/com/acme/service/OrderService.java")).isPresent();
        edit("OrderService", "int x = 2;", "int x = 3;");
        assertFast(1);
    }

    private static final String COMMAND_BUS = """
        package com.acme.bus;
        public class CommandBus {
            public interface Chain { <C> Object proceed(C command); }
            public <C> Object dispatch(C command, Chain chain) {
                // body
                return chain.proceed(command);
            }
        }
        """;
    private static final String QUERY_BUS = """
        package com.acme.bus;
        public class QueryBus {
            public interface Chain { <Q> Object proceed(Q query); }
            public <Q> Object dispatch(Q query, Chain chain) {
                // body
                return chain.proceed(query);
            }
        }
        """;

    private void addBusesAndReindex() {
        write("src/com/acme/bus/CommandBus.java", COMMAND_BUS);
        write("src/com/acme/bus/QueryBus.java", QUERY_BUS);
        indexer.indexFull(proj);
    }

    @Test
    void nestedTypesOfOneSimpleNameInTwoFilesEachOwnTheirNode() {
        addBusesAndReindex();
        assertThat(store.findNodesByFilePath("src/com/acme/bus/QueryBus.java")).extracting("id")
            .contains("com.acme.bus.QueryBus.Chain").doesNotContain("com.acme.bus.CommandBus.Chain");
        assertThat(store.findNodesByFilePath("src/com/acme/bus/CommandBus.java")).extracting("id")
            .contains("com.acme.bus.CommandBus.Chain").doesNotContain("com.acme.bus.QueryBus.Chain");

        // a body edit in either file takes the fast path and equals a fresh full index
        edit("QueryBus", "// body", "int probe = 1;");
        assertFast(1);
        edit("CommandBus", "// body", "int probe = 1;");
        assertFast(1);
    }

    @Test
    void addingAFileDeclaringANestedTypeOfTheSameSimpleNameIsTheOrdinaryNewFileCase() {
        write("src/com/acme/bus/CommandBus.java", COMMAND_BUS);
        indexer.indexFull(proj);
        write("src/com/acme/bus/QueryBus.java", QUERY_BUS);
        assertFallback("new file");
        assertThat(store.findMethodsByNodeId("com.acme.bus.QueryBus.Chain").stream()
            .map(m -> m.parameters()).toList()).containsExactly("Q query");
        assertThat(store.findMethodsByNodeId("com.acme.bus.CommandBus.Chain").stream()
            .map(m -> m.parameters()).toList()).containsExactly("C command");
    }

    @Test
    void forcedFullSyncFallsBack() {
        indexer = new ProjectIndexer(store).withFullSyncFlag(() -> "1");
        edit("OrderService", "// body", "int x = 1;");
        assertFallback("forced");
    }

    // ---- helpers ----

    private void assertFast(int files) {
        var result = indexer.indexIncremental(proj);
        assertThat(result.fastPath()).as("fast path (reason: %s)", result.fallbackReason()).isTrue();
        assertThat(result.filesReextracted()).isEqualTo(files);
        assertThat(result.filesReextracted()).isLessThan(result.javaFilesTotal());
        assertEqualsFull();
    }

    private void assertFallback(String reason) {
        var result = indexer.indexIncremental(proj);
        assertThat(result.fastPath()).isFalse();
        assertThat(result.fallbackReason()).isEqualTo(reason);
        assertEqualsFull();
    }

    /** The oracle: a brand-new store indexed in full over the same sources holds the same graph. */
    private void assertEqualsFull() {
        try (var full = new GraphStore(tempDir.resolve("full-" + (oracleRuns++) + ".db"))) {
            new ProjectIndexer(full).indexFull(proj);
            assertThat(GraphSnapshot.of(store).diff(GraphSnapshot.of(full))).isEmpty();
            assertThat(store.getAllReturnClasses()).isEqualTo(full.getAllReturnClasses());
            assertThat(store.getAllFileShapes()).isEqualTo(full.getAllFileShapes());
            assertThat(nodeRows(store)).isEqualTo(nodeRows(full));
        }
    }

    private static List<String> nodeRows(GraphStore s) {
        var rows = new ArrayList<String>();
        for (Node n : s.getAllNodes()) {
            rows.add(n.id() + "|" + n.type() + "|" + n.name() + "|" + n.filePath() + "|" + n.lineNumber()
                + "|entry=" + s.isEntryPoint(n.id()));
        }
        rows.sort(null);
        return rows;
    }

    private long countEdges(EdgeType type, String sourcePart, String targetPart) {
        return store.getAllEdges().stream()
            .filter(e -> e.type() == type && e.sourceId().contains(sourcePart) && e.targetId().contains(targetPart))
            .count();
    }

    private Path file(String simpleName) {
        try (var walk = Files.walk(proj)) {
            return walk.filter(p -> p.getFileName().toString().equals(simpleName + ".java")).findFirst().orElseThrow();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void edit(String simpleName, String from, String to) {
        try {
            Path p = file(simpleName);
            String text = Files.readString(p);
            assertThat(text).contains(from);
            Files.writeString(p, text.replace(from, to));
            bump(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(String relative, String content) {
        try {
            Path p = proj.resolve(relative);
            Files.createDirectories(p.getParent());
            Files.writeString(p, content);
            bump(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every write gets a distinct modification time, so a same-millisecond edit is never mistaken for no edit. */
    private void bump(Path p) throws IOException {
        clock += 5_000;
        Files.setLastModifiedTime(p, FileTime.fromMillis(clock));
    }
}
