package com.basketry.catalog.merchantstatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.basketry.catalog.support.CatalogServiceTestBase;
import java.time.Instant;
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
 * Two instances applying the first event of the same merchant at once, on a
 * real PostgreSQL: the MERGE that comes second waits for the other's insert
 * and then fails on the primary key, as MerchantStatusRepository#apply says.
 * What it throws must be one the listener retries (and the retry then takes
 * the MATCHED branch), never one it dead-letters. Only in postgresTest: H2
 * locks differently. Mutation: isDatabaseFailure accepts only
 * TransientDataAccessException (a duplicate key is non-transient).
 */
@Tag("postgres")
class MerchantInsertRaceTests extends CatalogServiceTestBase {

    private static final long MERCHANT_ID = 801;
    private static final long HOLD_MILLIS = 1_000;

    private ExecutorService otherThreads;
    private Future<?> other;

    @BeforeEach
    void startThreads() {
        otherThreads = Executors.newSingleThreadExecutor();
    }

    /** The other transaction must be over before the next test's cleanup. */
    @AfterEach
    void finishTheOtherTransaction() throws Exception {
        if (other != null) {
            other.get(HOLD_MILLIS * 10, TimeUnit.MILLISECONDS);
        }
        otherThreads.shutdownNow();
    }

    @Test
    void theLoserOfTheInsertRaceGetsARetriedDatabaseErrorAndTheRetryApplies() throws Exception {
        CountDownLatch inserted = new CountDownLatch(1);
        other = otherThreads.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            merchantStatusRepository.apply(MERCHANT_ID, true, 1, Instant.now());
            inserted.countDown();
            try {
                Thread.sleep(HOLD_MILLIS);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(inserted.await(5, TimeUnit.SECONDS)).as("the other instance inserted, uncommitted").isTrue();

        Throwable lost = catchThrowable(() -> merchantStatuses.apply(MERCHANT_ID, false, 2));

        assertThat(lost).as("the MERGE that waited for the other insert").isNotNull();
        assertThat(MerchantStatusConsumerConfig.isDatabaseFailure(lost)).as(lost.toString()).isTrue();
        other.get(HOLD_MILLIS * 10, TimeUnit.MILLISECONDS);
        // The listener's retry: now the row exists, and the newer version replaces it.
        assertThat(merchantStatuses.apply(MERCHANT_ID, false, 2)).isTrue();
        assertThat(merchantStatusRepository.findById(MERCHANT_ID).orElseThrow().isActive()).isFalse();
    }
}
