package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.events.OrderCreatedEvent;
import java.math.BigDecimal;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class OrderServiceTest {

    @Test
    void createsOrderAndCalculatesTotal() {
        ProductClient productClient = id -> new ProductResponse(
                id, "Mechanical Keyboard", new BigDecimal("599.00"));
        var publishedEvents = new ArrayList<OrderCreatedEvent>();
        OrderService orderService = new OrderService(productClient, publishedEvents::add);

        OrderResponse response = orderService.createOrder(new OrderRequest(1L, 2));

        assertThat(response.orderId()).isNotNull();
        assertThat(response.productId()).isEqualTo(1L);
        assertThat(response.productName()).isEqualTo("Mechanical Keyboard");
        assertThat(response.quantity()).isEqualTo(2);
        assertThat(response.unitPrice()).isEqualByComparingTo("599.00");
        assertThat(response.totalPrice()).isEqualByComparingTo("1198.00");
        assertThat(publishedEvents).containsExactly(
                new OrderCreatedEvent(response.orderId(), 1L, 2));
    }
}
