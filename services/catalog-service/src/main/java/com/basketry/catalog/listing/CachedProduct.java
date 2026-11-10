package com.basketry.catalog.listing;

/**
 * One product of a listing page as the cache stores it: everything but its
 * availability, which is read from inventory-service on every listing and never
 * cached. The price is a string, so its scale survives the round trip exactly.
 *
 * @param stockRowFailed the stock row could not be created for this product (CONFLICT: inventory-service has
 *                       a row of another merchant; REJECTED: it refused the request): always out of stock
 */
public record CachedProduct(long id, long merchantId, String name, String description, String category,
                            String price, boolean stockRowFailed) {
}
