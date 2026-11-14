package com.basketry.order.saga;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code basketry.order} in application.yml: the saga's times (DESIGN.md
 * Section 2, order-service, the timer). The fingerprint key, also under
 * {@code basketry.order}, is read by FingerprintKey.
 *
 * @param sagaDeadline  a result not in by then counts as failed (2 min)
 * @param timerInterval the pause between two timer passes (5 s)
 * @param startupGrace  the timer does nothing this long after the service starts (60 s)
 * @param compensation  the resends of an unconfirmed refund or release
 * @param timer         the timer's switch and batch
 */
@ConfigurationProperties("basketry.order")
public record SagaProperties(Duration sagaDeadline, Duration timerInterval, Duration startupGrace,
                             Compensation compensation, Timer timer) {

    /**
     * @param resendInterval how long a refund or release waits for its confirmation before it is sent again (1 min)
     * @param maxResends     how many times it is sent again before its leg is STUCK (3)
     */
    public record Compensation(Duration resendInterval, int maxResends) {
    }

    /**
     * @param enabled   off in tests, which run a pass directly
     * @param batchSize the most orders one pass handles (100)
     */
    public record Timer(boolean enabled, int batchSize) {
    }
}
