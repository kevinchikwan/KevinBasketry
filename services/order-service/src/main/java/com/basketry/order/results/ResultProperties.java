package com.basketry.order.results;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code basketry.order-results} in application.yml: the six result listeners
 * (DESIGN.md Section 5, order-service results).
 *
 * @param deadLetterPartitions of each dead-letter topic (3)
 * @param replicationFactor    1 while there is one broker
 * @param retryInitial         the first wait after a database failure; it doubles after each further one
 * @param retryMax             the longest wait; there is no limit on attempts
 */
@ConfigurationProperties("basketry.order-results")
public record ResultProperties(int deadLetterPartitions, short replicationFactor, Duration retryInitial,
                               Duration retryMax) {
}
