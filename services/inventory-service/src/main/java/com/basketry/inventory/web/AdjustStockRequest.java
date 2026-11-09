package com.basketry.inventory.web;

import com.basketry.inventory.stock.AdjustCommand;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Body of POST /stock/{productId}/adjustments: {@code {"delta": n}}. A whole
 * JSON number (1.5, "5" and true are 400s, through the global Jackson
 * settings), not null, not 0, at most 1,000,000 either way (AdjustCommand).
 * Any other field is a 400 naming it, as in merchant-service.
 */
public record AdjustStockRequest(@NotNull Long delta, @JsonAnySetter Map<String, Object> unknownFields) {

    public AdjustStockRequest {
        unknownFields = unknownFields == null ? new LinkedHashMap<>() : unknownFields;
    }

    void rejectUnknownFields() {
        if (!unknownFields.isEmpty()) {
            throw new InvalidRequestBodyException("Unknown field(s): "
                    + String.join(", ", new TreeSet<>(unknownFields.keySet())) + ". Only delta is accepted.");
        }
    }

    AdjustCommand toCommand(long productId) {
        return new AdjustCommand(productId, delta);
    }
}
