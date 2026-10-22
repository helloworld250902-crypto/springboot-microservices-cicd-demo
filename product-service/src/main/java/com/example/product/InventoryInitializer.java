package com.example.product;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class InventoryInitializer implements ApplicationRunner {
    private final ProductCatalog productCatalog;

    public InventoryInitializer(ProductCatalog productCatalog) {
        this.productCatalog = productCatalog;
    }

    @Override
    public void run(ApplicationArguments args) {
        productCatalog.initializeInventory();
    }
}
