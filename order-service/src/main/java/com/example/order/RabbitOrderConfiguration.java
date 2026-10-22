package com.example.order;

import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitOrderConfiguration {
    public static final String ORDERS_EXCHANGE = "orders.exchange";
    public static final String ORDER_CREATED_ROUTING_KEY = "order.created";

    @Bean
    TopicExchange ordersExchange() {
        return ExchangeBuilder.topicExchange(ORDERS_EXCHANGE).durable(true).build();
    }

    @Bean
    MessageConverter rabbitMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
