package com.basketry.order.outbox;

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

import com.basketry.order.support.OrderServiceTestBase;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The relay and the cancel (DESIGN.md Section 5, order-service requests), with
 * a fake publisher so a failure happens exactly where a test wants it.
 * KafkaRelayTests covers a real broker. The rows come from real checkouts: one
 * order of one merchant and one line has a charge and a reserve.
 */
@TestPropertySource(properties = "basketry.outbox.relay.batch-size=3")
class OutboxRelayTests extends OrderServiceTestBase {

    private static final String CHARGE = OrderRequests.CHARGE_TOPIC;
    private static final String RESERVE = OrderRequests.RESERVE_TOPIC;
    private static final String SCRUBBED = "\"cardNumber\":null";

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OrderOutbox orderOutbox;

    @MockitoBean
    private RequestPublisher publisher;

    @MockitoSpyBean
    private OutboxRepository spiedOutbox;

    @BeforeEach
    void givenAProduct() {
        givenBuyer();
        givenProduct(10, MERCHANT, "2.50");
        givenProduct(11, MERCHANT, "4.00");
        givenMerchant(MERCHANT, "5.00");
    }

    /** One order: a charge row, then a reserve row. */
    private long order(String key) {
        return checkout(command(key, "2.50", 10, 1)).order().getId();
    }

    private int cancel(long orderId) {
        return new TransactionTemplate(transactionManager).execute(status -> orderOutbox.cancelUnsentRequests(orderId));
    }

    @Test
    void nothingToPublish() {
        assertThat(relay.publishPending()).isTrue();

        verifyNoMoreInteractions(publisher);
    }

