import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public class SmokeTestServer {
    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(args[0]);
        AtomicInteger stock = new AtomicInteger(100);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/actuator/health", exchange ->
                respond(exchange, 200, "{\"status\":\"UP\"}"));
        server.createContext("/api/products/1", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "");
                return;
            }
            respond(exchange, 200,
                    "{\"id\":1,\"name\":\"Mechanical Keyboard\","
                            + "\"price\":599.00,\"stock\":" + stock.get() + "}");
        });
        server.createContext("/api/orders", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "");
                return;
            }
            stock.addAndGet(-2);
            respond(exchange, 201,
                    "{\"orderId\":\"00000000-0000-0000-0000-000000000001\","
                            + "\"productId\":1,\"productName\":\"Mechanical Keyboard\","
                            + "\"quantity\":2,\"unitPrice\":599.00,\"totalPrice\":1198.00}");
        });
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
