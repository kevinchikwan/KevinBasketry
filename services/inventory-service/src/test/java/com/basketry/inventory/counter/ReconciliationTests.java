package com.basketry.inventory.counter;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.inventory.counter.StockCounterProperties.Reconciliation;
import com.basketry.inventory.support.InMemoryStockCounter;
import com.basketry.inventory.support.InventoryServiceTestBase;
import com.basketry.inventory.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Row 3 of the Day 10 review: PostgreSQL always wins, in both directions. */
class ReconciliationTests extends InventoryServiceTestBase {

    @Autowired
    private StockReconciler reconciler;

    @Test
    void reconciliationRepairsDriftInBothDirectionsAndLoadsMissingCounters() {
        givenStock(41, 5);
        counter.overwrite(41, 2);
        givenStock(42, 5);
        counter.overwrite(42, 9);
        givenStock(43, 7);

        long written = reconciler.reconcileAll();

        assertThat(written).isEqualTo(3);
        assertThat(counterOf(41)).hasValue(5);
        assertThat(counterOf(42)).hasValue(5);
        assertThat(counterOf(43)).hasValue(7);
    }

    @Test
    void reconciliationPagesThroughEveryProduct() {
        for (long productId = 1; productId <= 5; productId++) {
            givenStock(productId, productId * 10);
        }
        StockReconciler smallPages = new StockReconciler(stockLevels, new StockCounterGuard(counter,
                counterProperties), new StockCounterProperties(Duration.ofSeconds(10),
                new Reconciliation(false, Duration.ofMinutes(5), 2)));

        assertThat(smallPages.reconcileAll()).isEqualTo(5);
        for (long productId = 1; productId <= 5; productId++) {
            assertThat(counterOf(productId)).hasValue(productId * 10);
        }
    }

    @Test
    void reconciliationStopsQuietlyWhenRedisIsDown() {
        givenStock(41, 5);
        givenStock(42, 5);
        InMemoryStockCounter down = new InMemoryStockCounter();
        down.setUnavailable(true);
        StockReconciler reconcilerOnDownRedis = new StockReconciler(stockLevels,
                new StockCounterGuard(down, counterProperties, new MutableClock()), counterProperties);

        assertThat(reconcilerOnDownRedis.reconcileAll()).isZero();
        assertThat(down.calls()).isEqualTo(1);
    }
}
