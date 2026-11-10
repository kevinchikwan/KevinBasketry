package com.basketry.catalog.listing;

/**
 * Whether a listed product can be bought now (FR-B6, FR-M6), from
 * inventory-service's stock on every listing. Never the exact quantity.
 */
public enum Availability {
    IN_STOCK,
    /** Zero stock, no stock row yet (a new product), or a stock row of another merchant. */
    OUT_OF_STOCK,
    /**
     * inventory-service did not answer. Not "out of stock": a site-wide
     * "sold out" during an outage would be wrong, and checkout's reservation is
     * the real check anyway (DESIGN.md Section 3, catalog-service).
     */
    UNKNOWN
}
