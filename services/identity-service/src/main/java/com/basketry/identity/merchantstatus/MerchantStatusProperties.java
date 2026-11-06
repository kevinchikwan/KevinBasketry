package com.basketry.identity.merchantstatus;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The merchant.status.changed consumer (DESIGN.md Section 5).
 *
 * @param topic                created by merchant-service, never here
 * @param deadLetterTopic      where a record goes that can never be applied; created here, on first use
 * @param deadLetterPartitions the partition count of the dead-letter topic
 * @param replicationFactor    1 while there is one broker
 * @param retryInitial         the wait after the first database failure; it doubles after each further one
 * @param retryMax             the longest wait
 * @param consumer             the listener itself
 */
@ConfigurationProperties("basketry.merchant-status")
public record MerchantStatusProperties(
        String topic,
        String deadLetterTopic,
        int deadLetterPartitions,
        short replicationFactor,
        Duration retryInitial,
        Duration retryMax,
        Consumer consumer) {

    /** @param enabled off in tests, which call {@link MerchantStatusHandler} directly */
    public record Consumer(boolean enabled) {
    }
}
