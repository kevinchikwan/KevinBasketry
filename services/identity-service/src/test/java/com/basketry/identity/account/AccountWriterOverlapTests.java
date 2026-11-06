package com.basketry.identity.account;

import static com.basketry.identity.merchantstatus.StatusEvents.event;
import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.identity.merchantlink.MerchantLinkService;
import com.basketry.identity.merchantstatus.MerchantStatusHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The writers of an Account row (DESIGN.md Section 2, identity-service). Each
 * owns separate columns, and @DynamicUpdate makes each write only those, so
 * without it the later of two overlapping writers puts back what the earlier
 * one changed.
 *
 * <p>The overlap tests do not depend on lucky timing: the first writer's
 * transaction is held open while the second runs, then the first commits. If
 * the first wrote every column it would undo the second, every time. Each runs
 * 20 times regardless, to show it is not flaky.
 */
@SpringBootTest
class AccountWriterOverlapTests {

    private static final int REPEATS = 20;
    /** How long the first transaction stays open if the second does not finish. Below H2's lock timeout. */
    private static final long HOLD_MILLIS = 150;
    private static final String PASSWORD = "Corr3ct-Horse!";
    private static final String EMAIL = "shop@example.com";
    private static final long MERCHANT = 42;
    private static final Jwt ADMIN = Jwt.withTokenValue("not-checked-here")
            .header("alg", "none")
            .subject("1")
            .claim("capabilities", List.of("ADMIN"))
            .build();

    @Autowired
    private AccountController controller;

    @Autowired
    private MerchantStatusHandler statusHandler;

    @Autowired
    private MerchantLinkService linkService;

    @Autowired
    private AccountRegistrationService registrationService;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ExecutorService otherThreads;
    private long accountId;

    @BeforeEach
    void setUp() {
        accounts.deleteAll();
        accountId = registrationService.registerBuyer(EMAIL, PASSWORD).getId();
        otherThreads = Executors.newCachedThreadPool();
    }

    @AfterEach
    void stopThreads() {
        otherThreads.shutdownNow();
    }

    private void link() {
        linkService.link(EMAIL, MERCHANT, null);
    }

    private void deactivateBuyer() {
        controller.setBuyerStatus(accountId, new BuyerStatusRequest(false), ADMIN);
    }

    private void applyDeactivation() {
        statusHandler.handle(event(MERCHANT, false, 3));
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
                // The second writer is waiting for this transaction's row, as it may.
            } catch (Exception failed) {
                throw new AssertionError(failed);
            }
        });
        started.getFirst().get(5, TimeUnit.SECONDS);
    }

    private Account account() {
        return accounts.findById(accountId).orElseThrow();
    }

    private void assertBuyerAndEventBothSurvived() {
        Account after = account();
        assertThat(after.isBuyerActive()).as("buyerActive").isFalse();
        assertThat(after.isMerchantActive()).as("merchantActive").isFalse();
        assertThat(after.getMerchantStatusVersion()).as("merchantStatusVersion").isEqualTo(3);
    }

    private void assertBuyerAndLinkBothSurvived() {
        Account after = account();
        assertThat(after.isBuyerActive()).as("buyerActive").isFalse();
        assertThat(after.getMerchantId()).as("merchantId").isEqualTo(MERCHANT);
        assertThat(after.isMerchantActive()).as("merchantActive").isTrue();
    }

    @RepeatedTest(REPEATS)
    void aBuyerStatusPatchCannotUndoAnEvent() throws Exception {
        link();

        overlap(this::deactivateBuyer, () -> {
            applyDeactivation();
            return null;
        });

        assertBuyerAndEventBothSurvived();
    }

    @RepeatedTest(REPEATS)
    void anEventCannotUndoABuyerStatusPatch() throws Exception {
        link();

        overlap(this::applyDeactivation, () -> {
            deactivateBuyer();
            return null;
        });

        assertBuyerAndEventBothSurvived();
    }

    /** Regression: this race existed before the consumer. The PATCH wrote merchant_id back to NULL. */
    @RepeatedTest(REPEATS)
    void aBuyerStatusPatchCannotUndoALink() throws Exception {
        overlap(this::deactivateBuyer, () -> {
            link();
            return null;
        });

        assertBuyerAndLinkBothSurvived();
    }

    /** Regression: the link wrote buyer_active back to true, silently undoing the deactivation. */
    @RepeatedTest(REPEATS)
    void aLinkCannotUndoABuyerStatusPatch() throws Exception {
        overlap(this::link, () -> {
            deactivateBuyer();
            return null;
        });

        assertBuyerAndLinkBothSurvived();
    }
}
