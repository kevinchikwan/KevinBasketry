package com.basketry.catalog.listing;

/**
 * One product of a listing page as the cache stores it: everything but its
 * availability, which is read from inventory-service on every listing and never
 * cached. The price is a string, so its scale survives the round trip exactly.
 *
 * @param stockConflict the product's stock row belongs to another merchant: always out of stock
 */
public record CachedProduct(long id, long merchantId, String name, String description, String category,
                            String price, boolean stockConflict) {
}
