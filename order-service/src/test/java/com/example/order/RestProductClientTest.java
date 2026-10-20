package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RestProductClientTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/products/1", exchange -> {
            byte[] body = "{\"id\":1,\"name\":\"Mechanical Keyboard\",\"price\":599.00}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/products/999", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/api/products/2", exchange -> {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void decodesProductJson() {
        ProductResponse product = new RestProductClient(baseUrl).getProduct(1L);

        assertThat(product.name()).isEqualTo("Mechanical Keyboard");
        assertThat(product.price()).isEqualByComparingTo("599.00");
    }

    @Test
    void mapsRemoteNotFound() {
        assertThatThrownBy(() -> new RestProductClient(baseUrl).getProduct(999L))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void mapsConnectionFailure() {
        RestProductClient client = new RestProductClient(baseUrl);
        server.stop(0);
        server = null;

        assertThatThrownBy(() -> client.getProduct(1L))
                .isInstanceOf(ProductServiceUnavailableException.class);
    }

    @Test
    void mapsReadTimeout() {
        RestProductClient client = new RestProductClient(baseUrl, Duration.ofMillis(100));

        assertThatThrownBy(() -> client.getProduct(2L))
                .isInstanceOf(ProductServiceUnavailableException.class);
    }
}
