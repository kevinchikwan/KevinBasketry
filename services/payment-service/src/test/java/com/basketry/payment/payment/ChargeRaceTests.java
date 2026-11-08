package com.basketry.payment.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.payment.card.CardAuthorizer;
import com.basketry.payment.outbox.OutboxEvent;
import com.basketry.payment.outbox.PaymentResultOutbox;
import com.basketry.payment.payment.ChargeOutcome.Charged;
import com.basketry.payment.support.PaymentServiceTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Two first requests of one charge at the same time (DESIGN.md Section 2,
 * payment-service). Runs on H2 and, in postgresTest, on PostgreSQL, where a
 * failed insert aborts its whole transaction.
 */
class ChargeRaceTests extends PaymentServiceTestBase {

    @Autowired
    private PaymentResultOutbox resultOutbox;

    @Autowired
    private CardAuthorizer authorizer;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Deterministic: the winner's row is committed, and the loser's lookup is
     * made to miss it, so the loser's insert must hit the unique constraint.
     * The loser then answers from the winner's row in a new transaction.
     */
    @Test
    void theLoserOfARaceAnswersWithTheWinnersResult() {
        Charged winner = (Charged) payments.authorize(charge(7, 3, "25.50", APPROVED_CARD));
        PaymentService loser = new PaymentService(firstLookupMisses(), resultOutbox, authorizer, transactionManager);

        ChargeOutcome lost = loser.authorize(charge(7, 3, "25.50", APPROVED_CARD));

        assertThat(lost).isEqualTo(winner);
        assertThat(onlyTransaction().getTransactionRef()).isEqualTo(winner.transactionRef());
        // The winner's result, and the loser's re-emit of it. The loser's own
        // outbox row was never written, because its insert failed first.
        assertThat(outboxRows()).hasSize(2).allSatisfy(event -> {
            assertThat(event.getTopic()).isEqualTo("order.merchant.charged");
            assertThat(payloadOf(event)).containsEntry("transactionRef", winner.transactionRef());
        });
    }

    /** Not deterministic, so it backs up the test above rather than replacing it. */
    @Test
    void manyConcurrentFirstRequestsChargeOnce() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ChargeOutcome>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return payments.authorize(charge(7, 3, "25.50", APPROVED_CARD));
                }));
            }
            start.countDown();

            List<ChargeOutcome> outcomes = new ArrayList<>();
            for (Future<ChargeOutcome> result : results) {
                outcomes.add(result.get(30, TimeUnit.SECONDS)); // any exception fails here
            }
            String ref = onlyTransaction().getTransactionRef();
            assertThat(outcomes).containsOnly(new Charged(ref));
            assertThat(outboxRows()).hasSize(threads).extracting(OutboxEvent::getTopic)
                    .containsOnly("order.merchant.charged");
        } finally {
            pool.shutdownNow();
        }
    }
}
