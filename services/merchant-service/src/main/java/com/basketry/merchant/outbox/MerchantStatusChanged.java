package com.basketry.merchant.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * The value of a {@code merchant.status.changed} record, as JSON (DESIGN.md
 * Section 5). The record key is the merchantId.
 *
 * @param schemaVersion the format of this payload, so a consumer can detect a change
 * @param eventId       for tracing only; consumers drop duplicates by {@code version}
 * @param version       increases by one with every status change of this merchant
 * @param occurredAt    when the change was made, in UTC
 */
record MerchantStatusChanged(
        int schemaVersion,
        UUID eventId,
        long merchantId,
        String merchantCode,
        boolean active,
        long version,
        Instant occurredAt) {

    static final int SCHEMA_VERSION = 1;
}
