package com.example.product;

import java.util.UUID;

public interface InventoryStore {
    void initialize(long productId, int stock);

    int getStock(long productId);

    InventoryDeductionResult deductOnce(UUID orderId, long productId, int quantity);
}
