package com.example.product;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.events.OrderCreatedEvent;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

class InventoryMessageListenerTest {

    private final InventoryStore inventoryStore = mock(InventoryStore.class);
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final InventoryMessageListener listener = new InventoryMessageListener(
            inventoryStore, redissonClient, Duration.ofMillis(50), Duration.ofSeconds(5));
    private final OrderCreatedEvent event = new OrderCreatedEvent(UUID.randomUUID(), 1L, 2);

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void deductsInventoryUnderProductSpecificLock() throws InterruptedException {
        lockAcquired();
        when(inventoryStore.deductOnce(event.orderId(), 1L, 2))
                .thenReturn(InventoryDeductionResult.DEDUCTED);

        listener.handle(event);

        verify(redissonClient).getLock("lock:inventory:1");
        verify(inventoryStore).deductOnce(event.orderId(), 1L, 2);
        verify(lock).unlock();
    }

    @Test
    void ignoresDuplicateOrderWithoutSecondDeductionFailure() throws InterruptedException {
        lockAcquired();
        when(inventoryStore.deductOnce(event.orderId(), 1L, 2))
                .thenReturn(InventoryDeductionResult.DUPLICATE);

        listener.handle(event);

        verify(lock).unlock();
    }

    @Test
    void rejectsInsufficientInventoryWithoutRequeue() throws InterruptedException {
        lockAcquired();
        when(inventoryStore.deductOnce(event.orderId(), 1L, 2))
                .thenReturn(InventoryDeductionResult.INSUFFICIENT_STOCK);

        assertThatThrownBy(() -> listener.handle(event))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(lock).unlock();
    }

    @Test
    void retriesWhenLockCannotBeAcquired() throws InterruptedException {
        when(redissonClient.getLock("lock:inventory:1")).thenReturn(lock);
        when(lock.tryLock(50, 5000, TimeUnit.MILLISECONDS)).thenReturn(false);

        assertThatThrownBy(() -> listener.handle(event))
                .isInstanceOf(InventoryLockUnavailableException.class);
        verify(inventoryStore, never()).deductOnce(event.orderId(), 1L, 2);
        verify(lock, never()).unlock();
    }

    @Test
    void preservesInterruptionAndDoesNotTouchInventory() throws InterruptedException {
        when(redissonClient.getLock("lock:inventory:1")).thenReturn(lock);
        when(lock.tryLock(50, 5000, TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException("test"));

        assertThatThrownBy(() -> listener.handle(event))
                .isInstanceOf(InventoryLockUnavailableException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(inventoryStore, never()).deductOnce(event.orderId(), 1L, 2);
    }

    @Test
    void doesNotUnlockWhenCurrentThreadNoLongerOwnsLock() throws InterruptedException {
        when(redissonClient.getLock("lock:inventory:1")).thenReturn(lock);
        when(lock.tryLock(50, 5000, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(false);
        when(inventoryStore.deductOnce(event.orderId(), 1L, 2))
                .thenReturn(InventoryDeductionResult.DEDUCTED);

        listener.handle(event);

        verify(lock, never()).unlock();
    }

    private void lockAcquired() throws InterruptedException {
        when(redissonClient.getLock("lock:inventory:1")).thenReturn(lock);
        when(lock.tryLock(50, 5000, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }
}
