package com.codenavigator.indexer;

import com.codenavigator.graph.Node;
import com.codenavigator.graph.NodeType;
import com.codenavigator.graph.Project;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NodeExtractorTest {

    @BeforeAll
    static void configureParser() {
        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
    }

    private List<Node> extract(Project tier, String code) {
        var cu = StaticJavaParser.parse(code);
        return new NodeExtractor(tier).extract(cu, "test.java");
    }

    // ── Project 1: Generic ──

    @Test
    void tier1_detectsControllerByName() {
        var nodes = extract(Project.GENERIC, "class PronunciationController {}");
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().type()).isEqualTo(NodeType.CONTROLLER);
    }

    @Test
    void tier1_detectsServiceByName() {
        var nodes = extract(Project.GENERIC, "class PronunciationService {}");
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().type()).isEqualTo(NodeType.SERVICE);
    }

    @Test
    void tier1_detectsRepositoryByName() {
        var nodes = extract(Project.GENERIC, "class PronunciationRepository {}");
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().type()).isEqualTo(NodeType.REPOSITORY);
    }

    @Test
    void tier1_detectsPlainClassAsCLASS() {
        var nodes = extract(Project.GENERIC, "class Utility {}");
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().type()).isEqualTo(NodeType.CLASS);
    }

    @Test
    void tier1_detectsInterface() {
        var nodes = extract(Project.GENERIC, "interface Sortable {}");
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().type()).isEqualTo(NodeType.INTERFACE);
    }

    @Test
    void tier1_detectsEnum() {
        var nodes = extract(Project.GENERIC, "enum Color { RED, GREEN, BLUE }");
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().type()).isEqualTo(NodeType.ENUM);
    }

    // ── Project 2: Spring ──

    @Test
    void tier2_detectsRestController() {
        var nodes = extract(Project.CRUD, """
                @RestController
                class OrderApi {}
                """);
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().type()).isEqualTo(NodeType.CONTROLLER);
    }

    @Test
    void tier2_detectsService() {
        var nodes = extract(Project.CRUD, """
                @Service
                class OrderProcessor {}
                """);
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().type()).isEqualTo(NodeType.SERVICE);
    }

    @Test
    void tier2_detectsEntity() {
        var nodes = extract(Project.CRUD, """
                @Entity
                @Table(name = "game_type")
                class GameType {}
                """);
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().type()).isEqualTo(NodeType.ENTITY);
    }

    @Test
    void tier2_detectsView() {
        var nodes = extract(Project.CRUD, """
                @Entity
                @Table(name = "order_view")
                class OrderView {}
                """);
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().type()).isEqualTo(NodeType.VIEW);
    }

    @Test
    void tier2_detectsConfiguration() {
        var nodes = extract(Project.CRUD, """
                @Configuration
                class AppConfig {}
                """);
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().type()).isEqualTo(NodeType.CONFIGURATION);
    }

    // ── Project 3: DDD ──

    @Test
    void tier3_detectsCommand() {
        var nodes = extract(Project.DDD, """
                record CreateOrderCommand(String name) implements Command<UUID> {}
                """);
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().type()).isEqualTo(NodeType.COMMAND);
    }

    @Test
    void tier3_detectsCommandHandler() {
        var nodes = extract(Project.DDD, """
                class CreateOrderCommandHandler implements CommandHandler<CreateOrderCommand, UUID> {}
                """);
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().type()).isEqualTo(NodeType.COMMAND_HANDLER);
    }

    @Test
    void tier3_detectsQuery() {
        var nodes = extract(Project.DDD, """
                record GetOrderQuery(UUID id) implements Query<OrderResponse> {}
                """);
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().type()).isEqualTo(NodeType.QUERY);
    }

    @Test
    void tier3_detectsQueryHandler() {
        var nodes = extract(Project.DDD, """
                class GetOrderQueryHandler implements QueryHandler<GetOrderQuery, OrderResponse> {}
                """);
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().type()).isEqualTo(NodeType.QUERY_HANDLER);
    }

    @Test
    void tier3_detectsAggregate() {
        var nodes = extract(Project.DDD, """
                class Order extends AggregateRoot<OrderId> {}
                """);
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().type()).isEqualTo(NodeType.AGGREGATE);
    }

    @Test
    void tier3_detectsDomainEvent() {
        var nodes = extract(Project.DDD, """
                class OrderCreated extends OrderEvent {}
                """);
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().type()).isEqualTo(NodeType.DOMAIN_EVENT);
    }

    @Test
    void tier3_detectsEventApplier() {
        var nodes = extract(Project.DDD, """
                class OrderEventApplier extends EventApplier<Order> {}
                """);
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().type()).isEqualTo(NodeType.EVENT_APPLIER);
    }

    @Test
    void tier3_detectsProjectionHandler() {
        var nodes = extract(Project.DDD, """
                @Component
                class OrderProjectionHandler {
                    @EventHandler
                    void on(OrderCreated event) {
                        viewRepository.save(new OrderView());
                    }
                }
                """);
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().type()).isEqualTo(NodeType.PROJECTION_HANDLER);
    }

    @Test
    void tier3_detectsEventListener() {
        var nodes = extract(Project.DDD, """
                @Component
                class OrderEventListener {
                    @EventHandler
                    void on(OrderCreated event) {
                        commandBus.dispatch(new NotifyCommand());
                    }
                }
                """);
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().type()).isEqualTo(NodeType.EVENT_LISTENER);
    }

    // ── Cross-tier ──

    @Test
    void tier2_ignoresDddTypes() {
        var nodes = extract(Project.CRUD, """
                record CreateOrderCommand(String name) implements Command<UUID> {}
                """);
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().type()).isEqualTo(NodeType.RECORD);
    }

    @Test
    void capturesCodeSnippet() {
        var nodes = extract(Project.GENERIC, """
                class Utility {
                    void doSomething() {}
                }
                """);
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().codeSnippet()).contains("class Utility");
        assertThat(nodes.getFirst().codeSnippet()).contains("doSomething");
    }
}
