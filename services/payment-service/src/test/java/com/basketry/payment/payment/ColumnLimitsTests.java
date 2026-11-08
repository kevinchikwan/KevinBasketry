package com.basketry.payment.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.payment.outbox.OutboxEvent;
import com.basketry.payment.payment.ChargeOutcome.Charged;
import com.basketry.payment.support.PaymentServiceTestBase;
import org.junit.jupiter.api.Test;

/**
 * The largest request validation lets through fits every column. A database
 * error is retried forever by the listener (DESIGN.md Section 5), so a valid
 * request that did not fit would block its partition for good. Runs on H2 and,
 * in postgresTest, on PostgreSQL.
 */
class ColumnLimitsTests extends PaymentServiceTestBase {

    @Test
    void theLargestValidChargeAndItsRefundAreStored() {
        long id = Long.MAX_VALUE;

        Charged charged = (Charged) payments.authorize(charge(id, id, "9999999999.99", APPROVED_CARD));
        payments.refund(new RefundCommand(id, id, charged.transactionRef()));

        assertThat(onlyTransaction().getAmount()).isEqualByComparingTo(ChargeCommand.MAX_AMOUNT);
        assertThat(outboxRows()).hasSize(2).extracting(OutboxEvent::getMessageKey)
                .containsOnly(Long.toString(id));
    }
}
