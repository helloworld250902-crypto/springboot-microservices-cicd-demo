package com.example.order;

import com.example.events.OrderCreatedEvent;

@FunctionalInterface
public interface OrderEventPublisher {
    void publish(OrderCreatedEvent event);
}
