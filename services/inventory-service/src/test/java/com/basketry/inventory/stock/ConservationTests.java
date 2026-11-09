package com.basketry.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.inventory.counter.StockReconciler;
import com.basketry.inventory.stock.ReservationOutcome.Reserved;
import com.basketry.inventory.support.InventoryServiceTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Row 11 of the Day 10 review: reservations, adjustments and overlapping
 * releases all at once, and no unit is created or lost. Afterwards:
 * {@code quantity + sum of RESERVED = initial + sum of applied deltas}, and the
 * counter equals PostgreSQL after reconciliation.
 */
class ConservationTests extends InventoryServiceTestBase {

    private static final long INITIAL = 60;

    @Autowired
    private StockReconciler reconciler;

    @Test
    void noUnitIsCreatedOrLostUnderMixedConcurrentChanges() throws Exception {
        givenStockAndCounter(42, INITIAL);
        AtomicLong appliedDeltas = new AtomicLong();
        List<Long> reservedOrders = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            // Phase 1: 48 reservations of 1 to 3 units and 16 adjustments of +-2, interleaved.
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> phase1 = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                int task = i;
                phase1.add(pool.submit(() -> {
                    start.await();
                    if (task % 4 == 3) {
                        long delta = task % 8 == 3 ? 2 : -2;
                        try {
                            stock.adjust(new AdjustCommand(42, delta));
                            appliedDeltas.addAndGet(delta);
                        } catch (StockWouldGoNegativeException refused) {
                            // nothing applied
                        }
                    } else {
                        long orderId = 1_000 + task;
                        if (stock.reserve(reserve(orderId, 42, 1 + task % 3)) instanceof Reserved) {
                            reservedOrders.add(orderId);
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : phase1) {
                f.get(60, TimeUnit.SECONDS);
            }

            // Phase 2: every second reservation is released twice at the same moment.
            List<Future<?>> phase2 = new ArrayList<>();
            for (int i = 0; i < reservedOrders.size(); i += 2) {
                long orderId = reservedOrders.get(i);
                int quantity = reservation(orderId, 42).getQuantity();
                CyclicBarrier together = new CyclicBarrier(2);
                for (int copy = 0; copy < 2; copy++) {
                    phase2.add(pool.submit(() -> {
                        together.await(10, TimeUnit.SECONDS);
                        stock.release(release(orderId, 42, quantity));
                        return null;
                    }));
                }
            }
            for (Future<?> f : phase2) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        long stillReserved = reservations.findAll().stream()
                .filter(r -> r.getStatus() == ReservationStatus.RESERVED)
                .mapToLong(StockReservation::getQuantity).sum();
        assertThat(reservedOrders).isNotEmpty();
        assertThat(quantityOf(42)).isNotNegative();
        assertThat(quantityOf(42) + stillReserved).isEqualTo(INITIAL + appliedDeltas.get());

        reconciler.reconcileAll();
        assertThat(counterOf(42)).hasValue(quantityOf(42));
    }
}
