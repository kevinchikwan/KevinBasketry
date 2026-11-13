package com.basketry.order.order;

/**
 * The state of a line's reserve leg (DESIGN.md Section 2, order-service).
 * Checkout creates every line REQUESTED. TIMED_OUT means no answer by the
 * deadline, and a release was requested.
 */
public enum LineStatus {
    REQUESTED,
    RESERVED,
    FAILED,
    TIMED_OUT,
    RELEASE_REQUESTED,
    RELEASED,
    STUCK
}
