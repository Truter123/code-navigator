package com.sample.domain;

import com.sample.domain.event.OrderCreatedEvent;
import com.sample.domain.event.OrderEvent;
import com.sample.infrastructure.cqrs.AggregateRoot;
import java.util.*;

public class Order extends AggregateRoot<UUID> {
    String name;
    private final List<OrderEvent> uncommittedEvents = new ArrayList<>();
    private final OrderEventApplier eventApplier = new OrderEventApplier(this);

    public Order(UUID id) { super(id); }

    public static Order create(String name) {
        Order order = new Order(UUID.randomUUID());
        order.applyEvent(new OrderCreatedEvent(order.getId(), name));
        return order;
    }

    public void applyEvent(OrderEvent event) {
        eventApplier.apply(event);
        uncommittedEvents.add(event);
    }
}
