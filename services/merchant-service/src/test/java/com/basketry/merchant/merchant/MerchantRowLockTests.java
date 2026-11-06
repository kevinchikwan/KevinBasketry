package com.basketry.merchant.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.merchant.outbox.OutboxEvent;
import com.basketry.merchant.support.MerchantServiceTestBase;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The row lock (DESIGN.md Section 2, merchant-service). Hibernate writes every
 * column, so without it the later of two overlapping updates puts back what the
 * earlier one changed.
 *
 * <p>The overlap tests do not depend on lucky timing: the first update's
 * transaction is held open while the second runs. With the lock the second
 * waits for it; without the lock the second finishes first and the first then
 * overwrites it, every time. Each runs 20 times regardless, to show it is not
 * flaky.
 */
class MerchantRowLockTests extends MerchantServiceTestBase {

    private static final int REPEATS = 20;
    /** How long the first transaction stays open if the second does not finish. Below H2's lock timeout. */
    private static final long HOLD_MILLIS = 150;

    @Autowired
    private MerchantUpdateService updates;

    @Autowired
    private MerchantOnboardingService onboarding;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ExecutorService otherThreads;

    @BeforeEach
    void startThreads() {
        otherThreads = Executors.newCachedThreadPool();
    }

    @AfterEach
    void stopThreads() {
        otherThreads.shutdownNow();
    }

    private static UpdateMerchantRequest active(boolean value) {
        UpdateMerchantRequest request = new UpdateMerchantRequest();
        request.setActive(value);
        return request;
    }

    private static UpdateMerchantRequest commission(String value) {
        UpdateMerchantRequest request = new UpdateMerchantRequest();
        request.setCommissionRate(new BigDecimal(value));
        return request;
    }

    /** Runs {@code first} in a transaction that stays open while {@code second} runs on another thread. */
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

    private Merchant reload(Merchant merchant) {
        return merchants.findById(merchant.getId()).orElseThrow();
    }

    private void assertBothChangesSurvived(Merchant merchant) {
        Merchant after = reload(merchant);
        assertThat(after.isActive()).as("active").isFalse();
        assertThat(after.getStatusVersion()).as("statusVersion").isEqualTo(1);
        assertThat(after.getCommissionRate()).as("commissionRate").isEqualByComparingTo("7.25");
        assertThat(outbox.findByMerchantIdOrderByIdAsc(merchant.getId()))
                .extracting(OutboxEvent::getVersion).containsExactly(1L);
    }

    @RepeatedTest(REPEATS)
    void aCommissionPatchCannotUndoAStatusChange() throws Exception {
        Merchant merchant = saveLinked("shop@example.com");
        String code = merchant.getMerchantCode();

        overlap(() -> updates.update(code, commission("7.25")), () -> updates.update(code, active(false)));

        assertBothChangesSurvived(merchant);
    }

    @RepeatedTest(REPEATS)
    void aStatusPatchCannotUndoACommissionChange() throws Exception {
        Merchant merchant = saveLinked("shop@example.com");
        String code = merchant.getMerchantCode();

        overlap(() -> updates.update(code, active(false)), () -> updates.update(code, commission("7.25")));

        assertBothChangesSurvived(merchant);
    }

    @RepeatedTest(REPEATS)
    void twoIdenticalStatusPatchesMakeOneChange() throws Exception {
        Merchant merchant = saveLinked("shop@example.com");
        String code = merchant.getMerchantCode();

        overlap(() -> updates.update(code, active(false)), () -> updates.update(code, active(false)));

        assertThat(reload(merchant).getStatusVersion()).isEqualTo(1);
        assertThat(outbox.findByMerchantIdOrderByIdAsc(merchant.getId())).hasSize(1);
    }

    /** Regression: a commission PATCH read PENDING, the link committed LINKED, the PATCH wrote PENDING back. */
    @RepeatedTest(REPEATS)
    void aCommissionPatchCannotPutLinkedBackToPending() throws Exception {
        Merchant merchant = savePending("shop@example.com");
        String code = merchant.getMerchantCode();
        identityLinks(HttpStatus.OK, "LINKED");

        overlap(() -> updates.update(code, commission("7.25")),
                () -> onboarding.retryLink(code, AccountLinkRequest.EMPTY));

        Merchant after = reload(merchant);
        assertThat(after.getAccountLinkStatus()).isEqualTo(AccountLinkStatus.LINKED);
        assertThat(after.getCommissionRate()).isEqualByComparingTo("7.25");
    }

