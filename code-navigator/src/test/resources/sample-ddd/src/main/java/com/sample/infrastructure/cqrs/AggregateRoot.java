package com.sample.infrastructure.cqrs;

public abstract class AggregateRoot<ID> { private ID id; protected AggregateRoot(ID id) { this.id = id; } public ID getId() { return id; } }
