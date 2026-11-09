package com.basketry.inventory.counter;

import com.basketry.inventory.counter.StockCounter.TakeResult;
import java.time.Clock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The only way the service reaches the stock counters. Redis is never needed
 * (DESIGN.md Section 2, inventory-service): any failure or timeout becomes
 * "unavailable", and the caller goes on with the PostgreSQL gate alone. After a
 * failure, every call skips Redis for {@code skip-after-failure} instead of
 * waiting for a timeout each time.
 *
 * <p>A failed call may still have been applied (the reply was lost). The guard
 * never reports such a take as taken, so nothing that may not have been taken
 * is ever given back: at worst the counter ends up too low, which the
 * confirming read and reconciliation make harmless.
 */
@Component
public class StockCounterGuard {

    private static final Logger log = LoggerFactory.getLogger(StockCounterGuard.class);

    private final StockCounter counter;
    private final long skipMillis;
    private final Clock clock;

    private volatile long skipUntil;
    private volatile boolean down;

    @Autowired
    public StockCounterGuard(StockCounter counter, StockCounterProperties properties) {
        this(counter, properties, Clock.systemUTC());
    }

    public StockCounterGuard(StockCounter counter, StockCounterProperties properties, Clock clock) {
        this.counter = counter;
        this.skipMillis = properties.skipAfterFailure().toMillis();
        this.clock = clock;
    }

    public Take take(long productId, int quantity) {
        TakeResult result = call(() -> counter.take(productId, quantity));
        if (result == null) {
            return Take.UNAVAILABLE;
        }
        return switch (result) {
            case TAKEN -> Take.TAKEN;
            case INSUFFICIENT -> Take.INSUFFICIENT;
            case NOT_LOADED -> Take.NOT_LOADED;
        };
    }

    /** False if Redis was skipped or failed; the counter is then repaired by reconciliation. */
    public boolean addIfLoaded(long productId, long delta) {
        return call(() -> {
            counter.addIfLoaded(productId, delta);
            return Boolean.TRUE;
        }) != null;
    }

    /** False if Redis was skipped or failed, or another call loaded the counter first. */
    public boolean loadIfAbsent(long productId, long quantity) {
        return Boolean.TRUE.equals(call(() -> counter.loadIfAbsent(productId, quantity)));
    }

    /** False if Redis was skipped or failed. */
    public boolean overwrite(long productId, long quantity) {
        return call(() -> {
            counter.overwrite(productId, quantity);
            return Boolean.TRUE;
        }) != null;
    }

    /** Null if skipped or failed. */
    private <T> T call(Supplier<T> action) {
        if (clock.millis() < skipUntil) {
            return null;
        }
        try {
            T result = action.get();
            if (down) {
                down = false;
                log.info("The stock counters (Redis) are reachable again");
            }
            return result;
        } catch (RuntimeException e) {
            skipUntil = clock.millis() + skipMillis;
            if (!down) {
                down = true;
                log.warn("The stock counters (Redis) are unavailable ({}: {}). Reservations use the PostgreSQL"
                        + " gate alone; Redis is tried again in {} ms", e.getClass().getSimpleName(),
                        e.getMessage(), skipMillis);
            } else {
                log.debug("The stock counters (Redis) are still unavailable", e);
            }
            return null;
        }
    }

    public enum Take {
        TAKEN,
        INSUFFICIENT,
        NOT_LOADED,
        /** Redis failed, timed out or was skipped. Nothing is known to have been taken. */
        UNAVAILABLE
    }
}
