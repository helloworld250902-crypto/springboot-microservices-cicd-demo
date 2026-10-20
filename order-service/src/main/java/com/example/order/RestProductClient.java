package com.example.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class RestProductClient implements ProductClient {
    private final RestClient restClient;

    public RestProductClient(@Value("${product-service.base-url}") String baseUrl) {
        this.restClient = RestClient.create(baseUrl);
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
