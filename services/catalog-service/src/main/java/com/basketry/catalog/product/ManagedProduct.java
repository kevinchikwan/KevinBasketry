package com.basketry.catalog.product;

import com.basketry.catalog.stockrow.StockRowStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A product as its merchant and Admin see it (GET /manage/products, and the
 * answer of POST and PATCH): inactive products too, and where its stock row in
 * inventory-service stands, so a merchant can see why a product stays out of
 * stock (DESIGN.md Section 3, catalog-service).
 */
public record ManagedProduct(long id, long merchantId, String name, String description, String category,
                             BigDecimal price, boolean active, StockRowStatus stockRow, Instant createdAt,
                             Instant updatedAt) {
}
