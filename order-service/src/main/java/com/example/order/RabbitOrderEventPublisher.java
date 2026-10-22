package com.example.order;

import com.example.events.OrderCreatedEvent;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RabbitOrderEventPublisher implements OrderEventPublisher {
    private final RabbitTemplate rabbitTemplate;
    private final Duration confirmTimeout;

    public RabbitOrderEventPublisher(
            RabbitTemplate rabbitTemplate,
            @Value("${orders.publisher-confirm-timeout:5s}") Duration confirmTimeout) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeout = confirmTimeout;
    }

    @Override
    public void publish(OrderCreatedEvent event) {
        CorrelationData correlationData = new CorrelationData(event.orderId().toString());
        try {
            rabbitTemplate.convertAndSend(
                    RabbitOrderConfiguration.ORDERS_EXCHANGE,
                    RabbitOrderConfiguration.ORDER_CREATED_ROUTING_KEY,
                    event,
                    correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack()) {
                throw new OrderEventPublishException(confirm.reason());
            }
        } catch (OrderEventPublishException exception) {
            throw exception;
        } catch (AmqpException exception) {
            throw new OrderEventPublishException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OrderEventPublishException(exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new OrderEventPublishException(exception);
        }
    }
}
