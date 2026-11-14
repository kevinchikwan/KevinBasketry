package com.basketry.order.saga;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.order.order.ChargeStatus;
import com.basketry.order.order.LineStatus;
import com.basketry.order.order.OrderStatus;
import com.basketry.order.outbox.OrderRequests;
import com.basketry.order.outbox.OutboxEvent;
import com.basketry.order.outbox.OutboxStatus;
import com.basketry.order.support.SagaTestBase;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The timer (DESIGN.md Section 2, order-service, the timer), pass by pass. */
class SagaTimerTests extends SagaTestBase {

    /**
     * Nothing happens before the deadline; at the deadline the timer acts.
     * Mutation: the deadline check in the step inverted.
     */
    @Test
    void nothingHappensBeforeTheDeadline() {
        long id = order();

        assertThat(passAfter(DEADLINE.minusSeconds(1))).isZero();
        assertThat(orderOf(id).getStatus()).isEqualTo(OrderStatus.PENDING);

        assertThat(passAfter(Duration.ofSeconds(1))).isEqualTo(1);
        assertThat(orderOf(id).getStatus()).isEqualTo(OrderStatus.FAILING);
    }

    /**
     * Past the deadline: the legs without a result time out (NO_RESULT), the
     * line without one gets its release at once although its reserve was never
     * sent, its unsent charge and reserve are CANCELLED with the card number
     * removed, and the positive legs are compensated. Mutations: the cancel
     * skipped; releases only for RESERVED lines (none for a timed-out one).
     */
    @Test
    void pastTheDeadlineEveryLegWithoutAResultTimesOut() {
        long id = order();
        // The relay sent merchant 3's charge and product 10's reserve; merchant 4's and product 20's did not go.
        jdbc.update("UPDATE outbox_event SET status = 'SENT', sent_at = CURRENT_TIMESTAMP"
                + " WHERE payload LIKE '%\"merchantId\":3,%' OR payload LIKE '%\"productId\":10,%'");
        charged(id, MERCHANT);
        reserved(id, PRODUCT_A);

        passAfter(DEADLINE);

        assertThat(orderOf(id).getStatus()).isEqualTo(OrderStatus.FAILING);
        assertThat(chargeOf(id, OTHER_MERCHANT).getStatus()).isEqualTo(ChargeStatus.TIMED_OUT);
        assertThat(chargeOf(id, OTHER_MERCHANT).getReasonCode()).isEqualTo("NO_RESULT");
        assertThat(lineOf(id, PRODUCT_B).getStatus()).isEqualTo(LineStatus.TIMED_OUT);
        assertThat(lineOf(id, PRODUCT_B).getCompensationDueAt()).isEqualTo(T0.plus(DEADLINE).plus(RESEND));
        assertThat(chargeOf(id, MERCHANT).getStatus()).isEqualTo(ChargeStatus.REFUND_REQUESTED);
        assertThat(lineOf(id, PRODUCT_A).getStatus()).isEqualTo(LineStatus.RELEASE_REQUESTED);
        assertThat(releases(id)).extracting(this::payloadOf).extracting(release -> release.get("productId"))
                .containsExactlyInAnyOrder(10, 20);
        assertThat(rows(id, OrderRequests.CHARGE_TOPIC)).extracting(OutboxEvent::getStatus)
                .containsExactly(OutboxStatus.SENT, OutboxStatus.CANCELLED);
        assertThat(rows(id, OrderRequests.CHARGE_TOPIC).get(1).getPayload()).doesNotContain(CARD);
        assertThat(rows(id, OrderRequests.RESERVE_TOPIC)).extracting(OutboxEvent::getStatus)
                .containsExactly(OutboxStatus.SENT, OutboxStatus.CANCELLED);
    }

