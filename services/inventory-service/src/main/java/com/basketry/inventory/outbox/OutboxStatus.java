package com.basketry.inventory.outbox;

/**
 * There is no FAILED: a row the relay cannot publish stays PENDING and is
 * retried, because giving up on it would lose the result.
 */
public enum OutboxStatus {
    PENDING,
    SENT
}
