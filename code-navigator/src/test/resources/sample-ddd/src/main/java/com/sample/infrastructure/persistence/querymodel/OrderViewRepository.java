package com.sample.infrastructure.persistence.querymodel;
import java.util.Optional;
import java.util.UUID;
public interface OrderViewRepository { Optional<OrderView> findById(UUID id); OrderView save(OrderView view); }
