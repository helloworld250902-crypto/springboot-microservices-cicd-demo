package com.example.product;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

@Service
public class ProductCatalog {

    private final Map<Long, Product> products = Map.of(
            1L, new Product(1L, "Mechanical Keyboard", new BigDecimal("599.00")),
            2L, new Product(2L, "Wireless Mouse", new BigDecimal("199.00"))
    );

    public Optional<Product> findById(long id) {
        return Optional.ofNullable(products.get(id));
    }
}

