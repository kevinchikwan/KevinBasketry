package com.basketry.catalog.listing;

import java.math.BigDecimal;

/** One product of a public listing, with its availability. */
public record ListedProduct(long id, long merchantId, String name, String description, String category,
                            BigDecimal price, Availability availability) {

    static ListedProduct of(CachedProduct product, Availability availability) {
        return new ListedProduct(product.id(), product.merchantId(), product.name(), product.description(),
                product.category(), new BigDecimal(product.price()), availability);
    }
}
