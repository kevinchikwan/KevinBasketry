package com.basketry.inventory.stock;

/** A product's stock row as committed. */
public record StockSnapshot(long productId, long merchantId, long quantity) {
}
