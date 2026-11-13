package com.basketry.order.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One request or order event waiting to be published, already published, or
 * cancelled (DESIGN.md Section 5, order-service requests). Written only by
 * {@link OrderOutbox}, in the transaction that decided it, and published by
 * {@link OutboxRelay}. A charge request's payload holds the card number only
 * while the row is PENDING: the conditional update that marks it SENT or
 * CANCELLED also replaces the payload with one whose cardNumber is null.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "outbox_event_id")
    @SequenceGenerator(name = "outbox_event_id", sequenceName = "outbox_event_id_seq", allocationSize = 1)
    private Long id;

    @Column(updatable = false)
    private UUID eventId;

    @Column(updatable = false)
    private String topic;

    /** The orderId, as a string. */
    @Column(updatable = false)
    private String messageKey;

    /** Changed only by the repository's marks, which remove the card number. */
    @Column(updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    private OutboxStatus status;

    private int attempts;

    private String lastError;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant sentAt;

    protected OutboxEvent() {
    }

    OutboxEvent(UUID eventId, String topic, String messageKey, String payload, Instant createdAt) {
        this.eventId = eventId;
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getTopic() {
        return topic;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    /** Never the payload: a charge request's holds the card number. */
    @Override
    public String toString() {
        return "OutboxEvent[id=" + id + ", topic=" + topic + ", key=" + messageKey + ", status=" + status + "]";
    }
}
