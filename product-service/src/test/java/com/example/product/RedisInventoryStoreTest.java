package com.example.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class RedisInventoryStoreTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedisInventoryStore store = new RedisInventoryStore(redisTemplate);

    @Test
    void initializesStockWithoutOverwritingExistingValue() {
        when(redisTemplate.opsForValue()).thenReturn(values);

        store.initialize(1L, 100);

        verify(values).setIfAbsent("inventory:1", "100");
    }

    @Test
    void readsCurrentStock() {
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get("inventory:1")).thenReturn("37");

        assertThat(store.getStock(1L)).isEqualTo(37);
    }

    @Test
    void mapsAtomicDeductionResultsIncludingExactStockBoundary() {
        UUID orderId = UUID.randomUUID();
        List<String> keys = List.of("processed-order:" + orderId, "inventory:1");

        when(redisTemplate.execute(any(RedisScript.class), eq(keys), eq("2")))
                .thenReturn(0L, 1L, 2L, 3L);

        assertThat(store.deductOnce(orderId, 1L, 2)).isEqualTo(InventoryDeductionResult.DEDUCTED);
        assertThat(store.deductOnce(orderId, 1L, 2)).isEqualTo(InventoryDeductionResult.DUPLICATE);
        assertThat(store.deductOnce(orderId, 1L, 2)).isEqualTo(InventoryDeductionResult.INSUFFICIENT_STOCK);
        assertThat(store.deductOnce(orderId, 1L, 2)).isEqualTo(InventoryDeductionResult.PRODUCT_NOT_FOUND);
    }
}
