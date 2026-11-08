package com.basketry.payment.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * The payloads payment-service publishes (DESIGN.md Section 5). Each carries
 * the fields of the event catalog plus, as in {@code merchant.status.changed},
 * {@code schemaVersion} (to detect a format change), {@code eventId} (tracing
 * only; duplicates are dropped by orderId and merchantId) and
 * {@code occurredAt}. None carries an amount or any part of the card number.
 */
public final class PaymentResultEvents {

    public static final int SCHEMA_VERSION = 1;

    public static final String CHARGED_TOPIC = "order.merchant.charged";
    public static final String DECLINED_TOPIC = "order.merchant.declined";
    public static final String REFUNDED_TOPIC = "order.merchant.refunded";

    private PaymentResultEvents() {
    }

    public record MerchantCharged(int schemaVersion, UUID eventId, long orderId, long merchantId,
                                  String transactionRef, Instant occurredAt) {
    }

    /** {@code reasonCode} is the ISO 8583-style response code. */
    public record MerchantDeclined(int schemaVersion, UUID eventId, long orderId, long merchantId,
                                   String reasonCode, Instant occurredAt) {
    }

    public record MerchantRefunded(int schemaVersion, UUID eventId, long orderId, long merchantId,
                                   Instant occurredAt) {
    }
}
