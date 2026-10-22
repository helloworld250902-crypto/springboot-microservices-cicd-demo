package com.example.product;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

@Service
public class ProductCatalog {

    private final Map<Long, ProductDefinition> products = Map.of(
            1L, new ProductDefinition("Mechanical Keyboard", new BigDecimal("599.00"), 100),
            2L, new ProductDefinition("Wireless Mouse", new BigDecimal("199.00"), 50)
    );
    private final InventoryStore inventoryStore;

    public ProductCatalog(InventoryStore inventoryStore) {
        this.inventoryStore = inventoryStore;
    }

    public Optional<Product> findById(long id) {
        ProductDefinition definition = products.get(id);
        if (definition == null) {
            return Optional.empty();
        }
        return Optional.of(new Product(
                id, definition.name(), definition.price(), inventoryStore.getStock(id)));
    }

    public void initializeInventory() {
        products.forEach((id, definition) -> inventoryStore.initialize(id, definition.initialStock()));
    }

    private record ProductDefinition(String name, BigDecimal price, int initialStock) {
    }
}

