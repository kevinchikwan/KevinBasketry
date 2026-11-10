package com.basketry.catalog.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basketry.catalog.support.CatalogServiceTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Row 2 of the Day 12 review: an update gives up after
 * {@link ProductRepository#LOCK_TIMEOUT_MILLIS} with {@link ProductBusyException}
 * (503 PRODUCT_BUSY), changing nothing. Only in postgresTest: Postgres would
 * otherwise wait forever, and the timing is Postgres's (H2 has its own lock
 * timeout). Mutation: drop the lock-timeout hint (the update would wait for the
 * whole hold, then succeed).
 */
@Tag("postgres")
class ProductLockTimeoutTests extends CatalogServiceTestBase {

    /** Longer than the lock timeout, so only the timeout can end the wait. */
    private static final long HOLD_MILLIS = 8_000;

    private ExecutorService otherThreads;
    private Future<?> holder;

    @BeforeEach
    void startThreads() {
        otherThreads = Executors.newSingleThreadExecutor();
    }

    /** The lock must be gone before the next test's cleanup deletes the rows. */
    @AfterEach
    void releaseTheLock() throws Exception {
        if (holder != null) {
            holder.get(HOLD_MILLIS * 2, TimeUnit.MILLISECONDS);
        }
        otherThreads.shutdownNow();
    }

    @Test
    void anUpdateOfALockedProductGivesUpAfterTheLockTimeout() throws Exception {
        long id = givenProduct(MERCHANT, "Tea", "2.50").id();
        CountDownLatch locked = new CountDownLatch(1);
        holder = otherThreads.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            products.findByIdForUpdate(id).orElseThrow();
            locked.countDown();
            try {
                Thread.sleep(HOLD_MILLIS);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).as("the other transaction holds the lock").isTrue();
        long started = System.nanoTime();

        assertThatThrownBy(() -> productService.update(MERCHANT, id, new ProductChanges(Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(new BigDecimal("9.99")), Optional.empty())))
                .isInstanceOf(ProductBusyException.class);

        long waited = Duration.ofNanos(System.nanoTime() - started).toMillis();
        long timeout = Long.parseLong(ProductRepository.LOCK_TIMEOUT_MILLIS);
        assertThat(waited).as("waited ms").isBetween(timeout - 500, HOLD_MILLIS - 500);
        holder.get(HOLD_MILLIS * 2, TimeUnit.MILLISECONDS);
        assertThat(products.findById(id).orElseThrow().getPrice()).isEqualByComparingTo("2.50");
    }
}
