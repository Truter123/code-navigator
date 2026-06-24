package com.codenavigator.briefing;

import com.codenavigator.graph.*;
import com.codenavigator.graph.GraphStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BriefingGeneratorTest {

    @TempDir Path tempDir;
    private GraphStore store;
    private Path outputDir;

    @BeforeEach
    void setUp() {
        store = new GraphStore(tempDir.resolve("code.db"));
        outputDir = tempDir.resolve(".ai-briefing");

        store.setConfig("tier", "DDD");
        store.saveNode(new Node("com.orders.api.OrderController", NodeType.CONTROLLER, "OrderController",
            "com.orders.api.OrderController", "OrderController.java", 1,
            "@RestController\n@RequestMapping(\"/api/orders\")\npublic class OrderController {\n    @PostMapping\n    public void create() {}", 0));
        store.saveNode(new Node("com.orders.commands.CreateOrderCommand", NodeType.COMMAND, "CreateOrderCommand",
            "com.orders.commands.CreateOrderCommand", "CreateOrderCommand.java", 1, "record CreateOrderCommand", 0));
        store.saveNode(new Node("com.orders.domain.Order", NodeType.AGGREGATE, "Order",
            "com.orders.domain.Order", "Order.java", 1, "class Order extends AggregateRoot", 0));
        store.saveNode(new Node("com.orders.events.OrderCreatedEvent", NodeType.DOMAIN_EVENT, "OrderCreatedEvent",
            "com.orders.events.OrderCreatedEvent", "OrderCreatedEvent.java", 1, "class OrderCreatedEvent", 0));
        store.saveNode(new Node("com.orders.projections.OrderListProjection", NodeType.PROJECTION_HANDLER, "OrderListProjection",
            "com.orders.projections.OrderListProjection", "OrderListProjection.java", 1, "class OrderListProjection", 0));
        store.saveNode(new Node("com.orders.views.OrderListView", NodeType.VIEW, "OrderListView",
            "com.orders.views.OrderListView", "OrderListView.java", 1, "class OrderListView", 0));

        store.saveEdge(new Edge("e1", EdgeType.DISPATCHES_COMMAND, "com.orders.api.OrderController", "com.orders.commands.CreateOrderCommand"));
        store.saveEdge(new Edge("e2", EdgeType.EMITS_EVENT, "com.orders.domain.Order", "com.orders.events.OrderCreatedEvent"));
        store.saveEdge(new Edge("e3", EdgeType.PROJECTS_EVENT, "com.orders.projections.OrderListProjection", "com.orders.events.OrderCreatedEvent"));
        store.saveEdge(new Edge("e4", EdgeType.UPDATES_VIEW, "com.orders.projections.OrderListProjection", "com.orders.views.OrderListView"));

        // Methods for controller
        store.saveMethod(new GraphStore.MethodRecord("com.orders.api.OrderController", "getAll",
            "ResponseEntity<List<OrderResponse>>", "", "GET /api/orders", "public"));
        store.saveMethod(new GraphStore.MethodRecord("com.orders.api.OrderController", "create",
            "ResponseEntity<OrderResponse>", "CreateOrderRequest req", "POST /api/orders", "public"));

        // Methods for record fields
        store.saveMethod(new GraphStore.MethodRecord("com.orders.commands.CreateOrderCommand", "name", "String", null, null, "field"));
        store.saveMethod(new GraphStore.MethodRecord("com.orders.commands.CreateOrderCommand", "quantity", "int", null, null, "field"));

        // Service node + methods
        store.saveNode(new Node("com.orders.services.OrderService", NodeType.SERVICE, "OrderService",
            "com.orders.services.OrderService", "OrderService.java", 1, "", 0));
        store.saveMethod(new GraphStore.MethodRecord("com.orders.services.OrderService", "findAll",
            "List<Order>", "", null, "public"));
        store.saveMethod(new GraphStore.MethodRecord("com.orders.services.OrderService", "create",
            "Order", "String name, int quantity", null, "public"));

        // FE nodes for components test
        store.saveNode(new Node("OrderPageComponent", NodeType.FE_COMPONENT, "OrderPageComponent",
            "OrderPageComponent", "order-page.component.ts", 1, "", 0));
        store.saveNode(new Node("OrderFeService", NodeType.FE_SERVICE, "OrderFeService",
            "OrderFeService", "order.service.ts", 1, "", 0));
        store.saveEdge(new Edge("fe1", EdgeType.USES_SERVICE, "OrderPageComponent", "OrderFeService"));
        store.saveEdge(new Edge("fe2", EdgeType.CALLS_API, "OrderFeService", "com.orders.api.OrderController"));
    }

    @AfterEach
    void tearDown() { store.close(); }

    @Test
    void generatesOverviewFile() throws IOException {
        new BriefingGenerator(store, null).generate(outputDir);

        String content = Files.readString(outputDir.resolve("overview.md"));
        assertThat(content).contains("DDD");
        assertThat(content).contains("9 nodes");
        assertThat(content).contains("com.orders");
    }

    @Test
    void generatesEndpointsFile() throws IOException {
        new BriefingGenerator(store, null).generate(outputDir);

        String content = Files.readString(outputDir.resolve("endpoints.md"));
        assertThat(content).contains("OrderController");
        assertThat(content).contains("POST");
    }

    @Test
    void generatesProjectionsFile() throws IOException {
        new BriefingGenerator(store, null).generate(outputDir);

        String content = Files.readString(outputDir.resolve("projections.md"));
        assertThat(content).contains("OrderListProjection");
        assertThat(content).contains("OrderCreatedEvent");
        assertThat(content).contains("OrderListView");
    }

    @Test
    void skipsDomainFilesWhenNoDomainDb() throws IOException {
        new BriefingGenerator(store, null).generate(outputDir);

        assertThat(outputDir.resolve("domain.md")).doesNotExist();
        assertThat(outputDir.resolve("flows.md")).doesNotExist();
        assertThat(outputDir.resolve("rules.md")).doesNotExist();
    }

    @Test
    void endpointsShowHttpMethods() throws IOException {
        new BriefingGenerator(store, null).generate(outputDir);

        String content = Files.readString(outputDir.resolve("endpoints.md"));
        assertThat(content).contains("GET");
        assertThat(content).contains("/api/orders");
        assertThat(content).contains("POST");
        assertThat(content).contains("List<OrderResponse>");
    }

    @Test
    void generatesModelsFile() throws IOException {
        new BriefingGenerator(store, null).generate(outputDir);

        String content = Files.readString(outputDir.resolve("models.md"));
        assertThat(content).contains("CreateOrderCommand");
        assertThat(content).contains("name: String");
        assertThat(content).contains("quantity: int");
    }

    @Test
    void generatesServicesFile() throws IOException {
        new BriefingGenerator(store, null).generate(outputDir);

        String content = Files.readString(outputDir.resolve("services.md"));
        assertThat(content).contains("OrderService");
        assertThat(content).contains("findAll()");
        assertThat(content).contains("List<Order>");
    }

    @Test
    void generatesComponentsFile() throws IOException {
        new BriefingGenerator(store, null).generate(outputDir);

        String content = Files.readString(outputDir.resolve("components.md"));
        assertThat(content).contains("OrderPageComponent");
        assertThat(content).contains("OrderFeService");
    }

    @Test
    void modelsFileShowsFeClassFieldsAndEnumValues() throws IOException {
        store.saveNode(new Node("Beneficiary", NodeType.FE_CLASS, "Beneficiary", "Beneficiary",
            "beneficiary.ts", 1,
            "export class Beneficiary {\n  type: BeneficiaryType;\n  allocation: number;\n  name: string;\n}", 0));
        store.saveNode(new Node("BeneficiaryType", NodeType.FE_ENUM, "BeneficiaryType", "BeneficiaryType",
            "beneficiary-type.ts", 1,
            "export enum BeneficiaryType {\n  PERSON,\n  ORGANIZATION\n}", 0));

        new BriefingGenerator(store, null).generate(outputDir);

        String content = Files.readString(outputDir.resolve("models.md"));
        assertThat(content).contains("Beneficiary (FE_CLASS)");
        assertThat(content).contains("name: string");
        assertThat(content).contains("BeneficiaryType (FE_ENUM)");
        assertThat(content).contains("values: PERSON, ORGANIZATION");
    }

    @Test
    void componentsFileShowsSelector() throws IOException {
        store.saveNode(new Node("WidgetComponent", NodeType.FE_COMPONENT, "WidgetComponent", "WidgetComponent",
            "widget.component.ts", 1,
            "@Component({\n  selector: 'app-widget',\n})\nexport class WidgetComponent {}", 0));

        new BriefingGenerator(store, null).generate(outputDir);

        String content = Files.readString(outputDir.resolve("components.md"));
        assertThat(content).contains("WidgetComponent");
        assertThat(content).contains("app-widget");
    }

    @Test
    void servicesFileAppendsFrontendTypes() throws IOException {
        store.saveNode(new Node("MoneyPipe", NodeType.FE_PIPE, "MoneyPipe", "MoneyPipe", "money.pipe.ts", 1, "", 0));
        store.saveNode(new Node("AuthGuard", NodeType.FE_GUARD, "AuthGuard", "AuthGuard", "auth.guard.ts", 1, "", 0));
        store.saveNode(new Node("ApiInterceptor", NodeType.FE_INTERCEPTOR, "ApiInterceptor", "ApiInterceptor",
            "api.interceptor.ts", 1, "", 0));

        new BriefingGenerator(store, null).generate(outputDir);

        String content = Files.readString(outputDir.resolve("services.md"));
        assertThat(content).contains("MoneyPipe");
        assertThat(content).contains("AuthGuard");
        assertThat(content).contains("ApiInterceptor");
    }

    @Test
    void generatesOtherFileForValidatorsConstantsScripts() throws IOException {
        store.saveNode(new Node("phoneValidator", NodeType.FE_VALIDATOR, "phoneValidator", "phoneValidator",
            "phone.validator.ts", 1, "", 0));
        store.saveNode(new Node("PEP_INFO_MESSAGE", NodeType.FE_CONSTANT, "PEP_INFO_MESSAGE", "PEP_INFO_MESSAGE",
            "pep-info-message.const.ts", 1, "", 0));
        store.saveNode(new Node("Jenkinsfile", NodeType.GROOVY_SCRIPT, "Jenkinsfile", "Jenkinsfile",
            "cicd/Jenkinsfile.groovy", 1, "", 0));

        new BriefingGenerator(store, null).generate(outputDir);

        String content = Files.readString(outputDir.resolve("other.md"));
        assertThat(content).contains("FE_VALIDATOR");
        assertThat(content).contains("phoneValidator");
        assertThat(content).contains("FE_CONSTANT");
        assertThat(content).contains("PEP_INFO_MESSAGE");
        assertThat(content).contains("GROOVY_SCRIPT");
        assertThat(content).contains("Jenkinsfile");
    }
}
