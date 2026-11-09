package com.basketry.inventory.web;

import com.basketry.inventory.stock.CreateStockCommand;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Body of POST /internal/stock: {@code {"productId": p, "merchantId": m}},
 * both positive whole JSON numbers. Any other field (an initial quantity
 * included: a new product always starts at 0) is a 400 naming it.
 */
public record CreateStockRequest(@NotNull Long productId, @NotNull Long merchantId,
                                 @JsonAnySetter Map<String, Object> unknownFields) {

    public CreateStockRequest {
        unknownFields = unknownFields == null ? new LinkedHashMap<>() : unknownFields;
    }

    void rejectUnknownFields() {
        if (!unknownFields.isEmpty()) {
            throw new InvalidRequestBodyException("Unknown field(s): "
                    + String.join(", ", new TreeSet<>(unknownFields.keySet()))
                    + ". Only productId and merchantId are accepted; a new product starts at 0.");
        }
    }

    CreateStockCommand toCommand() {
        return new CreateStockCommand(productId, merchantId);
    }
}
