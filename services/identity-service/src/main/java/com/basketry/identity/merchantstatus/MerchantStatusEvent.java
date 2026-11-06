package com.basketry.identity.merchantstatus;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The fields of a {@code merchant.status.changed} value that identity-service
 * uses (DESIGN.md Section 5). Boxed, so a missing field arrives as null and
 * can be refused. Other fields, such as merchantCode, are ignored.
 *
 * @param eventId for tracing only; duplicates are dropped by {@code version}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record MerchantStatusEvent(String eventId, Long merchantId, Boolean active, Long version) {
}
