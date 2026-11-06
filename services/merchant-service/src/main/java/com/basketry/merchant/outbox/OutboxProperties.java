package com.basketry.merchant.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The status topic and the outbox relay (DESIGN.md Section 5).
 *
 * @param topic             where status changes are published
 * @param partitions        fixed once in use: changing it moves keys between partitions
 * @param replicationFactor 1 while there is one broker
 * @param sendTimeout       how long to wait for one record's acknowledgement
 * @param relay             the scheduled publisher
 */
@ConfigurationProperties("basketry.outbox")
public record OutboxProperties(
        String topic,
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
