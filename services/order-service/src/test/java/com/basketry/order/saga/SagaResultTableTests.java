package com.basketry.order.saga;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.order.order.ChargeStatus;
import com.basketry.order.order.CustomerOrder;
import com.basketry.order.order.LineStatus;
import com.basketry.order.order.MerchantCharge;
import com.basketry.order.order.OrderLine;
import com.basketry.order.order.OrderStatus;
import com.basketry.order.outbox.OrderRequests;
import com.basketry.order.support.SagaTestBase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The cells of DESIGN.md Section 2's results-by-leg-state table (order-service)
 * that had no test elsewhere, one dynamic test per cell. The standard order:
 * merchant 3 (product 10), merchant 4 (product 20); the result always goes to
 * merchant 3's leg or product 10's line.
 *
 * <p>A "drop" cell: nothing about the order changes. Its status, needsAttention,
 * decidedAt and completedAt, every leg's state, reason, transactionRef,
 * resends and due time, and its outbox rows (no request, no event) are the
 * same after the result as before it.
 *
 * <p>Covered elsewhere: charged on REQUESTED (PENDING and FAILING), CHARGED,
 * REFUNDED, and TIMED_OUT of a FAILED order; declined on REQUESTED, CHARGED,
 * TIMED_OUT; refunded on REQUESTED, REFUND_REQUESTED, REFUNDED, STUCK; reserved
 * on REQUESTED (PENDING and FAILING) and RELEASED; reservation-failed on
 * REQUESTED, RESERVED, TIMED_OUT; released on REQUESTED, TIMED_OUT,
 * RELEASE_REQUESTED, STUCK (SagaDecisionTests, SagaTimerTests).
 *
 * <p>Mutations, one per group of cells: charged accepted from DECLINED; a late
 * charge refunded only on a FAILED order; declined accepted from any state but
 * CHARGED; refunded accepted from any state but REQUESTED; reserved on a
 * TIMED_OUT line treated as a normal reserved (back to RESERVED, no release);
 * reservation-failed accepted from any state but RESERVED; released accepted
 * from any state but REQUESTED.
 */
@ExtendWith(OutputCaptureExtension.class)
class SagaResultTableTests extends SagaTestBase {

    private static final int STUCK_AFTER = 4;

    /** Everything about one order that a result could change. */
    private record Snapshot(OrderStatus status, boolean needsAttention, Instant decidedAt, Instant completedAt,
                            List<String> legs, List<String> outbox) {
    }

    private Snapshot snapshot(long id) {
        CustomerOrder order = orderOf(id);
        List<String> legs = new ArrayList<>();
        for (MerchantCharge charge : charges.findByOrderIdOrderByMerchantIdAsc(id)) {
            legs.add("charge " + charge.getMerchantId() + " " + charge.getStatus() + " " + charge.getReasonCode()
                    + " " + charge.getTransactionRef() + " " + charge.getResends() + " "
                    + charge.getCompensationDueAt());
        }
        for (OrderLine line : lines.findByOrderIdOrderByProductIdAsc(id)) {
            legs.add("line " + line.getProductId() + " " + line.getStatus() + " " + line.getReasonCode() + " "
                    + line.getResends() + " " + line.getCompensationDueAt());
        }
        List<String> outbox = outboxRows().stream()
                .filter(row -> row.getMessageKey().equals(Long.toString(id)))
                .map(row -> row.getEventId() + " " + row.getTopic())
                .toList();
        return new Snapshot(order.getStatus(), order.isNeedsAttention(), order.getDecidedAt(),
                order.getCompletedAt(), legs, outbox);
    }

    // ---- The leg states, reached as the saga reaches them ----

    private void chargeCharged(long id) {
        charged(id, MERCHANT);
    }

    private void chargeDeclined(long id) {
        declined(id, MERCHANT, "51");
    }

    private void chargeRefundRequested(long id) {
        charged(id, MERCHANT);
        declined(id, OTHER_MERCHANT, "51");
    }

    private void chargeRefunded(long id) {
        chargeRefundRequested(id);
        refunded(id, MERCHANT);
    }

