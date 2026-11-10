package com.basketry.catalog.stockrow;

import java.time.Duration;
import java.time.Instant;

/**
 * When the relay may try again after a failed pass: doubling waits up to a
 * maximum. A copy of inventory-service's, because the services share no code.
 */
class RelayBackoff {

    private final Duration initial;
    private final Duration max;
    private Duration nextWait;
    private Instant notBefore = Instant.MIN;

    RelayBackoff(Duration initial, Duration max) {
        this.initial = initial;
        this.max = max;
        this.nextWait = initial;
    }

    boolean isReady(Instant now) {
        return !now.isBefore(notBefore);
    }

    void failed(Instant now) {
        notBefore = now.plus(nextWait);
        Duration doubled = nextWait.multipliedBy(2);
        nextWait = doubled.compareTo(max) > 0 ? max : doubled;
    }

    void succeeded() {
        nextWait = initial;
        notBefore = Instant.MIN;
    }
}
