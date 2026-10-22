package com.example.order;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.example.events.OrderCreatedEvent;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class RabbitOrderEventPublisherTest {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final RabbitOrderEventPublisher publisher =
            new RabbitOrderEventPublisher(rabbitTemplate, Duration.ofMillis(100));
    private final OrderCreatedEvent event = new OrderCreatedEvent(UUID.randomUUID(), 1L, 2);

    @Test
    void returnsAfterBrokerAcknowledgesEvent() {
        completeConfirmation(true, null);

        publisher.publish(event);
    }

    @Test
    void rejectsOrderWhenBrokerNacksEvent() {
        completeConfirmation(false, "broker rejected message");

        assertThatThrownBy(() -> publisher.publish(event))
                .isInstanceOf(OrderEventPublishException.class)
                .hasMessageContaining("broker rejected message");
    }

    @Test
    void rejectsOrderWhenBrokerDoesNotConfirmInTime() {
        assertThatThrownBy(() -> publisher.publish(event))
                .isInstanceOf(OrderEventPublishException.class)
                .hasMessageContaining("confirm");
    }

    @Test
    void rejectsOrderWhenBrokerAcknowledgesButReturnsUnroutableEvent() {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.setReturned(new ReturnedMessage(
                    new Message(new byte[0]),
                    312,
                    "NO_ROUTE",
                    RabbitOrderConfiguration.ORDERS_EXCHANGE,
                    RabbitOrderConfiguration.ORDER_CREATED_ROUTING_KEY));
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(RabbitOrderConfiguration.ORDERS_EXCHANGE),
                eq(RabbitOrderConfiguration.ORDER_CREATED_ROUTING_KEY),
                eq(event),
                any(CorrelationData.class));

        assertThatThrownBy(() -> publisher.publish(event))
                .isInstanceOf(OrderEventPublishException.class)
                .hasMessageContaining("NO_ROUTE");
    }

    private void completeConfirmation(boolean ack, String reason) {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(ack, reason));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(RabbitOrderConfiguration.ORDERS_EXCHANGE),
                eq(RabbitOrderConfiguration.ORDER_CREATED_ROUTING_KEY),
                eq(event),
                any(CorrelationData.class));
    }
}
