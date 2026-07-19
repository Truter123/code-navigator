package com.codenavigator.export;

import com.codenavigator.graph.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ExportServiceTest {

    private final ExportService exportService = new ExportService();

    private final List<Node> nodes = List.of(
        new Node("com.orders.OrderController", NodeType.CONTROLLER, "OrderController",
            "com.orders.OrderController", "OrderController.java", 1, "", 0),
        new Node("com.orders.CreateOrderCommand", NodeType.COMMAND, "CreateOrderCommand",
            "com.orders.CreateOrderCommand", "CreateOrderCommand.java", 1, "", 0),
        new Node("com.payments.PaymentService", NodeType.SERVICE, "PaymentService",
            "com.payments.PaymentService", "PaymentService.java", 1, "", 0)
    );

    private final List<Edge> edges = List.of(
        new Edge("e1", EdgeType.DISPATCHES_COMMAND, "com.orders.OrderController", "com.orders.CreateOrderCommand"),
        new Edge("e2", EdgeType.CALLS_METHOD, "com.orders.OrderController", "com.payments.PaymentService")
    );

    @Test
    void toJson() {
        String json = exportService.toJson(nodes, edges, "DDD");
        assertThat(json).contains("\"tier\":\"DDD\"");
        assertThat(json).contains("\"name\":\"OrderController\"");
        assertThat(json).contains("\"type\":\"DISPATCHES_COMMAND\"");
        assertThat(json).contains("\"source\":\"com.orders.OrderController\"");
    }

    @Test
    void toMermaid() {
        String mermaid = exportService.toMermaid(nodes, edges);
        assertThat(mermaid).startsWith("graph LR");
        assertThat(mermaid).contains("subgraph com.orders");
        assertThat(mermaid).contains("OrderController[OrderController");
        assertThat(mermaid).contains("-->|DISPATCHES_COMMAND|");
    }

    @Test
    void toPlantUml() {
        String plantuml = exportService.toPlantUml(nodes, edges);
        assertThat(plantuml).contains("@startuml");
        assertThat(plantuml).contains("@enduml");
        assertThat(plantuml).contains("package \"com.orders\"");
        assertThat(plantuml).contains("<<CONTROLLER>>");
        assertThat(plantuml).contains("--> [CreateOrderCommand]");
    }
}
