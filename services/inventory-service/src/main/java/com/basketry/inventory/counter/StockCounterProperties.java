package com.basketry.inventory.counter;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code basketry.stock-counter} in application.yml. */
@ConfigurationProperties("basketry.stock-counter")
public record StockCounterProperties(@DefaultValue("10s") Duration skipAfterFailure,
                                     @DefaultValue Reconciliation reconciliation) {

    public record Reconciliation(@DefaultValue("true") boolean enabled,
                                 @DefaultValue("5m") Duration interval,
                                 @DefaultValue("500") int pageSize) {
    }
}
