package com.basketry.merchant.outbox;

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
 * One status change waiting to be published, or already published (DESIGN.md
 * Section 2, merchant-service). Rows are written by {@link MerchantStatusOutbox}
 * and changed only by the update queries in {@link OutboxRepository}.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    /**
     * Taken while the merchant's row lock is held, so one merchant's events
     * always get ids in version order. allocationSize 1 keeps it that way.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "outbox_event_id")
    @SequenceGenerator(name = "outbox_event_id", sequenceName = "outbox_event_id_seq", allocationSize = 1)
    private Long id;

    @Column(updatable = false)
    private UUID eventId;

    @Column(updatable = false)
    private long merchantId;

    @Column(updatable = false)
    private long version;

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

    OutboxEvent(UUID eventId, long merchantId, long version, String payload, Instant createdAt) {
        this.eventId = eventId;
        this.merchantId = merchantId;
        this.version = version;
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

    public long getMerchantId() {
        return merchantId;
    }

    public long getVersion() {
        return version;
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
