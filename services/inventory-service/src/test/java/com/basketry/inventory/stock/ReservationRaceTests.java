package com.basketry.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.inventory.stock.ReservationOutcome.Reserved;
import com.basketry.inventory.support.InventoryServiceTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Two first requests of one reservation at the same time. Runs on H2 and, in
 * postgresTest, on PostgreSQL, where a failed insert aborts its whole
 * transaction.
 */
class ReservationRaceTests extends InventoryServiceTestBase {

    /**
     * Deterministic: the winner's row is committed, and the loser's lookup is
     * made to miss it, so the loser takes from the counter, its insert hits the
     * primary key, and its take from PostgreSQL rolls back. It must give the
     * counter back what it took, then answer from the winner's row.
     */
    @Test
    void theLoserOfARaceAnswersWithTheWinnersResultAndRestoresTheCounter() {
        givenStockAndCounter(42, 5);
        stock.reserve(reserve(7, 42, 2));
        StockService loser = serviceWith(firstLookupAnswers(Optional.empty()));

        ReservationOutcome lost = loser.reserve(reserve(7, 42, 2));

        assertThat(lost).isEqualTo(new Reserved());
        assertThat(quantityOf(42)).isEqualTo(3);
        assertThat(counterOf(42)).hasValue(3);
        assertThat(reservations.count()).isEqualTo(1);
        assertThat(topics()).containsExactly("order.stock.reserved", "order.stock.reserved");
    }

    /** Not deterministic, so it backs up the test above rather than replacing it. */
    @Test
    void manyConcurrentFirstRequestsTakeTheStockOnce() throws Exception {
        givenStockAndCounter(42, 5);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ReservationOutcome>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return stock.reserve(reserve(7, 42, 2));
                }));
            }
            start.countDown();

            List<ReservationOutcome> outcomes = new ArrayList<>();
            for (Future<ReservationOutcome> result : results) {
                outcomes.add(result.get(30, TimeUnit.SECONDS)); // any exception fails here
            }
            assertThat(outcomes).containsOnly(new Reserved());
            assertThat(quantityOf(42)).isEqualTo(3);
            assertThat(counterOf(42)).hasValue(3);
            assertThat(reservations.count()).isEqualTo(1);
            assertThat(topics()).hasSize(threads).containsOnly("order.stock.reserved");
        } finally {
            pool.shutdownNow();
        }
    }
}
