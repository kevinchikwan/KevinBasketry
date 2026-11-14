package com.basketry.order.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.basketry.order.order.ChargeStatus;
import com.basketry.order.order.CustomerOrder;
import com.basketry.order.order.LineStatus;
import com.basketry.order.order.OrderStatus;
import com.basketry.order.outbox.OrderRequests;
import com.basketry.order.outbox.OutboxEvent;
import com.basketry.order.support.SagaTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Results by leg state and the decision (DESIGN.md Section 2, order-service).
 * The standard order: merchant 3 (product 10), merchant 4 (product 20).
 */
@ExtendWith(OutputCaptureExtension.class)
class SagaDecisionTests extends SagaTestBase {

    /**
     * Every leg positive: SUCCEEDED, decided and completed now, one
     * order.succeeded. Not before the last line. Mutations: the lines left out
     * of the success check (SUCCEEDED after the charges); order.succeeded not
     * written.
     */
    @Test
    void everyLegPositiveSucceeds() {
        long id = order();

        charged(id, MERCHANT);
        reserved(id, PRODUCT_A);
        charged(id, OTHER_MERCHANT);
        assertThat(orderOf(id).getStatus()).isEqualTo(OrderStatus.PENDING);

        reserved(id, PRODUCT_B);

        CustomerOrder order = orderOf(id);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SUCCEEDED);
        assertThat(order.getDecidedAt()).isEqualTo(T0);
        assertThat(order.getCompletedAt()).isEqualTo(T0);
        assertThat(rows(id, OrderRequests.SUCCEEDED_TOPIC)).hasSize(1);
        assertThat(chargeOf(id, MERCHANT).getTransactionRef()).isEqualTo(ref(MERCHANT));
    }

    /**
     * The first negative result decides FAILING; only the positive legs are
     * compensated (the refund by its transactionRef, the release with the line's
     * quantity, both due in 1 min); a leg still REQUESTED keeps waiting.
     * Mutations: no compensation; the REQUESTED line released too.
     */
    @Test
    void aDeclineFailsTheOrderAndCompensatesOnlyThePositiveLegs() {
        long id = order();
        charged(id, MERCHANT);
        reserved(id, PRODUCT_A);

        declined(id, OTHER_MERCHANT, "51");

        CustomerOrder order = orderOf(id);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILING);
        assertThat(order.getDecidedAt()).isEqualTo(T0);
        assertThat(order.getCompletedAt()).isNull();
        assertThat(chargeOf(id, MERCHANT).getStatus()).isEqualTo(ChargeStatus.REFUND_REQUESTED);
        assertThat(chargeOf(id, MERCHANT).getCompensationDueAt()).isEqualTo(T0.plus(RESEND));
        assertThat(chargeOf(id, OTHER_MERCHANT).getReasonCode()).isEqualTo("51");
        assertThat(lineOf(id, PRODUCT_A).getStatus()).isEqualTo(LineStatus.RELEASE_REQUESTED);
        assertThat(lineOf(id, PRODUCT_B).getStatus()).isEqualTo(LineStatus.REQUESTED);
        assertThat(refunds(id)).singleElement().extracting(this::payloadOf).satisfies(refund -> assertThat(refund)
                .containsEntry("merchantId", (int) MERCHANT).containsEntry("transactionRef", ref(MERCHANT)));
        assertThat(releases(id)).singleElement().extracting(this::payloadOf).satisfies(release -> assertThat(release)
                .containsEntry("productId", (int) PRODUCT_A).containsEntry("quantity", 1));
    }

    /** A failed reservation is negative too. Mutation: OrderLine.negative() without FAILED. */
    @Test
    void aFailedReservationFailsTheOrder() {
        long id = order();

        reservationFailed(id, PRODUCT_B, "INSUFFICIENT_STOCK");

        assertThat(orderOf(id).getStatus()).isEqualTo(OrderStatus.FAILING);
        assertThat(lineOf(id, PRODUCT_B).getReasonCode()).isEqualTo("INSUFFICIENT_STOCK");
    }

    /**
     * FAILING becomes FAILED only once every leg is settled, with one
     * order.failed naming each failed leg. Mutation: the lines left out of
     * "every leg settled" (FAILED while line 20 still waits).
     */
    @Test
    void failingBecomesFailedOnceEveryLegIsSettled() {
        long id = order();
        charged(id, MERCHANT);
        reserved(id, PRODUCT_A);
        declined(id, OTHER_MERCHANT, "51");
        clock.advance(java.time.Duration.ofSeconds(5));

        refunded(id, MERCHANT);
        released(id, PRODUCT_A);
        assertThat(orderOf(id).getStatus()).isEqualTo(OrderStatus.FAILING);

        reservationFailed(id, PRODUCT_B, "INSUFFICIENT_STOCK");

        CustomerOrder order = orderOf(id);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(order.getCompletedAt()).isEqualTo(T0.plusSeconds(5));
        List<OutboxEvent> failed = rows(id, OrderRequests.FAILED_TOPIC);
        assertThat(failed).singleElement().extracting(this::payloadOf).extracting(event -> event.get("reasons"))
                .isEqualTo(List.of(Map.of("leg", "CHARGE", "merchantId", 4, "reasonCode", "51"),
                        Map.of("leg", "RESERVE", "productId", 20, "reasonCode", "INSUFFICIENT_STOCK")));
    }

    /**
     * A positive result on a FAILING order is compensated at once. Mutations:
     * a charge on a FAILING order left CHARGED; a reservation left RESERVED.
     */
    @Test
    void aPositiveResultOnAFailingOrderIsCompensatedAtOnce() {
        long id = order();
        declined(id, MERCHANT, "05");

        charged(id, OTHER_MERCHANT);
        reserved(id, PRODUCT_B);

        assertThat(chargeOf(id, OTHER_MERCHANT).getStatus()).isEqualTo(ChargeStatus.REFUND_REQUESTED);
        assertThat(chargeOf(id, OTHER_MERCHANT).getTransactionRef()).isEqualTo(ref(OTHER_MERCHANT));
        assertThat(lineOf(id, PRODUCT_B).getStatus()).isEqualTo(LineStatus.RELEASE_REQUESTED);
        assertThat(refunds(id)).hasSize(1);
        assertThat(releases(id)).hasSize(1);
    }

    /**
     * Repeats and re-publishes are dropped: a second charged changes nothing,
     * and charged after the refund, or reserved after the release, does not
     * bring a leg back. Mutation: charged accepted from any state.
     */
    @Test
    void repeatsAndRepublishesAreDropped() {
        long id = order();
        charged(id, MERCHANT);
        charged(id, MERCHANT);
        reserved(id, PRODUCT_A);
        declined(id, OTHER_MERCHANT, "51");
        refunded(id, MERCHANT);
        released(id, PRODUCT_A);
        int rowsBefore = outboxRows().size();

        charged(id, MERCHANT);
        reserved(id, PRODUCT_A);
        refunded(id, MERCHANT);

        assertThat(chargeOf(id, MERCHANT).getStatus()).isEqualTo(ChargeStatus.REFUNDED);
        assertThat(lineOf(id, PRODUCT_A).getStatus()).isEqualTo(LineStatus.RELEASED);
        assertThat(outboxRows()).hasSize(rowsBefore);
    }

    /**
     * A confirmation of a compensation that was never requested cannot happen;
     * it is a WARN and changes nothing. Mutation: refunded accepted from
     * REQUESTED.
     */
    @Test
    void aConfirmationNeverRequestedIsAWarnAndDropped(CapturedOutput output) {
        long id = order();

        refunded(id, MERCHANT);
        released(id, PRODUCT_A);

        assertThat(chargeOf(id, MERCHANT).getStatus()).isEqualTo(ChargeStatus.REQUESTED);
        assertThat(lineOf(id, PRODUCT_A).getStatus()).isEqualTo(LineStatus.REQUESTED);
        assertThat(output).contains("whose refund was never requested").contains("whose release was never requested");
    }

    /**
     * A result for an order or a leg this service does not have (data loss) is
     * a WARN, never an exception (which would dead-letter or block it).
     * Mutation: orElseThrow for an unknown order.
     */
    @Test
    void anUnknownOrderOrLegIsAWarnAndSkipped(CapturedOutput output) {
        long id = order();

        assertThatCode(() -> charged(999_999, MERCHANT)).doesNotThrowAnyException();
        assertThatCode(() -> charged(id, 99)).doesNotThrowAnyException();
        assertThatCode(() -> reserved(id, 99)).doesNotThrowAnyException();

        assertThat(output).contains("A result for order 999999, which order-service does not have: skipped")
                .contains("names merchant 99, which has no charge leg in it")
                .contains("names product 99, which has no line in it");
        assertThat(orderOf(id).getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    /** A SUCCEEDED order never changes. Mutation: declined accepted from CHARGED. */
    @Test
    void aSucceededOrderNeverChanges() {
        long id = order();
        charged(id, MERCHANT);
        charged(id, OTHER_MERCHANT);
        reserved(id, PRODUCT_A);
        reserved(id, PRODUCT_B);

        declined(id, MERCHANT, "51");
        reservationFailed(id, PRODUCT_A, "INSUFFICIENT_STOCK");

        assertThat(orderOf(id).getStatus()).isEqualTo(OrderStatus.SUCCEEDED);
        assertThat(chargeOf(id, MERCHANT).getStatus()).isEqualTo(ChargeStatus.CHARGED);
        assertThat(rows(id, OrderRequests.REFUND_TOPIC)).isEmpty();
    }

    /**
     * Results for a leg that timed out: declined and reservation-failed are
     * dropped (the release is still answered); released settles the line.
     * Mutation: reservation-failed accepted from TIMED_OUT (the line FAILED
     * with its release still out).
     */
    @Test
    void resultsForATimedOutLeg() {
        long id = order();
        passAfter(DEADLINE);

        declined(id, MERCHANT, "51");
        reservationFailed(id, PRODUCT_A, "INSUFFICIENT_STOCK");

        assertThat(chargeOf(id, MERCHANT).getStatus()).isEqualTo(ChargeStatus.TIMED_OUT);
        assertThat(lineOf(id, PRODUCT_A).getStatus()).isEqualTo(LineStatus.TIMED_OUT);
        released(id, PRODUCT_A);
        assertThat(lineOf(id, PRODUCT_A).getStatus()).isEqualTo(LineStatus.RELEASED);
    }

    /**
     * A late charge for a timed-out leg of a FAILED order: the order goes back
     * to FAILING, the charge is refunded, and once confirmed the order is
     * FAILED again with a second order.failed (a new eventId). Mutations: no
     * reopen (the charge ignored on a FAILED order); completedAt not reset by
     * the reopen.
     */
    @Test
    void aLateChargeReopensAFailedOrder() {
        long id = order();
        passAfter(DEADLINE);
        released(id, PRODUCT_A);
        released(id, PRODUCT_B);
        assertThat(orderOf(id).getStatus()).isEqualTo(OrderStatus.FAILED);

        charged(id, MERCHANT);

        CustomerOrder reopened = orderOf(id);
        assertThat(reopened.getStatus()).isEqualTo(OrderStatus.FAILING);
        assertThat(reopened.getCompletedAt()).isNull();
        assertThat(chargeOf(id, MERCHANT).getStatus()).isEqualTo(ChargeStatus.REFUND_REQUESTED);
        assertThat(chargeOf(id, MERCHANT).getReasonCode()).isEqualTo("NO_RESULT");
        assertThat(refunds(id)).singleElement().extracting(this::payloadOf)
                .satisfies(refund -> assertThat(refund).containsEntry("transactionRef", ref(MERCHANT)));

        refunded(id, MERCHANT);

        assertThat(orderOf(id).getStatus()).isEqualTo(OrderStatus.FAILED);
        List<OutboxEvent> failed = rows(id, OrderRequests.FAILED_TOPIC);
        assertThat(failed).hasSize(2);
        assertThat(failed.get(0).getEventId()).isNotEqualTo(failed.get(1).getEventId());
    }
}
