package com.example.events;

import java.util.UUID;

public record OrderCreatedEvent(UUID orderId, long productId, int quantity) {
}
