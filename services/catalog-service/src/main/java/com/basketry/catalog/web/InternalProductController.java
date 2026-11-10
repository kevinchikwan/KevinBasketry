package com.basketry.catalog.web;

import com.basketry.catalog.product.InternalProduct;
import com.basketry.catalog.product.ProductReads;
import java.util.List;
import java.util.TreeSet;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /internal/products?ids=1,2,3, for order-service's re-validation of every
 * checkout line (DESIGN.md Section 3, catalog-service): the real check of
 * purchasability, not the listing. Internal key only, in its own security
 * chain. Provisional until order-service is built.
 */
@RestController
public class InternalProductController {

    static final int MAX_IDS = 100;

    private final ProductReads reads;

    public InternalProductController(ProductReads reads) {
        this.reads = reads;
    }

    /**
     * 1 to 100 positive ids, else 400. The products that exist, in id order, a
     * repeated id answered once; unknown ids are left out. Inactive products
     * and products of inactive merchants are included, with their flags, so
     * order-service can tell why a line is refused.
     */
    @GetMapping("/internal/products")
    public Content list(@RequestParam List<Long> ids) {
        if (ids.isEmpty() || ids.size() > MAX_IDS || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new InvalidRequestBodyException("ids must be 1 to " + MAX_IDS + " positive product ids.");
        }
        return new Content(reads.internal(new TreeSet<>(ids)));
    }

    public record Content(List<InternalProduct> content) {
    }
}
