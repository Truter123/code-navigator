package com.sample.infrastructure.cqrs;

public abstract class EventApplier<T> { public void apply(Object event) {} }
