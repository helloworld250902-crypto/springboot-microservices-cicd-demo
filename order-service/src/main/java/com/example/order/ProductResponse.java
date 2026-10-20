package com.example.order;

import java.math.BigDecimal;

public record ProductResponse(Long id, String name, BigDecimal price) {
}
