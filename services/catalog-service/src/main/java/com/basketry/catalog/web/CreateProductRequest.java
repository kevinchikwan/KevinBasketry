package com.basketry.catalog.web;

import com.basketry.catalog.product.CreateProductCommand;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Body of POST /products: {@code name}, {@code category} and {@code price}
 * required, {@code description} optional. Each field takes only its own JSON
 * type: {@code price} a number, never a string (the global Jackson setting in
 * application.yml); the others strings, never a number or a boolean (the
 * strict deserializer: Jackson converts those even with that setting). Any other field, {@code active} and {@code merchantId} included, is
 * a 400 naming it: a new product is always active, and its owner always comes
 * from the token.
 */
public record CreateProductRequest(@JsonDeserialize(using = StrictStringDeserializer.class) String name,
                                   @JsonDeserialize(using = StrictStringDeserializer.class) String description,
                                   @JsonDeserialize(using = StrictStringDeserializer.class) String category,
                                   BigDecimal price,
                                   @JsonAnySetter Map<String, Object> unknownFields) {

    public CreateProductRequest {
        unknownFields = unknownFields == null ? new LinkedHashMap<>() : unknownFields;
    }

    /** Every rule of the body, before the caller's capability is looked at (400 before 403). */
    void check() {
        if (!unknownFields.isEmpty()) {
            throw new InvalidRequestBodyException("Unknown field(s): "
                    + String.join(", ", new TreeSet<>(unknownFields.keySet()))
                    + ". Only name, description, category and price are accepted.");
        }
        CreateProductCommand.checkFields(name, description, category, price);
    }

    CreateProductCommand toCommand(long merchantId) {
        return new CreateProductCommand(merchantId, name, description, category, price);
    }
}
