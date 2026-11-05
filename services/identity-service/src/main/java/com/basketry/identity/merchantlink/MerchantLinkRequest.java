package com.basketry.identity.merchantlink;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /internal/accounts/merchant-link. {@code initialPassword} is
 * optional here: it is required, and checked against the password policy,
 * only when a new account has to be created (MerchantLinkService).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MerchantLinkRequest(

        @NotBlank @Email @Size(max = 255)
        String email,

        @NotNull @Positive
        Long merchantId,

        String initialPassword) {

    /** Keeps the plaintext password out of any log line that prints this object. */
    @Override
    public String toString() {
        return "MerchantLinkRequest[email=" + email + ", merchantId=" + merchantId + ", initialPassword=***]";
    }
}
