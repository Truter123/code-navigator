package com.sample.domain.event;
import java.util.UUID;
public class OrderCreatedEvent extends OrderEvent {
    private final String name;
    public OrderCreatedEvent(UUID orderId, String name) { super(orderId); this.name = name; }
    public String getName() { return name; }
}
