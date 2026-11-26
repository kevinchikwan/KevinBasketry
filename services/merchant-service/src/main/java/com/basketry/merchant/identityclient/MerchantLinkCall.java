package com.basketry.merchant.identityclient;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body sent to identity-service's POST /internal/accounts/merchant-link.
 * {@code initialPassword} is left out of the JSON when null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record MerchantLinkCall(String email, long merchantId, String initialPassword) {

    /** Keeps the email and the plaintext password out of any log line that prints this object (RestClient logs it at DEBUG). */
    @Override
    public String toString() {
        return "MerchantLinkCall[email=***, merchantId=" + merchantId + ", initialPassword=***]";
    }
}
