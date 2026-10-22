package com.example.order;

public class OrderEventPublishException extends RuntimeException {
    public OrderEventPublishException(Throwable cause) {
        super("Order event could not be confirmed", cause);
    }

    public OrderEventPublishException(String detail) {
        super("Order event could not be confirmed: " + detail);
    }
}
