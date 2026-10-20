package com.example.order;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(long productId) {
        super("Product " + productId + " was not found");
    }
}
