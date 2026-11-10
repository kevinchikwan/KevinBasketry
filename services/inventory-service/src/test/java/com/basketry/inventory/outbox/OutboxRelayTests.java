package com.basketry.inventory.outbox;

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

import com.basketry.inventory.support.InventoryServiceTestBase;
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
class OutboxRelayTests extends InventoryServiceTestBase {

    private static final String RESERVED = StockResultEvents.RESERVED_TOPIC;
    private static final String FAILED = StockResultEvents.RESERVATION_FAILED_TOPIC;
    private static final String RELEASED = StockResultEvents.RELEASED_TOPIC;

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
    void eachRowGoesToItsOwnTopicAndKeyInIdOrder() {
        givenStock(42, 10);
        stock.reserve(reserve(7, 42, 3));
        stock.reserve(reserve(8, 99, 1));      // no stock row: UNKNOWN_PRODUCT
        stock.release(release(7, 42, 3));
        List<OutboxEvent> rows = outboxRows();

        assertThat(relay.publishPending()).isTrue();

        InOrder order = inOrder(publisher);
        order.verify(publisher).publish(RESERVED, "7", rows.get(0).getPayload());
        order.verify(publisher).publish(FAILED, "8", rows.get(1).getPayload());
        order.verify(publisher).publish(RELEASED, "7", rows.get(2).getPayload());
        assertThat(outboxRows()).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);
            assertThat(row.getSentAt()).isNotNull();
            assertThat(row.getAttempts()).isZero();
        });
    }

    @Test
    void aSentRowIsKeptAndNotPublishedAgain() {
        givenStock(42, 10);
        stock.reserve(reserve(7, 42, 3));

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
        givenStock(42, 10);
        stock.reserve(reserve(7, 42, 3));
        stock.reserve(reserve(7, 42, 3));     // the re-emit
        stock.release(release(7, 42, 3));
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
        order.verify(publisher, times(2)).publish(RESERVED, "7", rows.get(0).getPayload());
        order.verify(publisher).publish(RESERVED, "7", rows.get(1).getPayload());
        order.verify(publisher).publish(RELEASED, "7", rows.get(2).getPayload());
        assertThat(outboxRows()).allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT));
    }

    @Test
    void aVeryLongErrorIsCutToFitTheColumn() {
        givenStock(42, 10);
        stock.reserve(reserve(7, 42, 3));
        doThrow(new IllegalStateException("x".repeat(2000)))
                .when(publisher).publish(anyString(), anyString(), anyString());

        assertThat(relay.publishPending()).isFalse();

        assertThat(outboxRows().getFirst().getLastError()).hasSize(500);
    }

    /** Oldest first across passes: a batch of 3 leaves the 4th for the next pass. */
    @Test
    void aPassPublishesAtMostOneBatch() {
        givenStock(42, 10);
        for (long order = 1; order <= 4; order++) {
            stock.reserve(reserve(order, 42, 1));
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
        givenStock(42, 10);
        stock.reserve(reserve(7, 42, 3));
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
        givenStock(42, 10);
        stock.reserve(reserve(7, 42, 3));
        OutboxEvent event = outboxRows().getFirst();
        Instant first = Instant.parse("2026-01-01T00:00:00Z");

        assertThat(outbox.markSent(event.getId(), first)).isEqualTo(1);
        assertThat(outbox.markSent(event.getId(), first.plusSeconds(60))).isZero();

        assertThat(outboxRows().getFirst().getSentAt()).isEqualTo(first);
    }
}
