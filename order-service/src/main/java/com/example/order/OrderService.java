package com.example.order;

import java.math.RoundingMode;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final ProductClient productClient;

    public OrderService(ProductClient productClient) {
        this.productClient = productClient;
    }

    public OrderResponse createOrder(OrderRequest request) {
        ProductResponse product = productClient.getProduct(request.productId());
        var totalPrice = product.price()
                .multiply(java.math.BigDecimal.valueOf(request.quantity()))
                .setScale(2, RoundingMode.HALF_UP);

        return new OrderResponse(
                UUID.randomUUID(),
                product.id(),
                product.name(),
                request.quantity(),
                product.price().setScale(2, RoundingMode.HALF_UP),
                totalPrice);
    }
}
