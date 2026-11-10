package com.basketry.catalog.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.catalog.support.CatalogServiceTestBase;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Row 2 of the Day 12 review: the row lock, copied from merchant-service.
 * Hibernate writes every column, so without it the later of two overlapping
 * PATCHes puts back what the earlier one changed.
 *
 * <p>Not lucky timing: the first update's transaction is held open while the
 * second runs. With the lock the second waits; without it, the second commits
 * first and the first then overwrites it, every time. Each runs 20 times
 * regardless, to show it is not flaky. Mutation: read with
 * {@code findById} instead of {@code findByIdForUpdate}.
 */
class ProductRowLockTests extends CatalogServiceTestBase {

    private static final int REPEATS = 20;
    /** Below H2's lock timeout and far below the 5 s row-lock timeout, so the second waits rather than fails. */
    private static final long HOLD_MILLIS = 150;

    private ExecutorService otherThreads;

    @BeforeEach
    void startThreads() {
        otherThreads = Executors.newCachedThreadPool();
    }

    @AfterEach
    void stopThreads() {
        otherThreads.shutdownNow();
    }

    private static ProductChanges price(String value) {
        return new ProductChanges(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(new BigDecimal(value)), Optional.empty());
    }

    private static ProductChanges active(boolean value) {
        return new ProductChanges(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(value));
    }

    /**
     * Runs {@code first} in a transaction that stays open while {@code second}
     * runs on another thread. {@code first} joins that transaction (the
     * service's TransactionTemplate propagates REQUIRED), so its lock is held.
     * The invalidation after it is skipped here, because the outer transaction
     * is still open; that is not what these tests are about.
     */
    private void overlap(Runnable first, Callable<?> second) throws Exception {
        List<Future<?>> started = new ArrayList<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            first.run();
            Future<?> other = otherThreads.submit(second);
            started.add(other);
            try {
                other.get(HOLD_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException waiting) {
                // The second update is waiting for this transaction's lock, as it should.
            } catch (Exception failed) {
                throw new AssertionError(failed);
            }
        });
        started.getFirst().get(5, TimeUnit.SECONDS);
    }

    /** The first update inside the held transaction: the same lock and change as the service's, without its invalidation. */
    private void lockAndChange(long productId, ProductChanges changes) {
        Product product = products.findByIdForUpdate(productId).orElseThrow();
        changes.applyTo(product);
    }

    private void assertBothChangesSurvived(long productId) {
        Product after = products.findById(productId).orElseThrow();
        assertThat(after.getPrice()).as("price").isEqualByComparingTo("9.99");
        assertThat(after.isActive()).as("active").isFalse();
    }

    @RepeatedTest(REPEATS)
    void aPriceChangeCannotUndoADeactivation() throws Exception {
        long id = givenProduct(MERCHANT, "Tea", "2.50").id();

        overlap(() -> lockAndChange(id, price("9.99")), () -> productService.update(MERCHANT, id, active(false)));

        assertBothChangesSurvived(id);
    }

    @RepeatedTest(REPEATS)
    void aDeactivationCannotUndoAPriceChange() throws Exception {
        long id = givenProduct(MERCHANT, "Tea", "2.50").id();

        overlap(() -> lockAndChange(id, active(false)), () -> productService.update(MERCHANT, id, price("9.99")));

        assertBothChangesSurvived(id);
    }
}
