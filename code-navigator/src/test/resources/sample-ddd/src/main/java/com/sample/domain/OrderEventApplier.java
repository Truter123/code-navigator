package com.sample.domain;

import com.sample.domain.event.OrderCreatedEvent;
import com.sample.infrastructure.cqrs.ApplyEvent;
import com.sample.infrastructure.cqrs.EventApplier;

public class OrderEventApplier extends EventApplier<Order> {
    private final Order order;
    public OrderEventApplier(Order order) { super(); this.order = order; }

    @ApplyEvent
    void apply(OrderCreatedEvent event) { order.name = event.getName(); }
}
