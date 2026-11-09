package com.basketry.inventory.counter;

import java.util.OptionalLong;

/**
 * One mirrored counter per product (DESIGN.md Section 2, inventory-service):
 * a fast, atomic pre-check in front of the PostgreSQL gate, never the source of
 * truth. Implemented by {@link RedisStockCounter}; tests that need no Docker use
 * an in-memory fake with the same contract.
 *
 * <p>Any method may throw when the store is unreachable or slow. Callers go
 * through {@link StockCounterGuard}, which turns that into "unavailable".
 */
public interface StockCounter {

    /** The Redis key of a product's counter. */
    static String key(long productId) {
        return "inventory:stock:" + productId;
    }

    /**
     * In one atomic step: if the counter holds at least {@code quantity}, takes
     * it. Otherwise changes nothing.
     */
    TakeResult take(long productId, int quantity);

    /**
     * Adds {@code delta} (which may be negative) only if the counter exists. A
     * missing counter stays missing; it is loaded from PostgreSQL when next
     * needed. A plain INCRBY would create it holding only the delta.
     */
    void addIfLoaded(long productId, long delta);

    /** Sets the counter only if it does not exist yet. True if this call set it. */
    boolean loadIfAbsent(long productId, long quantity);

    /** Sets the counter whatever it held: reconciliation from PostgreSQL. */
    void overwrite(long productId, long quantity);

    /** The counter's value, or empty if it is not loaded. For tests and diagnostics. */
    OptionalLong current(long productId);

    enum TakeResult {
        TAKEN,
        /** The counter holds less than asked; nothing changed. */
        INSUFFICIENT,
        /** No counter for this product; nothing changed. Distinct from zero stock. */
        NOT_LOADED
    }
}
