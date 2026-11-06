package com.basketry.merchant.outbox;

/**
 * There is no FAILED: a row the relay cannot publish stays PENDING and is
 * retried, because giving up on it would lose the event.
 */
public enum OutboxStatus {
    PENDING,
    SENT
}
