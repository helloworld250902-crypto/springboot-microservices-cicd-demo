package com.example.product;

import com.example.events.OrderCreatedEvent;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InventoryMessageListener {
    private static final Logger log = LoggerFactory.getLogger(InventoryMessageListener.class);

    private final InventoryStore inventoryStore;
    private final RedissonClient redissonClient;
    private final Duration lockWait;
    private final Duration lockLease;

    public InventoryMessageListener(
            InventoryStore inventoryStore,
            RedissonClient redissonClient,
            @Value("${inventory.lock-wait:2s}") Duration lockWait,
            @Value("${inventory.lock-lease:10s}") Duration lockLease) {
        this.inventoryStore = inventoryStore;
        this.redissonClient = redissonClient;
        this.lockWait = lockWait;
        this.lockLease = lockLease;
    }

    @RabbitListener(queues = RabbitInventoryConfiguration.ORDER_CREATED_QUEUE)
    public void handle(OrderCreatedEvent event) {
        RLock lock = redissonClient.getLock("lock:inventory:" + event.productId());
        boolean acquired = false;
        try {
            acquired = lock.tryLock(
                    lockWait.toMillis(), lockLease.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new InventoryLockUnavailableException(event.productId());
            }

            InventoryDeductionResult result = inventoryStore.deductOnce(
                    event.orderId(), event.productId(), event.quantity());
            switch (result) {
                case DEDUCTED -> log.info(
                        "Inventory deducted: orderId={}, productId={}, quantity={}",
                        event.orderId(), event.productId(), event.quantity());
                case DUPLICATE -> log.info("Duplicate order event ignored: orderId={}", event.orderId());
                case INSUFFICIENT_STOCK -> throw rejected(
                        "Insufficient inventory for order " + event.orderId());
                case PRODUCT_NOT_FOUND -> throw rejected(
                        "Inventory product not found for order " + event.orderId());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InventoryLockUnavailableException(event.productId(), exception);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private AmqpRejectAndDontRequeueException rejected(String message) {
        return new AmqpRejectAndDontRequeueException(message);
    }
}
