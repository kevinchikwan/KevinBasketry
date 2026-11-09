package com.basketry.inventory.web;

import com.basketry.inventory.stock.CreateOutcome;

/** Body of POST /internal/stock: the stock row plus {@code outcome}, CREATED (201) or ALREADY_EXISTS (200). */
public record CreateStockResponse(long productId, long merchantId, long quantity, String outcome) {

    static CreateStockResponse from(CreateOutcome outcome) {
        return new CreateStockResponse(outcome.stock().productId(), outcome.stock().merchantId(),
                outcome.stock().quantity(), outcome.created() ? "CREATED" : "ALREADY_EXISTS");
    }
}