    /** Not a race: PATCHes without any overlap, against a linked merchant, keep LINKED. */
    @Test
    void aCommissionPatchLeavesLinkedAlone() throws Exception {
        Merchant merchant = saveLinked("shop@example.com");

        patchAsAdmin(merchant, "{\"commissionRate\":7.25}").andExpect(status().isOk());

        assertThat(reload(merchant).getAccountLinkStatus()).isEqualTo(AccountLinkStatus.LINKED);
    }

    /**
     * The lock is never held during the call to identity-service: while the
     * call is in progress there is no open transaction, and a PATCH of the
     * same merchant finishes at once instead of waiting.
     */
    @Test
    void noLockOrTransactionDuringTheIdentityCall() {
        Merchant merchant = savePending("shop@example.com");
        String code = merchant.getMerchantCode();
        AtomicBoolean transactionDuringCall = new AtomicBoolean(true);
        expectLink(ExpectedCount.once()).andRespond(request -> {
            transactionDuringCall.set(TransactionSynchronizationManager.isActualTransactionActive());
            try {
                otherThreads.submit(() -> updates.update(code, commission("7.25"))).get(5, TimeUnit.SECONDS);
            } catch (Exception patchWaitedOrFailed) {
                throw new AssertionError("The PATCH could not run during the identity call", patchWaitedOrFailed);
            }
            return withStatus(HttpStatus.OK)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"id\":7,\"buyerActive\":true,\"merchantActive\":true,\"outcome\":\"LINKED\"}")
                    .createResponse(request);
        });

        onboarding.retryLink(code, AccountLinkRequest.EMPTY);

        assertThat(transactionDuringCall).isFalse();
        Merchant after = reload(merchant);
        assertThat(after.getAccountLinkStatus()).isEqualTo(AccountLinkStatus.LINKED);
        assertThat(after.getCommissionRate()).isEqualByComparingTo("7.25");
    }

    /**
     * Free-running: real requests racing with no scripted order. Whatever the
     * order, the versions have no gaps or repeats, each event flips the last
     * one, and the merchant agrees with the newest event.
     */
    @RepeatedTest(REPEATS)
    void racingPatchesKeepTheMerchantAndItsEventsConsistent() throws Exception {
        Merchant merchant = saveLinked("shop@example.com");
        int threads = 4;
        int requestsEach = 5;
        CyclicBarrier start = new CyclicBarrier(threads);
        List<Future<?>> running = new ArrayList<>();
        for (int thread = 0; thread < threads; thread++) {
            int number = thread;
            running.add(otherThreads.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                for (int request = 0; request < requestsEach; request++) {
                    boolean value = (number + request) % 2 == 0;
                    String body = number % 2 == 0
                            ? "{\"active\":" + value + "}"
                            : "{\"commissionRate\":" + (number + request) + "}";
                    patchAsAdmin(merchant, body).andExpect(status().isOk());
                }
                return null;
            }));
        }
        for (Future<?> thread : running) {
            thread.get(30, TimeUnit.SECONDS);
        }

        List<OutboxEvent> events = outbox.findByMerchantIdOrderByIdAsc(merchant.getId());
        boolean expected = true;
        for (int index = 0; index < events.size(); index++) {
            expected = !expected;
            JsonNode payload = JSON.readTree(events.get(index).getPayload());
            assertThat(events.get(index).getVersion()).isEqualTo(index + 1);
            assertThat(payload.get("version").asLong()).isEqualTo(index + 1);
            assertThat(payload.get("active").asBoolean()).isEqualTo(expected);
        }
        Merchant after = reload(merchant);
        assertThat(after.getStatusVersion()).isEqualTo(events.size());
        assertThat(after.isActive()).isEqualTo(expected);
    }
}
