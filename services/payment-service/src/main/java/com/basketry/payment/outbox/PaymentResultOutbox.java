package com.basketry.payment.outbox;

import com.basketry.payment.outbox.PaymentResultEvents.MerchantCharged;
import com.basketry.payment.outbox.PaymentResultEvents.MerchantDeclined;
import com.basketry.payment.outbox.PaymentResultEvents.MerchantRefunded;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the outbox row for a result. The caller's transaction must be the one
 * that wrote or read the payment_transaction row, so the result and its
 * outbox row commit together or not at all (DESIGN.md Section 5). Every row is
 * keyed by orderId. Each call writes a new row with a new eventId, also for a
 * repeated request.
 */
@Service
public class PaymentResultOutbox {

    private final OutboxRepository outbox;
    private final JsonMapper json;

    public PaymentResultOutbox(OutboxRepository outbox, JsonMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    /** Fails if no transaction is open. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCharged(long orderId, long merchantId, String transactionRef) {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        write(PaymentResultEvents.CHARGED_TOPIC, orderId, eventId, now, new MerchantCharged(
                PaymentResultEvents.SCHEMA_VERSION, eventId, orderId, merchantId, transactionRef, now));
    }

    /** Fails if no transaction is open. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordDeclined(long orderId, long merchantId, String responseCode) {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        write(PaymentResultEvents.DECLINED_TOPIC, orderId, eventId, now, new MerchantDeclined(
                PaymentResultEvents.SCHEMA_VERSION, eventId, orderId, merchantId, responseCode, now));
    }

    /** Fails if no transaction is open. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordRefunded(long orderId, long merchantId) {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        write(PaymentResultEvents.REFUNDED_TOPIC, orderId, eventId, now, new MerchantRefunded(
                PaymentResultEvents.SCHEMA_VERSION, eventId, orderId, merchantId, now));
    }

    private void write(String topic, long orderId, UUID eventId, Instant now, Object event) {
        outbox.save(new OutboxEvent(eventId, topic, Long.toString(orderId), json.writeValueAsString(event), now));
    }
}
