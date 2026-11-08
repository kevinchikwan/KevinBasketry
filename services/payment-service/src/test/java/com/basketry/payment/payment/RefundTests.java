package com.basketry.payment.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basketry.payment.outbox.OutboxEvent;
import com.basketry.payment.payment.ChargeOutcome.Charged;
import com.basketry.payment.support.PaymentServiceTestBase;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** DESIGN.md Section 2 and Section 5, payment-service: refunds and their edge cases. */
class RefundTests extends PaymentServiceTestBase {

    @Autowired
    private PlatformTransactionManager transactionManager;

    private String approvedRef() {
        return ((Charged) payments.authorize(charge(7, 3, "25.50", APPROVED_CARD))).transactionRef();
    }

    @Test
    void anApprovedTransactionIsRefundedAndARefundedResultWritten() {
        String ref = approvedRef();

        Refunded refunded = payments.refund(new RefundCommand(7, 3, ref));

        assertThat(refunded).isEqualTo(new Refunded(7, 3, ref));
        PaymentTransaction row = onlyTransaction();
        assertThat(row.getStatus()).isEqualTo(TransactionStatus.REFUNDED);
        assertThat(row.getRefundedAt()).isNotNull();
        OutboxEvent event = outboxRows().getLast();
        assertThat(outboxRows()).hasSize(2);
        assertThat(event.getTopic()).isEqualTo("order.merchant.refunded");
        assertThat(event.getMessageKey()).isEqualTo("7");
        assertThat(payloadOf(event))
                .containsEntry("schemaVersion", 1)
                .containsEntry("eventId", event.getEventId().toString())
                .containsEntry("orderId", 7)
                .containsEntry("merchantId", 3)
                .containsKey("occurredAt")
                .hasSize(5);
    }

    @Test
    void aRepeatedRefundPublishesRefundedAgainAndChangesNothing() {
        String ref = approvedRef();
        payments.refund(new RefundCommand(7, 3, ref));
        Instant refundedAt = onlyTransaction().getRefundedAt();

        Refunded again = payments.refund(new RefundCommand(7, 3, ref));

        assertThat(again).isEqualTo(new Refunded(7, 3, ref));
        assertThat(onlyTransaction().getRefundedAt()).isEqualTo(refundedAt);
        assertThat(outboxRows()).extracting(OutboxEvent::getTopic).containsExactly(
                "order.merchant.charged", "order.merchant.refunded", "order.merchant.refunded");
    }

    /**
     * Two overlapping refunds both read APPROVED; this is the second one's
     * update. It must match nothing, so the first refund's time stays.
     */
    @Test
    void theSecondOfTwoOverlappingRefundsChangesNothing() {
        approvedRef();
        long id = onlyTransaction().getId();
        Instant first = Instant.parse("2026-11-08T10:00:00Z");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        Integer firstUpdate = transaction.execute(status -> transactions.markRefunded(id, first));
        Integer secondUpdate = transaction.execute(status -> transactions.markRefunded(id, first.plusSeconds(60)));

        assertThat(firstUpdate).isEqualTo(1);
        assertThat(secondUpdate).isZero();
        assertThat(onlyTransaction().getRefundedAt()).isEqualTo(first);
    }

    @Test
    void anUnknownReferenceIsRejectedAndNothingPublished() {
        approvedRef();
        String unknown = TransactionRefs.newRef();

        assertThatThrownBy(() -> payments.refund(new RefundCommand(7, 3, unknown)))
                .isInstanceOf(UnknownTransactionException.class)
                .isInstanceOf(PaymentRequestRejectedException.class)
                .hasMessageContaining(unknown);
        assertThat(onlyTransaction().getStatus()).isEqualTo(TransactionStatus.APPROVED);
        assertThat(outboxRows()).hasSize(1);
    }

    @Test
    void aDeclinedTransactionCannotBeRefunded() {
        payments.authorize(charge(7, 3, "25.50", DO_NOT_HONOR_CARD));
        String declinedRef = onlyTransaction().getTransactionRef();

        assertThatThrownBy(() -> payments.refund(new RefundCommand(7, 3, declinedRef)))
                .isInstanceOf(NotRefundableException.class)
                .isInstanceOf(PaymentRequestRejectedException.class)
                .hasMessageContaining("DECLINED");
        PaymentTransaction row = onlyTransaction();
        assertThat(row.getStatus()).isEqualTo(TransactionStatus.DECLINED);
        assertThat(row.getRefundedAt()).isNull();
        assertThat(outboxRows()).extracting(OutboxEvent::getTopic).containsExactly("order.merchant.declined");
    }

    @Test
    void aReferenceOfAnotherOrderOrMerchantIsRejected() {
        String ref = approvedRef();

        for (RefundCommand wrong : List.of(new RefundCommand(8, 3, ref), new RefundCommand(7, 4, ref))) {
            assertThatThrownBy(() -> payments.refund(wrong))
                    .isInstanceOf(RefundMismatchException.class)
                    .isInstanceOf(PaymentRequestRejectedException.class)
                    .hasMessageContaining("belongs to order 7 and merchant 3");
        }
        assertThat(onlyTransaction().getStatus()).isEqualTo(TransactionStatus.APPROVED);
        assertThat(outboxRows()).hasSize(1);
    }
}
