package com.basketry.merchant.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.merchant.support.MerchantServiceTestBase;
import com.basketry.merchant.support.TestTokens;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The row-lock reads give up after {@link MerchantRepository#LOCK_TIMEOUT_MILLIS}
 * and answer 503 {@code MERCHANT_BUSY}, changing nothing. Only in postgresTest:
 * Postgres would otherwise wait forever, and the timing is Postgres's (H2 has
 * its own lock timeout).
 */
@Tag("postgres")
class MerchantLockTimeoutTests extends MerchantServiceTestBase {

    /** Longer than the lock timeout, so only the timeout can end the wait. */
    private static final long HOLD_MILLIS = 8_000;

    @Autowired
    private PlatformTransactionManager transactionManager;

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

    /** Another transaction takes the merchant's row lock and keeps it for HOLD_MILLIS. */
    private void holdTheRowLock(Merchant merchant) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        holder = otherThreads.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            merchants.findByIdForUpdate(merchant.getId()).orElseThrow();
            locked.countDown();
            try {
                Thread.sleep(HOLD_MILLIS);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).as("the other transaction holds the lock").isTrue();
    }

    private static void assertAboutTheLockTimeout(long startedNanos) {
        Duration waited = Duration.ofNanos(System.nanoTime() - startedNanos);
        long timeout = Long.parseLong(MerchantRepository.LOCK_TIMEOUT_MILLIS);
        assertThat(waited.toMillis()).as("waited ms").isBetween(timeout - 500, HOLD_MILLIS - 500);
    }

    @Test
    void aPatchOfALockedMerchantGivesUpWith503() throws Exception {
        Merchant merchant = saveLinked("shop@example.com");
        holdTheRowLock(merchant);

        long started = System.nanoTime();
        patchAsAdmin(merchant, "{\"active\":false,\"commissionRate\":9}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MERCHANT_BUSY"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.merchantCode").doesNotExist());
        assertAboutTheLockTimeout(started);

        holder.get(HOLD_MILLIS * 2, TimeUnit.MILLISECONDS);
        Merchant after = merchants.findById(merchant.getId()).orElseThrow();
        assertThat(after.isActive()).isTrue();
        assertThat(after.getCommissionRate()).isEqualByComparingTo("5.00");
        assertThat(outbox.findByMerchantIdOrderByIdAsc(merchant.getId())).isEmpty();
    }

    @Test
    void aLinkRetryForALockedMerchantGivesUpWith503AndStaysPending() throws Exception {
        Merchant merchant = savePending("shop@example.com");
        identityLinks(HttpStatus.OK, "LINKED");
        holdTheRowLock(merchant);

        long started = System.nanoTime();
        mockMvc.perform(bearer(post("/merchants/{code}/account-link", merchant.getMerchantCode()),
                        TestTokens.admin()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MERCHANT_BUSY"))
                .andExpect(jsonPath("$.merchantCode").value(merchant.getMerchantCode()));
        assertAboutTheLockTimeout(started);

        holder.get(HOLD_MILLIS * 2, TimeUnit.MILLISECONDS);
        assertThat(merchants.findById(merchant.getId()).orElseThrow().getAccountLinkStatus())
                .isEqualTo(AccountLinkStatus.PENDING);
    }
}
