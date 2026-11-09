package com.basketry.inventory.web;

import java.util.List;

/** Body of GET /internal/stock: the known products of the request, in product order. */
public record StockAvailability(List<Item> content) {

    public record Item(long productId, long quantity) {
    }
}
