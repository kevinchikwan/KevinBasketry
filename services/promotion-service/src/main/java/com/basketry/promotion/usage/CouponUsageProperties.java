package com.basketry.promotion.usage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code basketry.coupon-usage} in application.yml: the order.succeeded listener
 * (DESIGN.md Section 5, promotion-service).
 *
 * @param deadLetterPartitions of the dead-letter topic (3)
 * @param replicationFactor    1 while there is one broker
 * @param retryInitial         the first wait after a database failure; it doubles after each further one
 * @param retryMax             the longest wait; there is no limit on attempts
 */
@ConfigurationProperties("basketry.coupon-usage")
public record CouponUsageProperties(int deadLetterPartitions, short replicationFactor, Duration retryInitial,
                                    Duration retryMax) {
}
