package com.basketry.catalog.product;

import java.math.BigDecimal;
import java.time.Instant;

/** A product as stored, for the management reads and the answers of create and update. */
public record ProductView(long id, long merchantId, String name, String description, String category,
                          BigDecimal price, boolean active, Instant createdAt, Instant updatedAt) {
}
