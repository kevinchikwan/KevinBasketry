package com.basketry.payment.request;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The charge and refund request listeners (DESIGN.md Section 5).
 *
 * @param chargeTopic          order.merchant-charge.requested; order-service's, never created here
 * @param refundTopic          order.merchant-refund.requested; order-service's, never created here
 * @param deadLetterPartitions the partition count of each dead-letter topic (request topic + ".DLT")
 * @param replicationFactor    1 while there is one broker
 * @param retryInitial         the wait after the first database failure; it doubles after each further one
 * @param retryMax             the longest wait
 * @param consumer             the listeners themselves
 */
@ConfigurationProperties("basketry.payment-requests")
public record PaymentRequestProperties(
        String chargeTopic,
        String refundTopic,
        int deadLetterPartitions,
        short replicationFactor,
        Duration retryInitial,
        Duration retryMax,
        Consumer consumer) {

    public static final String DEAD_LETTER_SUFFIX = ".DLT";

    /** @param enabled off in tests, which call PaymentService directly unless a test class turns it on */
    public record Consumer(boolean enabled) {
    }
}
