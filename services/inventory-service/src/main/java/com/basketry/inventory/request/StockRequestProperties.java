package com.basketry.inventory.request;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The reserve and release request listeners (DESIGN.md Section 5).
 *
 * @param reserveTopic         order.stock-reserve.requested; order-service's, never created here
 * @param releaseTopic         order.stock-release.requested; order-service's, never created here
 * @param deadLetterPartitions the partition count of each dead-letter topic (request topic + ".DLT")
 * @param replicationFactor    1 while there is one broker
 * @param retryInitial         the wait after the first database failure; it doubles after each further one
 * @param retryMax             the longest wait
 * @param consumer             the listeners themselves
 */
@ConfigurationProperties("basketry.stock-requests")
public record StockRequestProperties(
        String reserveTopic,
        String releaseTopic,
        int deadLetterPartitions,
        short replicationFactor,
        Duration retryInitial,
        Duration retryMax,
        Consumer consumer) {

    public static final String DEAD_LETTER_SUFFIX = ".DLT";

    /** @param enabled off in tests, which call StockService directly unless a test class turns it on */
    public record Consumer(boolean enabled) {
    }
}
