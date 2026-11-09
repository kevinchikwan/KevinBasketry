package com.basketry.inventory.outbox;

import com.basketry.inventory.outbox.StockResultEvents.StockReleased;
import com.basketry.inventory.outbox.StockResultEvents.StockReservationFailed;
import com.basketry.inventory.outbox.StockResultEvents.StockReserved;
import com.basketry.inventory.stock.ReasonCode;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the outbox row for a result. The caller's transaction must be the one
 * that wrote or read the stock_reservation row, so the result and its outbox
 * row commit together or not at all (DESIGN.md Section 5). Every row is keyed
 * by orderId. Each call writes a new row with a new eventId, also for a
 * repeated request.
 */
@Service
public class StockResultOutbox {

    private final OutboxRepository outbox;
    private final JsonMapper json;

    public StockResultOutbox(OutboxRepository outbox, JsonMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    /** Fails if no transaction is open. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordReserved(long orderId, long productId) {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        write(StockResultEvents.RESERVED_TOPIC, orderId, eventId, now, new StockReserved(
                StockResultEvents.SCHEMA_VERSION, eventId, orderId, productId, now));
    }

    /** Fails if no transaction is open. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordReservationFailed(long orderId, long productId, ReasonCode reasonCode) {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        write(StockResultEvents.RESERVATION_FAILED_TOPIC, orderId, eventId, now, new StockReservationFailed(
                StockResultEvents.SCHEMA_VERSION, eventId, orderId, productId, reasonCode, now));
    }

    /** Fails if no transaction is open. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordReleased(long orderId, long productId) {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        write(StockResultEvents.RELEASED_TOPIC, orderId, eventId, now, new StockReleased(
                StockResultEvents.SCHEMA_VERSION, eventId, orderId, productId, now));
    }

    private void write(String topic, long orderId, UUID eventId, Instant now, Object event) {
        outbox.save(new OutboxEvent(eventId, topic, Long.toString(orderId), json.writeValueAsString(event), now));
    }
}
