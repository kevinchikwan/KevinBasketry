package com.basketry.catalog.web;

import com.basketry.catalog.product.ProductChanges;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Body of PATCH /products/{id}, as merchant-service's PATCH body: a class, not
 * a record, because it has to tell an omitted field from an explicit null.
 *
 * <ul>
 *   <li>{@code name}, {@code description}, {@code category}, {@code price} and
 *       {@code active} are each optional; omitted means unchanged, and at least
 *       one must be present.</li>
 *   <li>An explicit {@code null} is a 400, never "no change" or "false". A
 *       description is cleared with {@code ""}.</li>
 *   <li>{@code price} a JSON number and {@code active} a JSON boolean (the
 *       global Jackson setting), the others JSON strings (the strict
 *       deserializer).</li>
 *   <li>Any other field is a 400 naming it, so the merchant never believes a
 *       change was applied when it was not.</li>
 * </ul>
 */
public class UpdateProductRequest {

    private String name;
    private boolean namePresent;
    private String description;
    private boolean descriptionPresent;
    private String category;
    private boolean categoryPresent;
    private BigDecimal price;
    private boolean pricePresent;
    private Boolean active;
    private boolean activePresent;
    private final List<String> unknownFields = new ArrayList<>();

    @JsonDeserialize(using = StrictStringDeserializer.class)
    public void setName(String name) {
        this.name = name;
        this.namePresent = true;
    }

    @JsonDeserialize(using = StrictStringDeserializer.class)
    public void setDescription(String description) {
        this.description = description;
        this.descriptionPresent = true;
    }

    @JsonDeserialize(using = StrictStringDeserializer.class)
    public void setCategory(String category) {
        this.category = category;
        this.categoryPresent = true;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
        this.pricePresent = true;
    }

    public void setActive(Boolean active) {
        this.active = active;
        this.activePresent = true;
    }

    @JsonAnySetter
    void unknownField(String field, Object ignored) {
        unknownFields.add(field);
    }

    /**
     * Every rule of the body, before the caller's capability is looked at (400
     * before 403): the shape here, the field rules in {@link ProductChanges}.
     */
    ProductChanges toChanges() {
        if (!unknownFields.isEmpty()) {
            throw new InvalidRequestBodyException("Unknown or not updatable field(s): "
                    + String.join(", ", new TreeSet<>(unknownFields))
                    + ". Only name, description, category, price and active can be changed.");
        }
        List<String> nulls = new ArrayList<>();
        nullIfPresent(namePresent, name, "name", nulls);
        nullIfPresent(descriptionPresent, description, "description", nulls);
        nullIfPresent(categoryPresent, category, "category", nulls);
        nullIfPresent(pricePresent, price, "price", nulls);
        nullIfPresent(activePresent, active, "active", nulls);
        if (!nulls.isEmpty()) {
            throw new InvalidRequestBodyException("Field(s) must not be null: " + String.join(", ", nulls)
                    + ". Omit a field to leave it unchanged; send \"\" to clear the description.");
        }
        return new ProductChanges(Optional.ofNullable(name), Optional.ofNullable(description),
                Optional.ofNullable(category), Optional.ofNullable(price), Optional.ofNullable(active));
    }

    private static void nullIfPresent(boolean present, Object value, String field, List<String> nulls) {
        if (present && value == null) {
            nulls.add(field);
        }
    }
}
