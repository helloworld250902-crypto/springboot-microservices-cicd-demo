package com.example.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = {OrderServiceApplication.class, OrderControllerTest.FakeProductConfiguration.class})
@AutoConfigureMockMvc
class OrderControllerTest {

    private final MockMvc mockMvc;

    OrderControllerTest(@Qualifier("mockMvc") MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void createsOrder() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":1,"quantity":2}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productName").value("Mechanical Keyboard"))
                .andExpect(jsonPath("$.totalPrice").value(1198.00));
    }

    @Test
    void rejectsMissingQuantity() throws Exception {
        assertInvalid("{\"productId\":1,\"quantity\":null}");
    }

    @Test
    void rejectsZeroQuantity() throws Exception {
        assertInvalid("{\"productId\":1,\"quantity\":0}");
    }

    @Test
    void rejectsNegativeQuantity() throws Exception {
        assertInvalid("{\"productId\":1,\"quantity\":-1}");
    }

    @Test
    void mapsMissingProductToNotFound() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":999,\"quantity\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Product not found"));
    }

    @Test
    void mapsUnavailableProductService() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":888,\"quantity\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Product service unavailable"));
    }

    @Test
    void mapsOrderEventPublishFailureToServiceUnavailable() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":777,\"quantity\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Order event unavailable"));
    }

    private void assertInvalid(String body) throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @TestConfiguration
    static class FakeProductConfiguration {
        @Bean
        @Primary
        ProductClient fakeProductClient() {
            return id -> {
                if (id == 999L) {
                    throw new ProductNotFoundException(id);
                }
                if (id == 888L) {
                    throw new ProductServiceUnavailableException(new IllegalStateException("test"));
                }
                return new ProductResponse(id, "Mechanical Keyboard", new BigDecimal("599.00"));
            };
        }

        @Bean
        @Primary
        OrderEventPublisher fakeOrderEventPublisher() {
            return event -> {
                if (event.productId() == 777L) {
                    throw new OrderEventPublishException(new IllegalStateException("test"));
                }
            };
        }
    }
}
