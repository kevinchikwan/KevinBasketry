package com.basketry.order.outbox;

/**
 * There is no FAILED: a row the relay cannot publish stays PENDING and is
 * retried, because giving up on it would lose the request. CANCELLED is the
 * timer's: an unsent charge or reserve of an order that already failed, never
 * sent (DESIGN.md Section 2, order-service, the timer).
 */
public enum OutboxStatus {
    PENDING,
    SENT,
    CANCELLED
}
