package com.basketry.identity.merchantstatus;

import static com.basketry.identity.merchantstatus.StatusEvents.event;
import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.identity.account.Account;
import com.basketry.identity.account.AccountRegistrationService;
import com.basketry.identity.account.AccountRepository;
import com.basketry.identity.merchantlink.MerchantLinkService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two events of one merchant applied at the same moment (DESIGN.md Section 5).
 * The conditional UPDATE decides alone: the waiting one is checked again
 * against the row as the other one committed it, so the final state is always
 * the highest version's, in either order.
 *
 * <p>The first two tests do not depend on lucky timing: the first event's
 * transaction holds the row while the second event runs. The last one races
 * freely. Each runs 20 times.
 */
@SpringBootTest
class MerchantStatusRaceTests {

    private static final int REPEATS = 20;
    /**
     * How long the first transaction stays open if the second does not finish.
     * Below H2's lock timeout; Postgres has none here, so the second simply waits.
     */
    private static final long HOLD_MILLIS = 150;
    private static final long MERCHANT = 42;

    @Autowired
    private MerchantStatusHandler handler;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AccountRegistrationService registrationService;

    @Autowired
    private MerchantLinkService linkService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ExecutorService otherThreads;
    private long accountId;

    @BeforeEach
    void linkAnAccount() {
        accounts.deleteAll();
        accountId = registrationService.registerBuyer("shop@example.com", "Corr3ct-Horse!").getId();
        linkService.link("shop@example.com", MERCHANT, null);
        otherThreads = Executors.newCachedThreadPool();
    }

    @AfterEach
    void stopThreads() {
        otherThreads.shutdownNow();
    }

    /** Version 2 is active, versions 1 and 3 are not: each version flips the previous one. */
    private void apply(long version) {
        handler.handle(event(MERCHANT, version % 2 == 0, version));
    }

    /** Applies {@code first} in a transaction that stays open while {@code second} is applied on another thread. */
    private void overlap(long first, long second) throws Exception {
        List<Future<?>> started = new ArrayList<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            apply(first);
            Future<?> other = otherThreads.submit(() -> apply(second));
            started.add(other);
            try {
                other.get(HOLD_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException waiting) {
                // The second event is waiting for this transaction's row, as it should.
            } catch (Exception failed) {
                throw new AssertionError(failed);
            }
        });
        started.getFirst().get(5, TimeUnit.SECONDS);
    }

    private void assertState(boolean merchantActive, long version) {
        Account after = accounts.findById(accountId).orElseThrow();
        assertThat(after.isMerchantActive()).as("merchantActive").isEqualTo(merchantActive);
        assertThat(after.getMerchantStatusVersion()).as("merchantStatusVersion").isEqualTo(version);
    }

    @RepeatedTest(REPEATS)
    void anOlderEventWaitingForANewerOneChangesNothing() throws Exception {
        overlap(3, 2);

        assertState(false, 3);
    }

    @RepeatedTest(REPEATS)
    void aNewerEventWaitingForAnOlderOneIsStillApplied() throws Exception {
        overlap(2, 3);

        assertState(false, 3);
    }

    @RepeatedTest(REPEATS)
    void racingEventsEndAtTheHighestVersion() throws Exception {
        List<Long> versions = new ArrayList<>(LongStream.rangeClosed(1, 8).boxed().toList());
        Collections.shuffle(versions);
        CyclicBarrier start = new CyclicBarrier(versions.size());
        List<Future<?>> running = new ArrayList<>();
        for (long version : versions) {
            running.add(otherThreads.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                apply(version);
                return null;
            }));
        }
        for (Future<?> thread : running) {
            thread.get(30, TimeUnit.SECONDS);
        }

        assertState(true, 8);
    }
}
