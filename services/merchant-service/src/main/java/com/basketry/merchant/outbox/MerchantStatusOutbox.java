package com.basketry.merchant.outbox;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the outbox row for a status change. The caller's transaction must be
 * the one that changes the merchant, so both commit or neither does: a plain
 * publish after the commit could lose the event (DESIGN.md Section 5).
 */
@Service
public class MerchantStatusOutbox {

    private final OutboxRepository outbox;
    private final JsonMapper json;

    public MerchantStatusOutbox(OutboxRepository outbox, JsonMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    /** {@code version} is the merchant's new status version. Fails if no transaction is open. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordChange(long merchantId, String merchantCode, boolean active, long version) {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        String payload = json.writeValueAsString(new MerchantStatusChanged(
                MerchantStatusChanged.SCHEMA_VERSION, eventId, merchantId, merchantCode, active, version, now));
        outbox.save(new OutboxEvent(eventId, merchantId, version, payload, now));
    }
}
