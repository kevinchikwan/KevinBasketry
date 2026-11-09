package com.basketry.inventory.stock;

/**
 * The bounds the application enforces (DESIGN.md Section 2, inventory-service).
 * None of them is a database CHECK: a release can lift a product above
 * {@link #MAX_STOCK}, and must never fail on it.
 */
public final class StockLimits {

    /** The most one reservation (one order line) may take. More is QUANTITY_OVER_LIMIT. */
    public static final int MAX_RESERVATION_QUANTITY = 1_000;

    /** The largest change one adjustment may make, either way. */
    public static final long MAX_ADJUSTMENT = 1_000_000;

    /** An increase may not take a product above this. Releases may. */
    public static final long MAX_STOCK = 1_000_000;

    private StockLimits() {
    }
}
