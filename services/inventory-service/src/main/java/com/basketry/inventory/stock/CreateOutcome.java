package com.basketry.inventory.stock;

/** {@code created} is false when the same merchant's row already existed; nothing changed then. */
public record CreateOutcome(StockSnapshot stock, boolean created) {
}
