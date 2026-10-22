package com.example.product;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = {ProductServiceApplication.class, ProductControllerTest.FakeInventoryConfiguration.class},
        properties = {
                "spring.rabbitmq.listener.simple.auto-startup=false",
                "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV4"
        })
@AutoConfigureMockMvc
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsKnownProduct() throws Exception {
        mockMvc.perform(get("/api/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Mechanical Keyboard"))
                .andExpect(jsonPath("$.price").value(599.00))
                .andExpect(jsonPath("$.stock").value(42));
    }

    @Test
    void returnsProblemDetailForUnknownProduct() throws Exception {
        mockMvc.perform(get("/api/products/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Product not found"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("999")));
    }

    @TestConfiguration
    static class FakeInventoryConfiguration {
        @Bean
        RedissonClient fakeRedissonClient() {
            return mock(RedissonClient.class);
        }

        @Bean
        @Primary
        InventoryStore fakeInventoryStore() {
            return new InventoryStore() {
                @Override
                public void initialize(long productId, int stock) {
                }

                @Override
                public int getStock(long productId) {
                    return 42;
                }

                @Override
                public InventoryDeductionResult deductOnce(
                        java.util.UUID orderId, long productId, int quantity) {
                    return InventoryDeductionResult.DEDUCTED;
                }
            };
        }
    }
}
