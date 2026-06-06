package com.sample.application.commands.create;
import com.sample.domain.Order;
import com.sample.infrastructure.cqrs.CommandHandler;
import java.util.UUID;

public class CreateOrderCommandHandler implements CommandHandler<CreateOrderCommand, UUID> {
    private final OrderRepository orderRepository;
    public CreateOrderCommandHandler(OrderRepository orderRepository) { this.orderRepository = orderRepository; }
    @Override
    public UUID handle(CreateOrderCommand command) {
        Order order = Order.create(command.name());
        orderRepository.save(order);
        return order.getId();
    }
    public interface OrderRepository extends com.sample.infrastructure.cqrs.Repository<Order, UUID> {}
}
