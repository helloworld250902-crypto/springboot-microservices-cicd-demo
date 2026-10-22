package com.example.product;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.boot.retry.RetryPolicySettings;

class RabbitInventoryConfigurationTest {

    @Test
    void retriesOnlyTransientInventoryLockFailures() {
        RabbitListenerRetrySettingsCustomizer customizer =
                new RabbitInventoryConfiguration().inventoryRetrySettingsCustomizer();
        RetryPolicySettings settings = new RetryPolicySettings();

        customizer.customize(settings);

        assertThat(settings.getExceptionPredicate().test(
                new RuntimeException(new InventoryLockUnavailableException(1L))))
                .isTrue();
        assertThat(settings.getExceptionPredicate().test(
                new AmqpRejectAndDontRequeueException("permanent")))
                .isFalse();
        assertThat(settings.getExceptionPredicate().test(new IllegalArgumentException("bad")))
                .isFalse();
    }
}
