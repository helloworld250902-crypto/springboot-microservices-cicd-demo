package com.example.product;

import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class RedisInventoryStore implements InventoryStore {
    private static final DefaultRedisScript<Long> DEDUCT_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              return 1
            end
            local stock = redis.call('GET', KEYS[2])
            if not stock then
              return 3
            end
            if tonumber(stock) < tonumber(ARGV[1]) then
              return 2
            end
            redis.call('DECRBY', KEYS[2], ARGV[1])
            redis.call('SET', KEYS[1], '1')
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisInventoryStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void initialize(long productId, int stock) {
        redisTemplate.opsForValue().setIfAbsent(inventoryKey(productId), Integer.toString(stock));
    }

    @Override
    public int getStock(long productId) {
        String stock = redisTemplate.opsForValue().get(inventoryKey(productId));
        return stock == null ? 0 : Integer.parseInt(stock);
    }

    @Override
    public InventoryDeductionResult deductOnce(
            UUID orderId, long productId, int quantity) {
        Long code = redisTemplate.execute(
                DEDUCT_SCRIPT,
                List.of("processed-order:" + orderId, inventoryKey(productId)),
                Integer.toString(quantity));
        if (code == null || code < 0 || code >= InventoryDeductionResult.values().length) {
            throw new IllegalStateException("Unexpected inventory script result: " + code);
        }
        return InventoryDeductionResult.values()[code.intValue()];
    }

    private String inventoryKey(long productId) {
        return "inventory:" + productId;
    }
}
