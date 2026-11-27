package com.basketry.promotion.usage;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * One counted order.succeeded event (DESIGN.md Section 2, promotion-service).
 * Always new: {@code save} then inserts at once, with no SELECT first, so a
 * redelivered event fails on the primary key instead of being merged
 * (CouponUsageService). Never changed or deleted.
 */
@Entity
@Table(name = "coupon_usage")
public class CouponUsage implements Persistable<UUID> {

    @Id
    private UUID eventId;

    private long couponId;

    private long orderId;

    private Instant occurredAt;

    protected CouponUsage() {
    }

    public CouponUsage(UUID eventId, long couponId, long orderId, Instant occurredAt) {
        this.eventId = eventId;
        this.couponId = couponId;
        this.orderId = orderId;
        // The precision both databases store (as Coupon's expiresAt).
        this.occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
    }

    @Override
    public UUID getId() {
        return eventId;
    }

    /** A row is only ever inserted. */
    @Override
    public boolean isNew() {
        return true;
    }

    public long getCouponId() {
        return couponId;
    }

    public long getOrderId() {
        return orderId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
