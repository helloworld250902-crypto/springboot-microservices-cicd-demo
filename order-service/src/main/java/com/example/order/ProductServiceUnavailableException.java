package com.example.order;

public class ProductServiceUnavailableException extends RuntimeException {
    public ProductServiceUnavailableException(Throwable cause) {
        super("Product service is unavailable", cause);
    }
}
