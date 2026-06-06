package com.sample.application.commands.create;
import com.sample.infrastructure.cqrs.Command;
import java.util.UUID;
public record CreateOrderCommand(String name) implements Command<UUID> {}
