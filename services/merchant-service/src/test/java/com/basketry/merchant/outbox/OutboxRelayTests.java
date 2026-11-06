package com.basketry.merchant.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.merchant.merchant.Merchant;
import com.basketry.merchant.support.MerchantServiceTestBase;
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
class OutboxRelayTests extends MerchantServiceTestBase {

    @Autowired
    private OutboxRelay relay;

    @MockitoBean
    private EventPublisher publisher;

    @MockitoSpyBean
    private OutboxRepository spiedOutbox;

    private void setActive(Merchant merchant, boolean active) throws Exception {
        patchAsAdmin(merchant, "{\"active\":" + active + "}").andExpect(status().isOk());
    }

    private static String key(Merchant merchant) {
        return merchant.getId().toString();
    }

    private List<OutboxEvent> events(Merchant merchant) {
        return outbox.findByMerchantIdOrderByIdAsc(merchant.getId());
    }

    @Test
    void nothingToPublish() {
        assertThat(relay.publishPending()).isTrue();

        verifyNoMoreInteractions(publisher);
    }

    @Test
    void publishesTheStoredPayloadKeyedByMerchantIdAndMarksTheRow() throws Exception {
        Merchant merchant = saveLinked("shop@example.com");
        setActive(merchant, false);
        OutboxEvent before = events(merchant).getFirst();

        assertThat(relay.publishPending()).isTrue();

        verify(publisher).publish(key(merchant), before.getPayload());
        OutboxEvent after = events(merchant).getFirst();
        assertThat(after.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(after.getSentAt()).isNotNull();
        assertThat(after.getAttempts()).isZero();
    }

    @Test
    void aSentRowIsKeptAndNotPublishedAgain() throws Exception {
        Merchant merchant = saveLinked("shop@example.com");
        setActive(merchant, false);

        relay.publishPending();
        relay.publishPending();

        verify(publisher, times(1)).publish(anyString(), anyString());
        assertThat(events(merchant)).hasSize(1);
    }

    @Test
    void aFailedRowStaysPendingAndTheNextPassRetriesIt() throws Exception {
        Merchant merchant = saveLinked("shop@example.com");
        setActive(merchant, false);
        doThrow(new IllegalStateException("broker unreachable"))
                .doNothing()
                .when(publisher).publish(anyString(), anyString());

        assertThat(relay.publishPending()).isFalse();

        OutboxEvent failed = events(merchant).getFirst();
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getLastError()).contains("broker unreachable");
        assertThat(failed.getSentAt()).isNull();

        assertThat(relay.publishPending()).isTrue();

        OutboxEvent sent = events(merchant).getFirst();
        assertThat(sent.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(sent.getAttempts()).isEqualTo(1);
        verify(publisher, times(2)).publish(key(merchant), failed.getPayload());
    }

    @Test
    void aVeryLongErrorIsCutToFitTheColumn() throws Exception {
        Merchant merchant = saveLinked("shop@example.com");
        setActive(merchant, false);
        doThrow(new IllegalStateException("x".repeat(2000))).when(publisher).publish(anyString(), anyString());

        assertThat(relay.publishPending()).isFalse();

        assertThat(events(merchant).getFirst().getLastError()).hasSize(500);
    }

    /** The first row fails, so nothing behind it goes: a later event cannot overtake it. */
    @Test
    void aFailureStopsThePassSoNoLaterEventOvertakes() throws Exception {
        Merchant merchant = saveLinked("shop@example.com");
        Merchant other = saveLinked("other@example.com");
        setActive(merchant, false);
        setActive(merchant, true);
        setActive(other, false);
        String version1 = events(merchant).get(0).getPayload();
        String version2 = events(merchant).get(1).getPayload();
        String otherVersion1 = events(other).getFirst().getPayload();
        doThrow(new IllegalStateException("broker unreachable"))
                .doNothing()
                .when(publisher).publish(anyString(), anyString());

        assertThat(relay.publishPending()).isFalse();

        verify(publisher, times(1)).publish(anyString(), anyString());
        verify(publisher, never()).publish(key(merchant), version2);
        assertThat(outbox.findAll()).allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING));

        assertThat(relay.publishPending()).isTrue();

        InOrder order = inOrder(publisher);
        order.verify(publisher, times(2)).publish(key(merchant), version1);
        order.verify(publisher).publish(key(merchant), version2);
        order.verify(publisher).publish(key(other), otherVersion1);
        assertThat(outbox.findAll()).allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT));
    }

    /** Oldest first across passes: a batch of 3 leaves the 4th for the next pass. */
    @Test
    void aPassPublishesAtMostOneBatch() throws Exception {
        Merchant merchant = saveLinked("shop@example.com");
        setActive(merchant, false);
        setActive(merchant, true);
        setActive(merchant, false);
        setActive(merchant, true);

        relay.publishPending();

        assertThat(events(merchant)).extracting(OutboxEvent::getStatus).containsExactly(
                OutboxStatus.SENT, OutboxStatus.SENT, OutboxStatus.SENT, OutboxStatus.PENDING);

        relay.publishPending();

        assertThat(events(merchant).getLast().getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    /**
     * A crash after the send and before the mark, here the mark failing: the
     * row is still PENDING, so it goes out again, byte for byte, and only then
     * ends SENT. A consumer sees the same version twice and drops the second.
     */
    @Test
    void aFailedMarkResendsTheIdenticalEvent() throws Exception {
        Merchant merchant = saveLinked("shop@example.com");
        setActive(merchant, false);
        doThrow(new IllegalStateException("crashed before the mark"))
                .when(spiedOutbox).markSent(anyLong(), any(Instant.class));

        assertThatThrownBy(relay::publishPending).hasMessageContaining("crashed before the mark");
        assertThat(events(merchant).getFirst().getStatus()).isEqualTo(OutboxStatus.PENDING);
        reset(spiedOutbox);

        assertThat(relay.publishPending()).isTrue();

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(publisher, times(2)).publish(anyString(), sent.capture());
        assertThat(sent.getAllValues().get(1)).isEqualTo(sent.getAllValues().get(0));
        assertThat(events(merchant).getFirst().getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    /** Two instances publishing the same row: the second mark changes nothing, so sentAt stays. */
    @Test
    void marksAreOnlyFromPending() throws Exception {
        Merchant merchant = saveLinked("shop@example.com");
        setActive(merchant, false);
        OutboxEvent event = events(merchant).getFirst();
        Instant first = Instant.parse("2026-01-01T00:00:00Z");

        assertThat(outbox.markSent(event.getId(), first)).isEqualTo(1);
        assertThat(outbox.markSent(event.getId(), first.plusSeconds(60))).isZero();

        assertThat(events(merchant).getFirst().getSentAt()).isEqualTo(first);
    }
}
