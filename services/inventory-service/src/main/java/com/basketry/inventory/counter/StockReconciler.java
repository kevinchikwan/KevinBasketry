package com.basketry.inventory.counter;

import com.basketry.inventory.stock.StockLevelRepository;
import com.basketry.inventory.stock.StockLevelRepository.ProductQuantity;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/**
 * Overwrites every stock counter from PostgreSQL, which always wins (DESIGN.md
 * Section 2, inventory-service). This repairs drift in both directions: a
 * counter left too low by a crash between Redis and PostgreSQL, and one left
 * too high by a lost reply, a missed decrement while Redis was down, or this
 * very reconciliation racing a reservation in flight. Neither direction can
 * oversell or wrongly refuse, so the overwrite needs no locking.
 *
 * <p>Pages through stock_level in product order, never holding a transaction
 * open while it talks to Redis. Stops at the first Redis failure.
 */
@Component
public class StockReconciler {

    private static final Logger log = LoggerFactory.getLogger(StockReconciler.class);

    private final StockLevelRepository stockLevels;
    private final StockCounterGuard counter;
    private final int pageSize;

    public StockReconciler(StockLevelRepository stockLevels, StockCounterGuard counter,
                           StockCounterProperties properties) {
        this.stockLevels = stockLevels;
        this.counter = counter;
        this.pageSize = properties.reconciliation().pageSize();
    }

    /** The number of counters written. Partial if Redis failed on the way. */
    public long reconcileAll() {
        long written = 0;
        long after = 0;
        while (true) {
            List<ProductQuantity> page = stockLevels.findPageAfter(after, Limit.of(pageSize));
            for (ProductQuantity stock : page) {
                if (!counter.overwrite(stock.productId(), stock.quantity())) {
                    log.warn("Stock counter reconciliation stopped after {} counters: Redis is unavailable", written);
                    return written;
                }
                written++;
            }
            if (page.size() < pageSize) {
                log.info("Stock counter reconciliation wrote {} counters from PostgreSQL", written);
                return written;
            }
            after = page.getLast().productId();
        }
    }
}
