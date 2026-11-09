package com.basketry.inventory.stock;

/** The state of a stock_reservation row (DESIGN.md Section 2, inventory-service). */
public enum ReservationStatus {
    /** The stock was taken. The only state that holds stock. */
    RESERVED,
    /** Was RESERVED; the stock was given back. */
    RELEASED,
    /** Nothing was taken, for the stored reason code. Also the tombstone of a release that came first. */
    FAILED
}
