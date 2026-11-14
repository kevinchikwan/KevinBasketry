package com.basketry.order.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.basketry.order.order.ChargeStatus;
import com.basketry.order.order.LineStatus;
import com.basketry.order.order.OrderStatus;
import com.basketry.order.outbox.OrderRequests;
import com.basketry.order.outbox.OutboxStatus;
import com.basketry.order.support.SagaTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A decision and its event commit together or not at all (DESIGN.md Section 2,
 * order-service: order.succeeded and order.failed are written in the
 * transaction that decides). Two failures are placed with a test-only CHECK
 * constraint, each removed again in {@code finally}:
 *
 * <ul>
 *   <li>the event's outbox row cannot be written: the decision rolls back with
 *       it, and the redelivered result or the next timer pass decides again,
 *       publishing exactly one event;</li>
 *   <li>the decision's own write fails after the event row was written: no
 *       event may remain for an order that was not decided.</li>
 * </ul>
 */
class DecisionAtomicityTests extends SagaTestBase {

    private void withConstraint(String table, String name, String check, Runnable inside) {
        jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + name + " CHECK (" + check + ")");
        try {
            inside.run();
        } finally {
            jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT " + name);
        }
    }

    /**
     * The last result's decision cannot write order.succeeded: nothing of that
     * result stays, and its redelivery decides with one event. Mutation:
     * order.succeeded written after the commit, in its own transaction
     * (afterCommit + REQUIRES_NEW): the decision stays without its event.
     */
    @Test
    void anEventThatCannotBeWrittenRollsTheDecisionBack() {
        long id = order();
        charged(id, MERCHANT);
        charged(id, OTHER_MERCHANT);
        reserved(id, PRODUCT_A);

        withConstraint("outbox_event", "ck_test_no_succeeded", "topic <> 'order.succeeded'",
                () -> assertThat(catchThrowable(() -> reserved(id, PRODUCT_B))).isNotNull());

        assertThat(orderOf(id).getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(orderOf(id).getDecidedAt()).isNull();
        assertThat(lineOf(id, PRODUCT_B).getStatus()).isEqualTo(LineStatus.REQUESTED);
        assertThat(rows(id, OrderRequests.SUCCEEDED_TOPIC)).isEmpty();

        reserved(id, PRODUCT_B);

        assertThat(orderOf(id).getStatus()).isEqualTo(OrderStatus.SUCCEEDED);
        assertThat(rows(id, OrderRequests.SUCCEEDED_TOPIC)).hasSize(1);
    }

    /**
     * The timer's pass cannot write order.failed: the whole step rolls back (no
     * timeouts, no cancel), and the next pass decides with one event. Mutation:
     * order.failed written after the commit, in its own transaction.
     */
    @Test
    void theTimerDecidesAgainAfterAFailedEventWrite() {
        long id = order();
        reservationFailed(id, PRODUCT_A, "INSUFFICIENT_STOCK");
        reservationFailed(id, PRODUCT_B, "INSUFFICIENT_STOCK");
        clock.advance(DEADLINE);

        withConstraint("outbox_event", "ck_test_no_failed", "topic <> 'order.failed'", () -> timer.pass());

        assertThat(orderOf(id).getStatus()).isEqualTo(OrderStatus.FAILING);
        assertThat(chargeOf(id, MERCHANT).getStatus()).isEqualTo(ChargeStatus.REQUESTED);
        assertThat(rows(id, OrderRequests.CHARGE_TOPIC)).extracting(row -> row.getStatus())
                .containsOnly(OutboxStatus.PENDING);
        assertThat(rows(id, OrderRequests.FAILED_TOPIC)).isEmpty();

        timer.pass();

        assertThat(orderOf(id).getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(rows(id, OrderRequests.FAILED_TOPIC)).hasSize(1);
        assertThat(rows(id, OrderRequests.CHARGE_TOPIC)).extracting(row -> row.getStatus())
                .containsOnly(OutboxStatus.CANCELLED);
    }

    /**
     * The decision's own write fails after order.succeeded was written: no
     * event remains. Mutation: order.succeeded written in REQUIRES_NEW (it
     * commits on its own).
     */
    @Test
    void aDecisionThatCannotBeWrittenLeavesNoSucceededEvent() {
        long id = order();
        charged(id, MERCHANT);
        charged(id, OTHER_MERCHANT);
        reserved(id, PRODUCT_A);

        withConstraint("customer_order", "ck_test_not_succeeded", "status <> 'SUCCEEDED'",
                () -> assertThat(catchThrowable(() -> reserved(id, PRODUCT_B))).isNotNull());

        assertThat(orderOf(id).getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(rows(id, OrderRequests.SUCCEEDED_TOPIC)).isEmpty();
        List<Integer> before = rowCounts();

        reserved(id, PRODUCT_B);

        assertThat(rows(id, OrderRequests.SUCCEEDED_TOPIC)).hasSize(1);
        assertThat(rowCounts().get(3)).isEqualTo(before.get(3) + 1);
    }

    /**
     * The timer's decision write fails after order.failed was written: no event
     * remains. Mutation: order.failed written in REQUIRES_NEW.
     */
    @Test
    void aDecisionThatCannotBeWrittenLeavesNoFailedEvent() {
        long id = order();
        reservationFailed(id, PRODUCT_A, "INSUFFICIENT_STOCK");
        reservationFailed(id, PRODUCT_B, "INSUFFICIENT_STOCK");
        clock.advance(DEADLINE);

        withConstraint("customer_order", "ck_test_not_failed", "status <> 'FAILED'", () -> timer.pass());

        assertThat(orderOf(id).getStatus()).isEqualTo(OrderStatus.FAILING);
        assertThat(rows(id, OrderRequests.FAILED_TOPIC)).isEmpty();

        timer.pass();

        assertThat(orderOf(id).getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(rows(id, OrderRequests.FAILED_TOPIC)).hasSize(1);
    }
}
