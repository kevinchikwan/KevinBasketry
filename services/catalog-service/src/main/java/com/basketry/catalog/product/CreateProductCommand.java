package com.basketry.catalog.product;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A new product for {@code merchantId}, the owner taken from the token, never
 * from the request. Validated and normalized on construction: every broken
 * rule is named in one {@link InvalidProductException}.
 */
public record CreateProductCommand(long merchantId, String name, String description, String category,
                                   BigDecimal price) {

    public CreateProductCommand {
        List<String> violations = new ArrayList<>();
        ProductFields.requirePositive(merchantId, "merchantId", violations);
        name = ProductFields.name(name, violations);
        description = ProductFields.description(description, violations);
        category = ProductFields.category(category, violations);
        price = ProductFields.price(price, violations);
        ProductFields.throwIfAny(violations);
    }
}
