package com.example.product;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitInventoryConfiguration {
    public static final String ORDERS_EXCHANGE = "orders.exchange";
    public static final String ORDER_CREATED_ROUTING_KEY = "order.created";
    public static final String ORDER_CREATED_QUEUE = "inventory.order-created";
    public static final String DEAD_LETTER_EXCHANGE = "orders.dlx";
    public static final String DEAD_LETTER_ROUTING_KEY = "order.created.failed";
    public static final String DEAD_LETTER_QUEUE = "inventory.order-created.dlq";

    @Bean
    TopicExchange ordersExchange() {
        return ExchangeBuilder.topicExchange(ORDERS_EXCHANGE).durable(true).build();
    }

    @Bean
    TopicExchange ordersDeadLetterExchange() {
        return ExchangeBuilder.topicExchange(DEAD_LETTER_EXCHANGE).durable(true).build();
    }

    @Bean
    Queue orderCreatedQueue() {
        return QueueBuilder.durable(ORDER_CREATED_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    Queue orderCreatedDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding orderCreatedBinding(Queue orderCreatedQueue, TopicExchange ordersExchange) {
        return BindingBuilder.bind(orderCreatedQueue)
                .to(ordersExchange)
                .with(ORDER_CREATED_ROUTING_KEY);
    }

    @Bean
    Binding orderCreatedDeadLetterBinding(
            Queue orderCreatedDeadLetterQueue, TopicExchange ordersDeadLetterExchange) {
        return BindingBuilder.bind(orderCreatedDeadLetterQueue)
                .to(ordersDeadLetterExchange)
                .with(DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    MessageConverter rabbitMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    RabbitListenerRetrySettingsCustomizer inventoryRetrySettingsCustomizer() {
        return settings -> settings.setExceptionPredicate(
                RabbitInventoryConfiguration::isTransientLockFailure);
    }

    private static boolean isTransientLockFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof InventoryLockUnavailableException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
