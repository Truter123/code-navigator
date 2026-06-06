package com.codenavigator.indexer;

import com.codenavigator.graph.*;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeExtractorTest {

    @TempDir
    Path tempDir;

    private GraphStore store;

    @BeforeAll
    static void configureParser() {
        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
    }

    @BeforeEach
    void setUp() {
        store = new GraphStore(tempDir.resolve("test.db"));
    }

    private List<Edge> extract(Project tier, String code, Node sourceNode) {
        var cu = StaticJavaParser.parse(code);
        return new EdgeExtractor(tier, store).extract(cu, sourceNode);
    }

    private Node node(String qualifiedName, NodeType type) {
        var name = qualifiedName.contains(".")
                ? qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1)
                : qualifiedName;
        return new Node(qualifiedName, type, name, qualifiedName, "test.java", 1, "", 0L);
    }

    private void saveNode(Node n) {
        store.saveNode(n);
    }

    // ── Project 1 ──

    @Test
    void detectsInjects() {
        var serviceNode = node("com.example.GameTypeService", NodeType.SERVICE);
        saveNode(serviceNode);

        var controllerNode = node("com.example.GameTypeController", NodeType.CONTROLLER);
        saveNode(controllerNode);

        var code = """
                package com.example;
                class GameTypeController {
                    private final GameTypeService service;
                    GameTypeController(GameTypeService service) {
                        this.service = service;
                    }
                }
                """;

        var edges = extract(Project.GENERIC, code, controllerNode);

        assertThat(edges).hasSize(1);
        assertThat(edges.getFirst().type()).isEqualTo(EdgeType.INJECTS);
        assertThat(edges.getFirst().sourceId()).isEqualTo(controllerNode.id());
        assertThat(edges.getFirst().targetId()).isEqualTo(serviceNode.id());
    }

    // ── Project 3: DDD ──

    @Test
    void detectsDispatchesCommand() {
        var commandNode = node("com.example.CreateOrderCommand", NodeType.COMMAND);
        saveNode(commandNode);

        var controllerNode = node("com.example.OrderController", NodeType.CONTROLLER);
        saveNode(controllerNode);

        var code = """
                package com.example;
                class OrderController {
                    void create() {
                        commandBus.dispatch(new CreateOrderCommand("test"));
                    }
                }
                """;

        var edges = extract(Project.DDD, code, controllerNode);

        assertThat(edges).anyMatch(e ->
                e.type() == EdgeType.DISPATCHES_COMMAND
                && e.targetId().equals(commandNode.id()));
    }

    @Test
    void detectsDispatchesQuery() {
        var queryNode = node("com.example.GetOrderQuery", NodeType.QUERY);
        saveNode(queryNode);

        var controllerNode = node("com.example.OrderController", NodeType.CONTROLLER);
        saveNode(controllerNode);

        var code = """
                package com.example;
                class OrderController {
                    void get() {
                        queryBus.dispatch(new GetOrderQuery("id"));
                    }
                }
                """;

        var edges = extract(Project.DDD, code, controllerNode);

        assertThat(edges).anyMatch(e ->
                e.type() == EdgeType.DISPATCHES_QUERY
                && e.targetId().equals(queryNode.id()));
    }

    @Test
    void detectsHandlesCommand() {
        var commandNode = node("com.example.CreateOrderCommand", NodeType.COMMAND);
        saveNode(commandNode);

        var handlerNode = node("com.example.CreateOrderCommandHandler", NodeType.COMMAND_HANDLER);
        saveNode(handlerNode);

        var code = """
                package com.example;
                class CreateOrderCommandHandler implements CommandHandler<CreateOrderCommand, UUID> {
                    public UUID handle(CreateOrderCommand command) {
                        return null;
                    }
                }
                """;

        var edges = extract(Project.DDD, code, handlerNode);

        assertThat(edges).anyMatch(e ->
                e.type() == EdgeType.HANDLES
                && e.sourceId().equals(handlerNode.id())
                && e.targetId().equals(commandNode.id()));
    }

    @Test
    void detectsEmitsEvent() {
        var eventNode = node("com.example.OrderCreatedEvent", NodeType.DOMAIN_EVENT);
        saveNode(eventNode);

        var aggregateNode = node("com.example.Order", NodeType.AGGREGATE);
        saveNode(aggregateNode);

        var code = """
                package com.example;
                class Order {
                    void create() {
                        applyEvent(new OrderCreatedEvent());
                    }
                }
                """;

        var edges = extract(Project.DDD, code, aggregateNode);

        assertThat(edges).anyMatch(e ->
                e.type() == EdgeType.EMITS_EVENT
                && e.targetId().equals(eventNode.id()));
    }

    @Test
    void detectsAppliesEvent() {
        var eventNode = node("com.example.OrderCreatedEvent", NodeType.DOMAIN_EVENT);
        saveNode(eventNode);

        var applierNode = node("com.example.OrderEventApplier", NodeType.EVENT_APPLIER);
        saveNode(applierNode);

        var code = """
                package com.example;
                class OrderEventApplier {
                    @ApplyEvent
                    void apply(OrderCreatedEvent event) {
                        // apply logic
                    }
                }
                """;

        var edges = extract(Project.DDD, code, applierNode);

        assertThat(edges).anyMatch(e ->
                e.type() == EdgeType.APPLIES_EVENT
                && e.targetId().equals(eventNode.id()));
    }

    @Test
    void detectsProjectsEvent() {
        var eventNode = node("com.example.OrderCreatedEvent", NodeType.DOMAIN_EVENT);
        saveNode(eventNode);

        var handlerNode = node("com.example.OrderViewProjectionHandler", NodeType.PROJECTION_HANDLER);
        saveNode(handlerNode);

        var code = """
                package com.example;
                class OrderViewProjectionHandler {
                    @EventHandler
                    void on(OrderCreatedEvent event) {
                        // project to view
                    }
                }
                """;

        var edges = extract(Project.DDD, code, handlerNode);

        assertThat(edges).anyMatch(e ->
                e.type() == EdgeType.PROJECTS_EVENT
                && e.targetId().equals(eventNode.id()));
    }

    // ── CALLS_METHOD ──

    @Test
    void detectsCallsMethodViaNameExpr() {
        var serviceNode = node("com.example.OrderService", NodeType.SERVICE);
        saveNode(serviceNode);

        var controllerNode = node("com.example.OrderController", NodeType.CONTROLLER);
        saveNode(controllerNode);

        var code = """
                package com.example;
                class OrderController {
                    private final OrderService orderService;
                    OrderController(OrderService orderService) {
                        this.orderService = orderService;
                    }
                    void create() {
                        orderService.createOrder();
                    }
                }
                """;

        var edges = extract(Project.GENERIC, code, controllerNode);

        assertThat(edges).anyMatch(e ->
                e.type() == EdgeType.CALLS_METHOD
                && e.sourceId().equals(controllerNode.id())
                && e.targetId().equals(serviceNode.id()));
    }

    @Test
    void detectsCallsMethodViaFieldAccessExpr() {
        var serviceNode = node("com.example.PaymentService", NodeType.SERVICE);
        saveNode(serviceNode);

        var controllerNode = node("com.example.PaymentController", NodeType.CONTROLLER);
        saveNode(controllerNode);

        var code = """
                package com.example;
                class PaymentController {
                    private final PaymentService paymentService;
                    PaymentController(PaymentService paymentService) {
                        this.paymentService = paymentService;
                    }
                    void pay() {
                        this.paymentService.charge();
                    }
                }
                """;

        var edges = extract(Project.GENERIC, code, controllerNode);

        assertThat(edges).anyMatch(e ->
                e.type() == EdgeType.CALLS_METHOD
                && e.sourceId().equals(controllerNode.id())
                && e.targetId().equals(serviceNode.id()));
    }

    @Test
    void deduplicatesCallsMethodToSameTarget() {
        var serviceNode = node("com.example.OrderService", NodeType.SERVICE);
        saveNode(serviceNode);

        var controllerNode = node("com.example.OrderController", NodeType.CONTROLLER);
        saveNode(controllerNode);

        var code = """
                package com.example;
                class OrderController {
                    private final OrderService orderService;
                    OrderController(OrderService orderService) {
                        this.orderService = orderService;
                    }
                    void create() {
                        orderService.createOrder();
                    }
                    void delete() {
                        orderService.deleteOrder();
                    }
                }
                """;

        var edges = extract(Project.GENERIC, code, controllerNode);

        var callsMethodEdges = edges.stream()
                .filter(e -> e.type() == EdgeType.CALLS_METHOD)
                .toList();
        assertThat(callsMethodEdges).hasSize(1);
    }

    @Test
    void skipsCallsMethodForUnknownAndUnscopedCalls() {
        var controllerNode = node("com.example.OrderController", NodeType.CONTROLLER);
        saveNode(controllerNode);

        var code = """
                package com.example;
                class OrderController {
                    private final UnknownService unknownService;
                    OrderController(UnknownService unknownService) {
                        this.unknownService = unknownService;
                    }
                    void doStuff() {
                        unknownService.something();
                        doSomethingLocal();
                    }
                }
                """;

        var edges = extract(Project.GENERIC, code, controllerNode);

        assertThat(edges.stream().filter(e -> e.type() == EdgeType.CALLS_METHOD).toList()).isEmpty();
    }

    // ── RETURNS_TYPE ──

    @Test
    void extractsReturnsTypeEdge() {
        var source = """
            package com.sample;
            public class OrderService {
                private OrderRepository orderRepository;
                public OrderService(OrderRepository orderRepository) {
                    this.orderRepository = orderRepository;
                }
                public OrderView getOrder(String id) {
                    return orderRepository.findById(id);
                }
            }
            """;
        var cu = StaticJavaParser.parse(source);

        store.saveNode(new Node("com.sample.OrderService", NodeType.SERVICE, "OrderService",
            "com.sample.OrderService", "OrderService.java", 1, "", 0));
        store.saveNode(new Node("com.sample.OrderView", NodeType.VIEW, "OrderView",
            "com.sample.OrderView", "OrderView.java", 1, "", 0));

        var sourceNode = store.findNodeById("com.sample.OrderService").orElseThrow();
        var edges = new EdgeExtractor(Project.GENERIC, store).extract(cu, sourceNode);

        assertThat(edges).anyMatch(e ->
            e.type() == EdgeType.RETURNS_TYPE
            && e.sourceId().equals("com.sample.OrderService")
            && e.targetId().equals("com.sample.OrderView"));
    }
}
