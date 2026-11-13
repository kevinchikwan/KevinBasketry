package com.basketry.order.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The topics order-service owns and the outbox relay (DESIGN.md Section 5).
 * The topic names are not settings: each outbox row carries its own, from
 * {@link OrderRequests}.
 *
 * @param partitions        of each topic; fixed once in use, because changing it moves keys between partitions
 * @param replicationFactor 1 while there is one broker
 * @param sendTimeout       how long to wait for one record's acknowledgement
 * @param relay             the scheduled publisher
 */
@ConfigurationProperties("basketry.outbox")
public record OutboxProperties(
        int partitions,
        short replicationFactor,
        Duration sendTimeout,
        Relay relay) {

    /**
     * @param enabled        off in tests, which run the relay directly
     * @param interval       the pause between two passes
     * @param batchSize      the most rows one pass publishes
     * @param backoffInitial the wait after the first failed pass; it doubles after each further one
     * @param backoffMax     the longest wait
     */
    public record Relay(
            boolean enabled,
            Duration interval,
            int batchSize,
            Duration backoffInitial,
            Duration backoffMax) {
    }
}
