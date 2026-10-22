package com.example.product;

public class InventoryLockUnavailableException extends RuntimeException {
    public InventoryLockUnavailableException(long productId) {
        super("Inventory lock unavailable for product " + productId);
    }

    public InventoryLockUnavailableException(long productId, Throwable cause) {
        super("Inventory lock interrupted for product " + productId, cause);
    }
}
