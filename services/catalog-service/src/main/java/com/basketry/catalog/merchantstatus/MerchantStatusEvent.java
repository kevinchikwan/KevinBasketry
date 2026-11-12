package com.basketry.catalog.merchantstatus;

/** The fields of a merchant.status.changed value that catalog-service uses, as checked by the parser. */
public record MerchantStatusEvent(long merchantId, boolean active, long version) {
}
