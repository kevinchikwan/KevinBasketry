package com.basketry.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.inventory.stock.ReservationOutcome.ReservationFailed;
import com.basketry.inventory.stock.ReservationOutcome.Reserved;
import com.basketry.inventory.support.InventoryServiceTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Row 11 of the Day 10 review: 16 buyers, distinct orders, the last unit.
 * Exactly one gets it. The two guards are independent, so a mutation of one
 * alone cannot fail the combined test; each guard has its own test with the
 * other one neutralized (this class for PostgreSQL, RedisAtomicityTests for
 * Redis). Runs on H2 with the fake counter and, in postgresTest, on PostgreSQL
 * with Redis.
 */
class OversellTests extends InventoryServiceTestBase {

    private static final int THREADS = 16;

    /** Both guards real. In postgresTest: real Redis and real PostgreSQL. */
    @Test
    void sixteenBuyersOfTheLastUnitGetExactlyOne() throws Exception {
        givenStockAndCounter(42, 1);

        List<ReservationOutcome> outcomes = reserveConcurrently(42);

        assertThat(outcomes).filteredOn(Reserved.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(ReservationFailed.class::isInstance)
                .containsOnly(new ReservationFailed(ReasonCode.INSUFFICIENT_STOCK)).hasSize(THREADS - 1);
        assertThat(quantityOf(42)).isZero();
        assertThat(counterOf(42)).hasValue(0);
    }

    /**
     * The Redis guard neutralized: the counter says 100, so every buyer passes
     * the pre-check. The PostgreSQL gate alone must let exactly one through, and
     * the other 15 must fail cleanly (no exception: the CHECK on quantity is a
     * backstop, never the thing that answers).
     */
    @Test
    void thePostgresGateAloneLetsExactlyOneThrough() throws Exception {
        givenStock(42, 1);
        counter.overwrite(42, 100);

        List<ReservationOutcome> outcomes = reserveConcurrently(42);

        assertThat(outcomes).filteredOn(Reserved.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(ReservationFailed.class::isInstance)
                .containsOnly(new ReservationFailed(ReasonCode.INSUFFICIENT_STOCK)).hasSize(THREADS - 1);
        assertThat(quantityOf(42)).isZero();
        // Every loser gave back what it took from the counter.
        assertThat(counterOf(42)).hasValue(99);
    }

    private List<ReservationOutcome> reserveConcurrently(long productId) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ReservationOutcome>> results = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                long orderId = 100 + i;
                results.add(pool.submit(() -> {
                    start.await();
                    return stock.reserve(reserve(orderId, productId, 1));
                }));
            }
            start.countDown();
            List<ReservationOutcome> outcomes = new ArrayList<>();
            for (Future<ReservationOutcome> result : results) {
                outcomes.add(result.get(30, TimeUnit.SECONDS)); // any exception fails here
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
