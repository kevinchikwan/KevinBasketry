package com.basketry.identity.merchantstatus;

import java.util.UUID;

/** A record value exactly as merchant-service publishes it (DESIGN.md Section 5). */
public final class StatusEvents {

    private StatusEvents() {
    }

    public static String event(long merchantId, boolean active, long version) {
        return """
                {"schemaVersion":1,"eventId":"%s","merchantId":%d,"merchantCode":"MCH-%03d",\
                "active":%b,"version":%d,"occurredAt":"2026-11-06T01:20:22.864Z"}"""
                .formatted(UUID.randomUUID(), merchantId, merchantId, active, version);
    }
}
