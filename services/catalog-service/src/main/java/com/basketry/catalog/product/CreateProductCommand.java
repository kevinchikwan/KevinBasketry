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

    /**
     * The field rules alone, before the owner is known: the REST layer checks
     * the body (400) before the caller's capability (403), as everywhere in
     * the services (DESIGN.md Section 3).
     *
     * @throws InvalidProductException naming every broken rule
     */
    public static void checkFields(String name, String description, String category, BigDecimal price) {
        List<String> violations = new ArrayList<>();
        ProductFields.name(name, violations);
        ProductFields.description(description, violations);
        ProductFields.category(category, violations);
        ProductFields.price(price, violations);
        ProductFields.throwIfAny(violations);
    }
}
