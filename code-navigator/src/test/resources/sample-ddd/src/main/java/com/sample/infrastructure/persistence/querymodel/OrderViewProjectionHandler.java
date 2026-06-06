package com.sample.infrastructure.persistence.querymodel;
import com.sample.domain.event.OrderCreatedEvent;
import com.sample.infrastructure.cqrs.EventHandler;

public class OrderViewProjectionHandler {
    private final OrderViewRepository viewRepository;
    public OrderViewProjectionHandler(OrderViewRepository viewRepository) { this.viewRepository = viewRepository; }

    @EventHandler
    public void on(OrderCreatedEvent event) {
        OrderView view = new OrderView();
        viewRepository.save(view);
    }
}
