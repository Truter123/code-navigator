package com.sample.presentation;
import com.sample.application.commands.create.CreateOrderCommand;
import com.sample.infrastructure.cqrs.CommandBus;
import com.sample.infrastructure.cqrs.QueryBus;
import java.util.UUID;

public class OrderController {
    private final CommandBus commandBus;
    private final QueryBus queryBus;
    public OrderController(CommandBus commandBus, QueryBus queryBus) { this.commandBus = commandBus; this.queryBus = queryBus; }
    public UUID create(String name) { return commandBus.dispatch(new CreateOrderCommand(name)); }
}
