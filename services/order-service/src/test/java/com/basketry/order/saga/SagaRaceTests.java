package com.basketry.order.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.basketry.order.order.OrderStatus;
import com.basketry.order.outbox.OrderRequests;
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
 * Two results of one order, applied at the same moment on two threads, as two
 * result listeners would (DESIGN.md Section 2, order-service, who decides). Each
 * transaction is held just before its decision until the other has reached the
 * same point, or 500 ms have passed. With the order's row lock the second waits
 * at the lock, so it reaches its decision only after the first committed, sees
 * both legs positive, and decides; the barrier simply times out. Without the
 * lock both reach the barrier, each sees the other's leg still REQUESTED (under
 * READ COMMITTED an uncommitted change is invisible), neither decides, and the
 * order stays PENDING. Runs on H2 and, in postgresTest, on PostgreSQL.
 */
class SagaRaceTests extends SagaTestBase {

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
                // The other result is waiting at the order's lock: the right case.
            }
            return invocation.callRealMethod();
        }).when(decision).decide(any(), any(), any()));
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    /** An order of one merchant and one line: the charge and the reservation are its only results. */
    private long smallOrder() {
        givenBuyer();
        givenProduct(PRODUCT_A, MERCHANT, "2.50");
        givenMerchant(MERCHANT, "5.00");
        return checkout(command("race-" + System.nanoTime(), "2.50", PRODUCT_A, 1)).order().getId();
    }

    /** Mutation: findByIdForUpdate replaced by a plain findById (no row lock). */
    @RepeatedTest(5)
    void twoResultsAtOnceStillDecide() throws Exception {
        long id = smallOrder();

        List<Future<?>> both = List.of(
                threads.submit(() -> charged(id, MERCHANT)),
                threads.submit(() -> reserved(id, PRODUCT_A)));
        for (Future<?> result : both) {
            result.get(30, TimeUnit.SECONDS);
        }

        assertThat(orderOf(id).getStatus()).isEqualTo(OrderStatus.SUCCEEDED);
        assertThat(rows(id, OrderRequests.SUCCEEDED_TOPIC)).hasSize(1);
    }
}
