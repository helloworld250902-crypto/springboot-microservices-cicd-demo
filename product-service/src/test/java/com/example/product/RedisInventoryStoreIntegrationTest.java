package com.example.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class RedisInventoryStoreIntegrationTest {

    @Container
    private static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7.4.11-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static RedisInventoryStore store;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        store = new RedisInventoryStore(redisTemplate);
    }

    @AfterEach
    void clearRedis() {
        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushAll();
        }
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void duplicateOrderDeductsOnlyOnce() {
        UUID orderId = UUID.randomUUID();
        store.initialize(1L, 5);

        assertThat(store.deductOnce(orderId, 1L, 2))
                .isEqualTo(InventoryDeductionResult.DEDUCTED);
        assertThat(store.deductOnce(orderId, 1L, 2))
                .isEqualTo(InventoryDeductionResult.DUPLICATE);

        assertThat(store.getStock(1L)).isEqualTo(3);
        assertThat(redisTemplate.opsForValue().get("processed-order:" + orderId)).isEqualTo("1");
    }

    @Test
    void exactStockCanReachZero() {
        UUID orderId = UUID.randomUUID();
        store.initialize(1L, 2);

        assertThat(store.deductOnce(orderId, 1L, 2))
                .isEqualTo(InventoryDeductionResult.DEDUCTED);
        assertThat(store.getStock(1L)).isZero();
    }

    @Test
    void insufficientStockDoesNotChangeInventoryOrMarkOrder() {
        UUID orderId = UUID.randomUUID();
        store.initialize(1L, 1);

        assertThat(store.deductOnce(orderId, 1L, 2))
                .isEqualTo(InventoryDeductionResult.INSUFFICIENT_STOCK);
        assertThat(store.getStock(1L)).isEqualTo(1);
        assertThat(redisTemplate.opsForValue().get("processed-order:" + orderId)).isNull();
    }

    @Test
    void missingProductDoesNotMarkOrder() {
        UUID orderId = UUID.randomUUID();

        assertThat(store.deductOnce(orderId, 99L, 2))
                .isEqualTo(InventoryDeductionResult.PRODUCT_NOT_FOUND);
        assertThat(redisTemplate.opsForValue().get("processed-order:" + orderId)).isNull();
    }
}