    /** As SagaTimerTests: both lines failed, so the deadline times nothing out. */
    private void chargeStuck(long id) {
        chargeRefundRequested(id);
        reservationFailed(id, PRODUCT_A, "INSUFFICIENT_STOCK");
        reservationFailed(id, PRODUCT_B, "INSUFFICIENT_STOCK");
        for (int pass = 0; pass < STUCK_AFTER; pass++) {
            passAfter(RESEND);
        }
    }

    /** Every leg TIMED_OUT; the lines' releases are out, so the order is FAILING. */
    private void timedOut(long id) {
        passAfter(DEADLINE);
    }

    private void lineReserved(long id) {
        reserved(id, PRODUCT_A);
    }

    private void lineFailed(long id) {
        reservationFailed(id, PRODUCT_A, "INSUFFICIENT_STOCK");
    }

    private void lineReleaseRequested(long id) {
        reserved(id, PRODUCT_A);
        declined(id, MERCHANT, "51");
    }

    private void lineReleased(long id) {
        lineReleaseRequested(id);
        released(id, PRODUCT_A);
    }

    private void lineStuck(long id) {
        passAfter(DEADLINE);
        for (int pass = 0; pass < STUCK_AFTER; pass++) {
            passAfter(RESEND);
        }
    }

    // ---- The cells ----

    @TestFactory
    Stream<DynamicTest> dropCells() {
        return Stream.of(
                drop("charged on DECLINED", this::chargeDeclined, ChargeStatus.DECLINED,
                        id -> charged(id, MERCHANT)),
                drop("charged on REFUND_REQUESTED", this::chargeRefundRequested, ChargeStatus.REFUND_REQUESTED,
                        id -> charged(id, MERCHANT)),
                drop("charged on STUCK", this::chargeStuck, ChargeStatus.STUCK,
                        id -> charged(id, MERCHANT)),
                drop("declined on DECLINED", this::chargeDeclined, ChargeStatus.DECLINED,
                        id -> declined(id, MERCHANT, "05")),
                drop("declined on REFUND_REQUESTED", this::chargeRefundRequested, ChargeStatus.REFUND_REQUESTED,
                        id -> declined(id, MERCHANT, "05")),
                drop("declined on REFUNDED", this::chargeRefunded, ChargeStatus.REFUNDED,
                        id -> declined(id, MERCHANT, "05")),
                drop("declined on STUCK", this::chargeStuck, ChargeStatus.STUCK,
                        id -> declined(id, MERCHANT, "05")),
                drop("refunded on CHARGED", this::chargeCharged, ChargeStatus.CHARGED,
                        id -> refunded(id, MERCHANT)),
                drop("refunded on DECLINED", this::chargeDeclined, ChargeStatus.DECLINED,
                        id -> refunded(id, MERCHANT)),
                drop("refunded on TIMED_OUT (cannot happen: a WARN)", this::timedOut, ChargeStatus.TIMED_OUT,
                        id -> refunded(id, MERCHANT)),
                drop("reserved on RESERVED", this::lineReserved, LineStatus.RESERVED,
                        id -> reserved(id, PRODUCT_A)),
                drop("reserved on FAILED", this::lineFailed, LineStatus.FAILED,
                        id -> reserved(id, PRODUCT_A)),
                drop("reserved on TIMED_OUT (its release is already requested)", this::timedOut,
                        LineStatus.TIMED_OUT, id -> reserved(id, PRODUCT_A)),
                drop("reserved on RELEASE_REQUESTED", this::lineReleaseRequested, LineStatus.RELEASE_REQUESTED,
                        id -> reserved(id, PRODUCT_A)),
                drop("reserved on STUCK", this::lineStuck, LineStatus.STUCK,
                        id -> reserved(id, PRODUCT_A)),
                drop("reservation-failed on FAILED", this::lineFailed, LineStatus.FAILED,
                        id -> reservationFailed(id, PRODUCT_A, "UNKNOWN_PRODUCT")),
                drop("reservation-failed on RELEASE_REQUESTED", this::lineReleaseRequested,
                        LineStatus.RELEASE_REQUESTED, id -> reservationFailed(id, PRODUCT_A, "UNKNOWN_PRODUCT")),
                drop("reservation-failed on RELEASED", this::lineReleased, LineStatus.RELEASED,
                        id -> reservationFailed(id, PRODUCT_A, "UNKNOWN_PRODUCT")),
                drop("reservation-failed on STUCK", this::lineStuck, LineStatus.STUCK,
                        id -> reservationFailed(id, PRODUCT_A, "UNKNOWN_PRODUCT")),
                drop("released on RESERVED", this::lineReserved, LineStatus.RESERVED,
                        id -> released(id, PRODUCT_A)),
                drop("released on FAILED", this::lineFailed, LineStatus.FAILED,
                        id -> released(id, PRODUCT_A)),
                drop("released on RELEASED", this::lineReleased, LineStatus.RELEASED,
                        id -> released(id, PRODUCT_A)));
    }

