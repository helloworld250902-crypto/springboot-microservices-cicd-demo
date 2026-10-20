package com.example.order;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class RestProductClient implements ProductClient {
    private final RestClient restClient;

    @Autowired
    public RestProductClient(
            @Value("${product-service.base-url}") String baseUrl,
            @Value("${product-service.timeout:2s}") Duration timeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    RestProductClient(String baseUrl) {
        this(baseUrl, Duration.ofSeconds(2));
    }

    @Override
    public ProductResponse getProduct(long id) {
        try {
            return restClient.get()
                    .uri("/api/products/{id}", id)
                    .retrieve()
                    .body(ProductResponse.class);
        } catch (HttpClientErrorException.NotFound exception) {
            throw new ProductNotFoundException(id);
        } catch (RestClientException exception) {
            throw new ProductServiceUnavailableException(exception);
        }
    }
}
