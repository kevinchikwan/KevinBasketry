package com.basketry.payment.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.payment.outbox.OutboxEvent;
import com.basketry.payment.outbox.OutboxStatus;
import com.basketry.payment.payment.ChargeOutcome.Charged;
import com.basketry.payment.payment.ChargeOutcome.Declined;
import com.basketry.payment.support.PaymentServiceTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class AuthorizeTests extends PaymentServiceTestBase {

    @Test
    void anApprovedCardStoresAnApprovedRowAndAChargedResult() {
        ChargeOutcome outcome = payments.authorize(charge(7, 3, "25.5", APPROVED_CARD));

        PaymentTransaction row = onlyTransaction();
        assertThat(outcome).isEqualTo(new Charged(row.getTransactionRef()));
        assertThat(TransactionRefs.isWellFormed(row.getTransactionRef())).isTrue();
        assertThat(row.getStatus()).isEqualTo(TransactionStatus.APPROVED);
        assertThat(row.getResponseCode()).isEqualTo("00");
        assertThat(row.getAmount()).isEqualByComparingTo("25.50");
        assertThat(row.getCardLastFour()).isEqualTo("0002");
        assertThat(row.getOrderId()).isEqualTo(7);
        assertThat(row.getMerchantId()).isEqualTo(3);
        assertThat(row.getRefundedAt()).isNull();

        OutboxEvent event = outboxRows().getFirst();
        assertThat(outboxRows()).hasSize(1);
        assertThat(event.getTopic()).isEqualTo("order.merchant.charged");
        assertThat(event.getMessageKey()).isEqualTo("7");
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(payloadOf(event))
                .containsEntry("schemaVersion", 1)
                .containsEntry("eventId", event.getEventId().toString())
                .containsEntry("orderId", 7)
                .containsEntry("merchantId", 3)
                .containsEntry("transactionRef", row.getTransactionRef())
                .containsKey("occurredAt")
                .hasSize(6);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"4000000000000010, 51", "4000000000000028, 05", "4000000000000036, 54"})
    void aDeclinedCardStoresADeclinedRowAndADeclinedResult(String card, String code) {
        ChargeOutcome outcome = payments.authorize(charge(7, 3, "25.50", card));

        assertThat(outcome).isEqualTo(new Declined(code));
        PaymentTransaction row = onlyTransaction();
        assertThat(row.getStatus()).isEqualTo(TransactionStatus.DECLINED);
        assertThat(row.getResponseCode()).isEqualTo(code);
        assertThat(TransactionRefs.isWellFormed(row.getTransactionRef())).isTrue();

        OutboxEvent event = outboxRows().getFirst();
        assertThat(outboxRows()).hasSize(1);
        assertThat(event.getTopic()).isEqualTo("order.merchant.declined");
        assertThat(event.getMessageKey()).isEqualTo("7");
        // No transactionRef: order-service must never get a reference it could refund.
        assertThat(payloadOf(event))
                .containsEntry("orderId", 7)
                .containsEntry("merchantId", 3)
                .containsEntry("reasonCode", code)
                .doesNotContainKey("transactionRef")
                .hasSize(6);
    }

    @Test
    void aRepeatedChargeIsNotChargedAgainButItsResultIsPublishedAgain() {
        ChargeOutcome first = payments.authorize(charge(7, 3, "25.50", APPROVED_CARD));
        ChargeOutcome second = payments.authorize(charge(7, 3, "25.50", APPROVED_CARD));

        assertThat(second).isEqualTo(first);
        String ref = onlyTransaction().getTransactionRef();
        List<OutboxEvent> events = outboxRows();
        assertThat(events).hasSize(2).allSatisfy(event -> {
            assertThat(event.getTopic()).isEqualTo("order.merchant.charged");
            assertThat(payloadOf(event)).containsEntry("transactionRef", ref);
        });
        assertThat(events.get(0).getEventId()).isNotEqualTo(events.get(1).getEventId());
    }

    @Test
    void aRepeatedDeclineIsAnsweredWithTheStoredCode() {
        payments.authorize(charge(7, 3, "25.50", INSUFFICIENT_FUNDS_CARD));
        ChargeOutcome second = payments.authorize(charge(7, 3, "25.50", INSUFFICIENT_FUNDS_CARD));

        assertThat(second).isEqualTo(new Declined("51"));
        assertThat(onlyTransaction().getStatus()).isEqualTo(TransactionStatus.DECLINED);
        assertThat(outboxRows()).hasSize(2)
                .allSatisfy(event -> assertThat(payloadOf(event)).containsEntry("reasonCode", "51"));
    }

    /** A charge request is answered as a charge, even once the charge was refunded. */
    @Test
    void aRepeatedChargeAfterItsRefundIsStillAnsweredAsCharged() {
        Charged first = (Charged) payments.authorize(charge(7, 3, "25.50", APPROVED_CARD));
        payments.refund(new RefundCommand(7, 3, first.transactionRef()));

        ChargeOutcome again = payments.authorize(charge(7, 3, "25.50", APPROVED_CARD));

        assertThat(again).isEqualTo(first);
        assertThat(onlyTransaction().getStatus()).isEqualTo(TransactionStatus.REFUNDED);
        OutboxEvent last = outboxRows().getLast();
        assertThat(last.getTopic()).isEqualTo("order.merchant.charged");
        assertThat(payloadOf(last)).containsEntry("transactionRef", first.transactionRef());
    }

    /**
     * Same orderId and merchantId, different amount and card: the first result
     * stands, a WARN is logged, nothing is authorized again. The second card is
     * a declining one, so a fresh authorization would show as a decline.
     */
    @Test
    void aRepeatedChargeWithADifferentPayloadKeepsTheFirstResultAndWarns(CapturedOutput output) {
        Charged first = (Charged) payments.authorize(charge(7, 3, "25.50", APPROVED_CARD));

        ChargeOutcome second = payments.authorize(charge(7, 3, "99.99", INSUFFICIENT_FUNDS_CARD));

        assertThat(second).isEqualTo(first);
        PaymentTransaction row = onlyTransaction();
        assertThat(row.getAmount()).isEqualByComparingTo("25.50");
        assertThat(row.getCardLastFour()).isEqualTo("0002");
        assertThat(row.getResponseCode()).isEqualTo("00");
        assertThat(outboxRows()).hasSize(2);
        assertThat(payloadOf(outboxRows().getLast())).containsEntry("transactionRef", first.transactionRef());
        // The line itself must be a WARN: other lines of the output can contain "WARN" too.
        assertThat(output.getAll().lines()
                .filter(line -> line.contains("Repeated charge for order 7, merchant 3 differs from the first")))
                .singleElement().asString()
                .contains(" WARN ")
                .contains("amount 99.99 (first 25.50)").contains("card ending 0010 (first 0002)");
        assertThat(output).doesNotContain(INSUFFICIENT_FUNDS_CARD).doesNotContain(APPROVED_CARD);
    }

    @Test
    void anIdenticalRepeatIsNotWarnedAbout(CapturedOutput output) {
        payments.authorize(charge(7, 3, "25.50", APPROVED_CARD));
        payments.authorize(charge(7, 3, "25.5", APPROVED_CARD));   // the same amount

        assertThat(output).doesNotContain("differs from the first");
    }

    /** The key is the pair, not orderId alone: one order charges each of its merchants once. */
    @Test
    void otherMerchantsOfTheOrderAndOtherOrdersAreChargedSeparately() {
        Charged a = (Charged) payments.authorize(charge(7, 3, "10.00", APPROVED_CARD));
        Charged b = (Charged) payments.authorize(charge(7, 4, "20.00", APPROVED_CARD));
        Charged c = (Charged) payments.authorize(charge(8, 3, "30.00", APPROVED_CARD));

        assertThat(List.of(a.transactionRef(), b.transactionRef(), c.transactionRef())).doesNotHaveDuplicates();
        assertThat(transactions.findAll()).hasSize(3);
        assertThat(outboxRows()).extracting(OutboxEvent::getMessageKey).containsExactly("7", "7", "8");
        List<Object> merchantIds = outboxRows().stream()
                .map(event -> (Object) payloadOf(event).get("merchantId")).toList();
        assertThat(merchantIds).containsExactly(3, 4, 3);
    }
}
