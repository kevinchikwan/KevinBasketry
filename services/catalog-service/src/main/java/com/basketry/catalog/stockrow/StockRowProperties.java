package com.basketry.catalog.stockrow;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code basketry.stock-rows} in application.yml. */
@ConfigurationProperties("basketry.stock-rows")
public record StockRowProperties(Relay relay) {

    /**
     * @param enabled        off in tests, which run the relay directly
     * @param interval       the pause between two passes
     * @param batchSize      the most requests one pass handles
     * @param backoffInitial the wait after the first failed pass; it doubles after each further one
     * @param backoffMax     the longest wait
     */
    public record Relay(boolean enabled, Duration interval, int batchSize, Duration backoffInitial,
                        Duration backoffMax) {
    }
}
