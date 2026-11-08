package com.basketry.payment.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.basketry.payment.payment.ChargeOutcome.Charged;
import com.basketry.payment.payment.RefundCommand;
import com.basketry.payment.support.PaymentServiceTestBase;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The relay's decisions, with a fake publisher so a failure happens exactly
 * where a test wants it. KafkaRelayTests covers a real broker.
 */
@TestPropertySource(properties = "basketry.outbox.relay.batch-size=3")
class OutboxRelayTests extends PaymentServiceTestBase {

    private static final String CHARGED = PaymentResultEvents.CHARGED_TOPIC;
    private static final String DECLINED = PaymentResultEvents.DECLINED_TOPIC;
    private static final String REFUNDED = PaymentResultEvents.REFUNDED_TOPIC;

    @Autowired
    private OutboxRelay relay;

    @MockitoBean
    private ResultPublisher publisher;

    @MockitoSpyBean
    private OutboxRepository spiedOutbox;

    @Test
    void nothingToPublish() {
        assertThat(relay.publishPending()).isTrue();

        verifyNoMoreInteractions(publisher);
    }

    /** Each row goes to the topic it names, keyed by orderId, with its stored bytes. */
    @Test
    void eachRowGoesToItsOwnTopicInIdOrder() {
        Charged charged = (Charged) payments.authorize(charge(7, 3, "25.50", APPROVED_CARD));
        payments.authorize(charge(8, 3, "10.00", INSUFFICIENT_FUNDS_CARD));
        payments.refund(new RefundCommand(7, 3, charged.transactionRef()));
        List<OutboxEvent> rows = outboxRows();

        assertThat(relay.publishPending()).isTrue();

        InOrder order = inOrder(publisher);
        order.verify(publisher).publish(CHARGED, "7", rows.get(0).getPayload());
        order.verify(publisher).publish(DECLINED, "8", rows.get(1).getPayload());
        order.verify(publisher).publish(REFUNDED, "7", rows.get(2).getPayload());
        assertThat(outboxRows()).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);
            assertThat(row.getSentAt()).isNotNull();
            assertThat(row.getAttempts()).isZero();
        });
    }

    @Test
    void aSentRowIsKeptAndNotPublishedAgain() {
        payments.authorize(charge(7, 3, "25.50", APPROVED_CARD));

        relay.publishPending();
        relay.publishPending();

        verify(publisher, times(1)).publish(anyString(), anyString(), anyString());
        assertThat(outboxRows()).hasSize(1);
    }

    /**
     * The first row fails, so nothing behind it goes, whatever its topic: a
     * re-emit (a new row) cannot overtake an earlier row of the same order.
     */
    @Test
    void aFailureStopsThePassAndTheNextPassKeepsTheOrder() {
        Charged charged = (Charged) payments.authorize(charge(7, 3, "25.50", APPROVED_CARD));
        payments.authorize(charge(7, 3, "25.50", APPROVED_CARD));   // the re-emit
        payments.refund(new RefundCommand(7, 3, charged.transactionRef()));
        List<OutboxEvent> rows = outboxRows();
        doThrow(new IllegalStateException("broker unreachable"))
                .doNothing()
                .when(publisher).publish(anyString(), anyString(), anyString());

        assertThat(relay.publishPending()).isFalse();

        verify(publisher, times(1)).publish(anyString(), anyString(), anyString());
        OutboxEvent failed = outboxRows().getFirst();
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getLastError()).contains("broker unreachable");
        assertThat(outboxRows()).allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING));

        assertThat(relay.publishPending()).isTrue();

        InOrder order = inOrder(publisher);
        order.verify(publisher, times(2)).publish(CHARGED, "7", rows.get(0).getPayload());
        order.verify(publisher).publish(CHARGED, "7", rows.get(1).getPayload());
        order.verify(publisher).publish(REFUNDED, "7", rows.get(2).getPayload());
        assertThat(outboxRows()).allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT));
    }

    @Test
    void aVeryLongErrorIsCutToFitTheColumn() {
        payments.authorize(charge(7, 3, "25.50", APPROVED_CARD));
        doThrow(new IllegalStateException("x".repeat(2000)))
                .when(publisher).publish(anyString(), anyString(), anyString());

        assertThat(relay.publishPending()).isFalse();

        assertThat(outboxRows().getFirst().getLastError()).hasSize(500);
    }

    /** Oldest first across passes: a batch of 3 leaves the 4th for the next pass. */
    @Test
    void aPassPublishesAtMostOneBatch() {
        for (long order = 1; order <= 4; order++) {
            payments.authorize(charge(order, 3, "1.00", APPROVED_CARD));
        }

        relay.publishPending();

        assertThat(outboxRows()).extracting(OutboxEvent::getStatus).containsExactly(
                OutboxStatus.SENT, OutboxStatus.SENT, OutboxStatus.SENT, OutboxStatus.PENDING);

        relay.publishPending();

        assertThat(outboxRows().getLast().getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    /** A crash after the send and before the mark: the row goes out again, byte for byte. */
    @Test
    void aFailedMarkResendsTheIdenticalResult() {
        payments.authorize(charge(7, 3, "25.50", APPROVED_CARD));
        doThrow(new IllegalStateException("crashed before the mark"))
                .when(spiedOutbox).markSent(anyLong(), any(Instant.class));

        assertThatThrownBy(relay::publishPending).hasMessageContaining("crashed before the mark");
        assertThat(outboxRows().getFirst().getStatus()).isEqualTo(OutboxStatus.PENDING);
        reset(spiedOutbox);

        assertThat(relay.publishPending()).isTrue();

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(publisher, times(2)).publish(anyString(), anyString(), sent.capture());
        assertThat(sent.getAllValues().get(1)).isEqualTo(sent.getAllValues().get(0));
        assertThat(outboxRows().getFirst().getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    /** Two instances publishing the same row: the second mark changes nothing, so sentAt stays. */
    @Test
    void marksAreOnlyFromPending() {
        payments.authorize(charge(7, 3, "25.50", APPROVED_CARD));
        OutboxEvent event = outboxRows().getFirst();
        Instant first = Instant.parse("2026-01-01T00:00:00Z");

        assertThat(outbox.markSent(event.getId(), first)).isEqualTo(1);
        assertThat(outbox.markSent(event.getId(), first.plusSeconds(60))).isZero();

        assertThat(outboxRows().getFirst().getSentAt()).isEqualTo(first);
    }
}
