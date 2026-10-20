package com.example.order;

@FunctionalInterface
public interface ProductClient {
    ProductResponse getProduct(long id);
}