    /**
     * An unconfirmed refund is sent again 3 times, 1 min apart, the same
     * request each time; 1 min after the third the leg is STUCK, the order needs
     * attention and stays FAILING, and nothing more is sent. Mutations:
     * {@code <=} for {@code <} on maxResends (a fourth resend); the STUCK
     * charge leg not counted for needsAttention.
     */
    @Test
    void anUnconfirmedRefundIsResentThreeTimesThenStuck() {
        long id = order();
        charged(id, MERCHANT);
        declined(id, OTHER_MERCHANT, "51");
        reservationFailed(id, PRODUCT_A, "INSUFFICIENT_STOCK");
        reservationFailed(id, PRODUCT_B, "INSUFFICIENT_STOCK");
        assertThat(refunds(id)).hasSize(1);

        for (int resend = 1; resend <= 3; resend++) {
            passAfter(RESEND);
            assertThat(refunds(id)).as("after resend " + resend).hasSize(1 + resend);
            assertThat(chargeOf(id, MERCHANT).getResends()).isEqualTo(resend);
        }
        assertThat(refunds(id)).extracting(this::payloadOf).extracting(refund -> refund.get("transactionRef"))
                .containsOnly(ref(MERCHANT));

        passAfter(RESEND);

        assertThat(chargeOf(id, MERCHANT).getStatus()).isEqualTo(ChargeStatus.STUCK);
        assertThat(orderOf(id).isNeedsAttention()).isTrue();
        assertThat(orderOf(id).getStatus()).isEqualTo(OrderStatus.FAILING);
        assertThat(refunds(id)).hasSize(4);

        passAfter(RESEND.multipliedBy(5));
        assertThat(refunds(id)).hasSize(4);
    }

    /**
     * A TIMED_OUT line's release gets exactly the same resends as a
     * RELEASE_REQUESTED line's, then STUCK. Mutation: TIMED_OUT left out of the
     * timer's release loop.
     */
    @Test
    void aTimedOutLinesReleaseIsResentThreeTimesThenStuck() {
        long id = order();
        passAfter(DEADLINE);
        assertThat(lineOf(id, PRODUCT_A).getStatus()).isEqualTo(LineStatus.TIMED_OUT);
        int firstReleases = releases(id).size();

        for (int resend = 1; resend <= 3; resend++) {
            passAfter(RESEND);
            assertThat(lineOf(id, PRODUCT_A).getResends()).isEqualTo(resend);
        }
        assertThat(releases(id)).hasSize(firstReleases + 6);

        passAfter(RESEND);

        assertThat(lineOf(id, PRODUCT_A).getStatus()).isEqualTo(LineStatus.STUCK);
        assertThat(lineOf(id, PRODUCT_B).getStatus()).isEqualTo(LineStatus.STUCK);
        assertThat(orderOf(id).isNeedsAttention()).isTrue();
    }

    /**
     * A late confirmation still settles a STUCK leg; needsAttention clears only
     * once no leg is STUCK, and the order then completes. Mutations: released
     * not accepted from STUCK; needsAttention counting only charge legs.
     */
    @Test
    void aLateConfirmationSettlesAStuckLeg() {
        long id = order();
        charged(id, MERCHANT);
        passAfter(DEADLINE);
        for (int pass = 0; pass < 4; pass++) {
            passAfter(RESEND);
        }
        assertThat(chargeOf(id, MERCHANT).getStatus()).isEqualTo(ChargeStatus.STUCK);
        assertThat(lineOf(id, PRODUCT_A).getStatus()).isEqualTo(LineStatus.STUCK);

        refunded(id, MERCHANT);
        assertThat(chargeOf(id, MERCHANT).getStatus()).isEqualTo(ChargeStatus.REFUNDED);
        assertThat(orderOf(id).isNeedsAttention()).as("lines still STUCK").isTrue();

        released(id, PRODUCT_A);
        released(id, PRODUCT_B);

        assertThat(orderOf(id).isNeedsAttention()).isFalse();
        assertThat(orderOf(id).getStatus()).isEqualTo(OrderStatus.FAILED);
    }

    /**
     * A confirmed compensation is not resent. Mutation: the refunded result
     * accepted without moving the leg to REFUNDED (it stays due and is resent).
     */
    @Test
    void aConfirmedCompensationIsNotResent() {
        long id = order();
        charged(id, MERCHANT);
        declined(id, OTHER_MERCHANT, "51");
        refunded(id, MERCHANT);

        passAfter(RESEND.multipliedBy(2));

        assertThat(refunds(id)).hasSize(1);
        assertThat(chargeOf(id, MERCHANT).getResends()).isZero();
    }
}
