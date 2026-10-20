package com.example.product;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductCatalog catalog;

    public ProductController(ProductCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/{id}")
    public Product findById(@PathVariable long id) {
        return catalog.findById(id).orElseThrow(() -> notFound(id));
    }

    private ErrorResponseException notFound(long id) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, "Product " + id + " was not found");
        problem.setTitle("Product not found");
        return new ErrorResponseException(HttpStatus.NOT_FOUND, problem, null);
    }
}

