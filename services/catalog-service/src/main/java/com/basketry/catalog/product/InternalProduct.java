package com.basketry.catalog.product;

import com.basketry.catalog.stockrow.StockRowStatus;
import java.math.BigDecimal;

/**
 * A product as order-service needs it to re-validate a checkout line
 * (GET /internal/products, DESIGN.md Section 3). The line is purchasable only
 * if {@code active}, {@code merchantActive} and a stock row that is not
 * CONFLICT or REJECTED; the price is the current one.
 *
 * @param merchantActive catalog-service's local copy, which follows merchant.status.changed and so lags it by
 *                       seconds; true for a merchant that never changed status
 */
public record InternalProduct(long id, long merchantId, String name, BigDecimal price, boolean active,
                              boolean merchantActive, StockRowStatus stockRow) {
}
