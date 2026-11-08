package com.basketry.payment.outbox;

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
 * One result waiting to be published, or already published (DESIGN.md Section
 * 5, payment-service). Written only by {@link PaymentResultOutbox}, in the
 * transaction that decided the result. The relay that publishes the rows comes
 * with the Kafka side.
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
}
