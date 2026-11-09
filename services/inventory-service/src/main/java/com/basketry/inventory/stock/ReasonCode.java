package com.basketry.inventory.stock;

/** Why a reservation failed; the {@code reasonCode} of order.stock.reservation-failed (DESIGN.md Section 5). */
public enum ReasonCode {
    /** PostgreSQL has less stock than asked. */
    INSUFFICIENT_STOCK,
    /** No stock row for the product. */
    UNKNOWN_PRODUCT,
    /** More than {@link StockLimits#MAX_RESERVATION_QUANTITY} in one line. */
    QUANTITY_OVER_LIMIT,
    /** A release of this order and product came first. Stored as a tombstone, so this reserve takes nothing. */
    RELEASED_BEFORE_RESERVE
}
