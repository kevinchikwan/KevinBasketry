package com.basketry.catalog.product;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The fields a PATCH changes; an empty Optional means "leave it as it is". At
 * least one must be present. Telling an omitted field from an explicit JSON
 * null (a 400) is the REST layer's job, before this is built. A present
 * description may be blank, which clears it. Validated on construction, as
 * {@link CreateProductCommand}.
 */
public record ProductChanges(Optional<String> name, Optional<String> description, Optional<String> category,
                             Optional<BigDecimal> price, Optional<Boolean> active) {

    public ProductChanges {
        List<String> violations = new ArrayList<>();
        if (name.isEmpty() && description.isEmpty() && category.isEmpty() && price.isEmpty() && active.isEmpty()) {
            violations.add("Give at least one of name, description, category, price and active.");
        }
        name = name.map(value -> ProductFields.name(value, violations));
        description = description.map(value -> ProductFields.description(value, violations));
        category = category.map(value -> ProductFields.category(value, violations));
        price = price.map(value -> ProductFields.price(value, violations));
        ProductFields.throwIfAny(violations);
    }

    void applyTo(Product product) {
        name.ifPresent(product::setName);
        description.ifPresent(product::setDescription);
        category.ifPresent(product::setCategory);
        price.ifPresent(product::setPrice);
        active.ifPresent(product::setActive);
    }
}
