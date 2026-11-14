package com.basketry.order.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.basketry.order.order.ChargeStatus;
import com.basketry.order.order.OrderStatus;
import com.basketry.order.outbox.OutboxEvent;
import com.basketry.order.support.SagaTestBase;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The timer and a late result for the same order at the same moment (DESIGN.md
 * Section 2, order-service, the timer: "Two instances are safe: the order's row
 * lock decides who acts"). The order is past its deadline with merchant 3's
 * charge still REQUESTED; the timer pass and merchant 3's charged result run on
 * two threads, each held just before its decision until the other arrives or
 * 500 ms pass.
 *
 * <p>With the row lock, whichever comes second waits and sees the other's
 * committed work, and both orders end the same way: the charge is refunded (a
 * charged leg compensated by the timer's decision, or a timed-out leg's late
 * charge), exactly one refund is requested, and the order is FAILING, waiting
 * for the refund and the releases. Without the timer's lock, both change
 * merchant 3's leg from the same stale state; the later commit overwrites the
 * earlier (Hibernate writes every column), and a charge is lost: CHARGED on a
 * failing order with no refund, or TIMED_OUT with its transactionRef gone. Runs
 * on H2 and, in postgresTest, on PostgreSQL.
 */
class SagaTimerRaceTests extends SagaTestBase {

    @MockitoSpyBean
    private SagaDecision decision;

    private ExecutorService threads;
    private CyclicBarrier bothAtTheDecision;

    @BeforeEach
    void holdEachDecisionUntilTheOtherArrives() {
        threads = Executors.newFixedThreadPool(2);
        bothAtTheDecision = new CyclicBarrier(2);
        // decide() requires a transaction (MANDATORY), and stubbing calls it once.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> doAnswer(invocation -> {
            try {
                bothAtTheDecision.await(500, TimeUnit.MILLISECONDS);
            } catch (TimeoutException | BrokenBarrierException alone) {
                // The other one is waiting at the order's lock: the right case.
            }
            return invocation.callRealMethod();
        }).when(decision).decide(any(), any(), any()));
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    /** Mutation: the timer reads the order with a plain findById instead of the row lock. */
    @RepeatedTest(5)
    void aLateChargeRacingTheTimerIsRefundedAndNothingIsLost() throws Exception {
        long id = order();
        checkoutRequestsSent();
        clock.advance(DEADLINE);

        List<Future<?>> both = List.of(
                threads.submit(() -> timer.pass()),
                threads.submit(() -> charged(id, MERCHANT)));
        for (Future<?> one : both) {
            one.get(30, TimeUnit.SECONDS);
        }

        assertThat(chargeOf(id, MERCHANT).getStatus()).isEqualTo(ChargeStatus.REFUND_REQUESTED);
        assertThat(chargeOf(id, MERCHANT).getTransactionRef()).isEqualTo(ref(MERCHANT));
        assertThat(refunds(id)).extracting(this::payloadOf).extracting(refund -> refund.get("transactionRef"))
                .containsExactly(ref(MERCHANT));
        assertThat(chargeOf(id, OTHER_MERCHANT).getStatus()).isEqualTo(ChargeStatus.TIMED_OUT);
        assertThat(releases(id)).extracting(OutboxEvent::getMessageKey).hasSize(2);
        assertThat(orderOf(id).getStatus()).isEqualTo(OrderStatus.FAILING);
    }
}
