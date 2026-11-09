package com.basketry.inventory.web;

import com.basketry.inventory.stock.StockLevel;
import com.basketry.inventory.stock.StockSnapshot;

/**
 * One product's stock, as every stock endpoint returns it. {@code quantity} is
 * a long, as stored (BIGINT): it is the stock still available, with
 * reservations in progress already taken off.
 */
public record StockResponse(long productId, long merchantId, long quantity) {

    static StockResponse from(StockLevel level) {
        return new StockResponse(level.getProductId(), level.getMerchantId(), level.getQuantity());
    }

    static StockResponse from(StockSnapshot snapshot) {
        return new StockResponse(snapshot.productId(), snapshot.merchantId(), snapshot.quantity());
    }
}
