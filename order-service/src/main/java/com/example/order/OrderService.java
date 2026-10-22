package com.example.order;

import com.example.events.OrderCreatedEvent;
import java.math.RoundingMode;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final ProductClient productClient;
    private final OrderEventPublisher orderEventPublisher;

    public OrderService(ProductClient productClient, OrderEventPublisher orderEventPublisher) {
        this.productClient = productClient;
        this.orderEventPublisher = orderEventPublisher;
    }

    public OrderResponse createOrder(OrderRequest request) {
        ProductResponse product = productClient.getProduct(request.productId());
        var totalPrice = product.price()
                .multiply(java.math.BigDecimal.valueOf(request.quantity()))
                .setScale(2, RoundingMode.HALF_UP);

        UUID orderId = UUID.randomUUID();
        var response = new OrderResponse(
                orderId,
                product.id(),
                product.name(),
                request.quantity(),
                product.price().setScale(2, RoundingMode.HALF_UP),
                totalPrice);
        orderEventPublisher.publish(new OrderCreatedEvent(
                orderId, request.productId(), request.quantity()));
        return response;
    }
}
