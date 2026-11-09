package com.basketry.inventory.stock;

/** The answer to a release request: always "released", whatever had to be done for it. */
public record Released(long orderId, long productId) {
}