    /**
     * Each row goes to the topic it names, keyed by orderId, in id order, with
     * its stored bytes: the charge with its card number, which payment-service
     * needs. Mutation: the payload scrubbed before it is published.
     */
    @Test
    void eachRowGoesToItsTopicInIdOrderWithTheCardNumber() {
        long orderId = order("key-0001");
        List<OutboxEvent> rows = outboxRows();

        assertThat(relay.publishPending()).isTrue();

        InOrder order = inOrder(publisher);
        order.verify(publisher).publish(CHARGE, Long.toString(orderId), rows.get(0).getPayload());
        order.verify(publisher).publish(RESERVE, Long.toString(orderId), rows.get(1).getPayload());
        assertThat(rows.get(0).getPayload()).contains("\"cardNumber\":\"" + CARD + "\"");
        assertThat(outboxRows()).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);
            assertThat(row.getSentAt()).isNotNull();
        });
    }

    /**
     * Once SENT, a charge row keeps no card number: the same payload with
     * cardNumber null, every other field as it was; a reserve row is unchanged.
     * Mutation: markSent given the original payload.
     */
    @Test
    void aSentChargeRowKeepsNoCardNumber() {
        order("key-0001");
        List<OutboxEvent> before = outboxRows();

        relay.publishPending();

        List<OutboxEvent> after = outboxRows();
        assertThat(after.get(0).getPayload()).isEqualTo(before.get(0).getPayload()
                .replace("\"cardNumber\":\"" + CARD + "\"", SCRUBBED));
        assertThat(after.get(1).getPayload()).isEqualTo(before.get(1).getPayload());
    }

    /**
     * A failure stops the pass, the row stays PENDING with its card number for
     * the retry, and the next pass keeps the order. Mutation: continue past a
     * failure.
     */
    @Test
    void aFailureStopsThePassAndTheRowKeepsItsCardNumberForTheRetry() {
        order("key-0001");
        List<OutboxEvent> rows = outboxRows();
        doThrow(new IllegalStateException("broker unreachable")).doNothing()
                .when(publisher).publish(anyString(), anyString(), anyString());

        assertThat(relay.publishPending()).isFalse();

        verify(publisher, times(1)).publish(anyString(), anyString(), anyString());
        OutboxEvent failed = outboxRows().getFirst();
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getLastError()).contains("broker unreachable");
        assertThat(failed.getPayload()).isEqualTo(rows.get(0).getPayload());
        assertThat(outboxRows().get(1).getStatus()).isEqualTo(OutboxStatus.PENDING);

        assertThat(relay.publishPending()).isTrue();

        InOrder order = inOrder(publisher);
        order.verify(publisher, times(2)).publish(CHARGE, rows.get(0).getMessageKey(), rows.get(0).getPayload());
        order.verify(publisher).publish(RESERVE, rows.get(1).getMessageKey(), rows.get(1).getPayload());
    }

    /** Oldest first across passes: a batch of 3 leaves the 4th for the next pass. Mutation: no Limit. */
    @Test
    void aPassPublishesAtMostOneBatch() {
        order("key-0001");
        order("key-0002");

        relay.publishPending();

        assertThat(outboxRows()).extracting(OutboxEvent::getStatus).containsExactly(
                OutboxStatus.SENT, OutboxStatus.SENT, OutboxStatus.SENT, OutboxStatus.PENDING);
    }

    /** A crash after the send and before the mark: the row goes out again, byte for byte. */
    @Test
    void aFailedMarkResendsTheIdenticalBytes() {
        order("key-0001");
        doThrow(new IllegalStateException("crashed before the mark"))
                .when(spiedOutbox).markSent(anyLong(), any(Instant.class), anyString());

        assertThatThrownBy(relay::publishPending).hasMessageContaining("crashed before the mark");
        reset(spiedOutbox);

        assertThat(relay.publishPending()).isTrue();

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(publisher, times(3)).publish(anyString(), anyString(), sent.capture());
        assertThat(sent.getAllValues().get(1)).isEqualTo(sent.getAllValues().get(0));
    }

    /**
     * The timer's cancel: this order's PENDING charge and reserve rows become
     * CANCELLED with the card number removed; another order's rows are left
     * alone. Mutations: the scrub skipped on cancel (named in review); every
     * order's rows cancelled.
     */
    @Test
    void theCancelTakesOnlyThisOrdersUnsentRequestsAndRemovesTheCardNumber() {
        long cancelled = order("key-0001");
        long other = order("key-0002");

        assertThat(cancel(cancelled)).isEqualTo(2);

        List<OutboxEvent> rows = outboxRows();
        assertThat(rows.subList(0, 2)).allSatisfy(row -> {
            assertThat(row.getMessageKey()).isEqualTo(Long.toString(cancelled));
            assertThat(row.getStatus()).isEqualTo(OutboxStatus.CANCELLED);
            assertThat(row.getSentAt()).isNull();
        });
        assertThat(rows.get(0).getPayload()).contains(SCRUBBED).doesNotContain(CARD);
        assertThat(rows.subList(2, 4)).allSatisfy(row -> {
            assertThat(row.getMessageKey()).isEqualTo(Long.toString(other));
            assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        });
        assertThat(rows.get(2).getPayload()).contains(CARD);
    }

    /**
     * Only charges and reserves are cancelled: a refund or release a failed
     * order still needs stays PENDING. Mutation: every topic cancelled.
     */
    @Test
    void refundsAndReleasesAreNeverCancelled() {
        long orderId = order("key-0001");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            orderOutbox.recordRefund(orderId, MERCHANT, "TXN-" + "0".repeat(32));
            orderOutbox.recordRelease(orderId, 10, 1);
        });

        assertThat(cancel(orderId)).isEqualTo(2);

        assertThat(outboxRows()).extracting(OutboxEvent::getStatus).containsExactly(
                OutboxStatus.CANCELLED, OutboxStatus.CANCELLED, OutboxStatus.PENDING, OutboxStatus.PENDING);
    }

    /**
     * Whichever mark comes first wins: a row already SENT is not cancelled, and
     * a CANCELLED row is never sent nor marked SENT. Mutation: markSent without
     * its status condition (a cancelled row marked SENT).
     */
    @Test
    void theFirstMarkWins() {
        long sentFirst = order("key-0001");
        relay.publishPending();
        reset(publisher);
        long cancelledFirst = order("key-0002");

        assertThat(cancel(sentFirst)).isZero();
        assertThat(cancel(cancelledFirst)).isEqualTo(2);
        assertThat(relay.publishPending()).isTrue();

        verifyNoMoreInteractions(publisher);
        OutboxEvent cancelledCharge = outboxRows().get(2);
        assertThat(spiedOutbox.markSent(cancelledCharge.getId(), Instant.now(), "{}")).isZero();
        assertThat(outboxRows()).extracting(OutboxEvent::getStatus).containsExactly(
                OutboxStatus.SENT, OutboxStatus.SENT, OutboxStatus.CANCELLED, OutboxStatus.CANCELLED);
        assertThat(outboxRows().get(2).getPayload()).contains(SCRUBBED);
    }

    /** The cancel commits with the timer's change, never on its own. Mutation: MANDATORY removed. */
    @Test
    void theCancelNeedsTheCallersTransaction() {
        long orderId = order("key-0001");

        assertThatThrownBy(() -> orderOutbox.cancelUnsentRequests(orderId))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(outboxRows()).extracting(OutboxEvent::getStatus).containsOnly(OutboxStatus.PENDING);
    }
}