    /**
     * The one cell of the "TIMED_OUT" column that acts and had no test: a late
     * charge while the order is still FAILING (a line's release is out). The
     * leg is refunded by the late transactionRef and keeps NO_RESULT; the order
     * stays FAILING, nothing is reopened, and no order event is written.
     */
    @TestFactory
    Stream<DynamicTest> lateChargeOnAFailingOrder() {
        return Stream.of(DynamicTest.dynamicTest("charged on TIMED_OUT, order FAILING", () -> {
            clock.set(T0);
            long id = order();
            timedOut(id);
            assertThat(chargeOf(id, MERCHANT).getStatus()).isEqualTo(ChargeStatus.TIMED_OUT);
            Snapshot before = snapshot(id);
            assertThat(before.status()).isEqualTo(OrderStatus.FAILING);
            int refundsBefore = refunds(id).size();

            charged(id, MERCHANT);

            MerchantCharge leg = chargeOf(id, MERCHANT);
            assertThat(leg.getStatus()).isEqualTo(ChargeStatus.REFUND_REQUESTED);
            assertThat(leg.getTransactionRef()).isEqualTo(ref(MERCHANT));
            assertThat(leg.getReasonCode()).isEqualTo(MerchantCharge.NO_RESULT);
            assertThat(leg.getCompensationDueAt()).isEqualTo(clock.instant().plus(RESEND));
            CustomerOrder order = orderOf(id);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILING);
            assertThat(order.getDecidedAt()).isEqualTo(before.decidedAt());
            assertThat(order.getCompletedAt()).isNull();
            assertThat(refunds(id)).hasSize(refundsBefore + 1);
            assertThat(payloadOf(refunds(id).getLast())).containsEntry("transactionRef", ref(MERCHANT))
                    .containsEntry("merchantId", (int) MERCHANT);
            assertThat(rows(id, OrderRequests.FAILED_TOPIC)).isEmpty();
            assertThat(rows(id, OrderRequests.SUCCEEDED_TOPIC)).isEmpty();
            assertThat(chargeOf(id, OTHER_MERCHANT).getStatus()).isEqualTo(ChargeStatus.TIMED_OUT);
            assertThat(lineOf(id, PRODUCT_A).getStatus()).isEqualTo(LineStatus.TIMED_OUT);
        }));
    }

    /** The cell's WARN: a refund confirmation for a leg that never had one requested. */
    @TestFactory
    Stream<DynamicTest> aRefundedOnATimedOutLegIsAWarn(CapturedOutput output) {
        return Stream.of(DynamicTest.dynamicTest("refunded on TIMED_OUT logs a WARN", () -> {
            clock.set(T0);
            long id = order();
            timedOut(id);

            refunded(id, MERCHANT);

            assertThat(output).contains("A refunded result for order " + id + ", merchant " + MERCHANT
                    + ", whose refund was never requested: dropped");
        }));
    }

    /**
     * One drop cell: the leg is first brought to {@code state} (checked), then
     * the result changes nothing about the order.
     */
    private DynamicTest drop(String name, LongConsumer setUp, Enum<?> state, LongConsumer result) {
        return DynamicTest.dynamicTest(name, () -> {
            clock.set(T0);
            long id = order();
            setUp.accept(id);
            Enum<?> reached = state instanceof ChargeStatus
                    ? chargeOf(id, MERCHANT).getStatus() : lineOf(id, PRODUCT_A).getStatus();
            assertThat(reached).as("the leg's state before the result").isEqualTo(state);
            Snapshot before = snapshot(id);

            result.accept(id);

            assertThat(snapshot(id)).as(name + ": nothing changes").isEqualTo(before);
        });
    }
}
