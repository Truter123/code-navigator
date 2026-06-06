package com.sample.domain.event;
import java.util.UUID;
public abstract class OrderEvent {
    private final UUID orderId;
    protected OrderEvent(UUID orderId) { this.orderId = orderId; }
    public UUID getOrderId() { return orderId; }
}
