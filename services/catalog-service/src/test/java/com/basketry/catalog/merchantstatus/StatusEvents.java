package com.basketry.catalog.merchantstatus;

/** merchant.status.changed values as merchant-service builds them (DESIGN.md Section 5). */
final class StatusEvents {

    static final String TOPIC = "merchant.status.changed";
    static final String DLT = "merchant.status.changed.catalog-service.DLT";

    private StatusEvents() {
    }

    static String event(long merchantId, boolean active, long version) {
        return """
                {"schemaVersion":1,"eventId":"0b8e4a52-3f0c-4f7e-9a51-6a3c2f1d9e70","merchantId":%d,\
                "merchantCode":"MCH-%03d","active":%s,"version":%d,"occurredAt":"2026-11-06T01:20:22.864Z"}"""
                .formatted(merchantId, merchantId, active, version);
    }
}
