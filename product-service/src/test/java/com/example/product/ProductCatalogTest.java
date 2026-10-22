package com.example.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductCatalogTest {

    @Test
    void returnsSharedInventoryWithProductMetadata() {
        FakeInventoryStore inventoryStore = new FakeInventoryStore();
        inventoryStore.stocks.put(1L, 37);
        ProductCatalog catalog = new ProductCatalog(inventoryStore);

        Product product = catalog.findById(1L).orElseThrow();

        assertThat(product.name()).isEqualTo("Mechanical Keyboard");
        assertThat(product.price()).isEqualByComparingTo("599.00");
        assertThat(product.stock()).isEqualTo(37);
    }

    @Test
    void initializesKnownProductsWithoutOwningPersistenceRules() {
        FakeInventoryStore inventoryStore = new FakeInventoryStore();
        ProductCatalog catalog = new ProductCatalog(inventoryStore);

        catalog.initializeInventory();

        assertThat(inventoryStore.stocks).containsEntry(1L, 100).containsEntry(2L, 50);
    }

    private static final class FakeInventoryStore implements InventoryStore {
        private final Map<Long, Integer> stocks = new HashMap<>();

        @Override
        public void initialize(long productId, int stock) {
            stocks.putIfAbsent(productId, stock);
        }

        @Override
        public int getStock(long productId) {
            return stocks.getOrDefault(productId, 0);
        }

        @Override
        public InventoryDeductionResult deductOnce(UUID orderId, long productId, int quantity) {
            throw new UnsupportedOperationException();
        }
    }
}
