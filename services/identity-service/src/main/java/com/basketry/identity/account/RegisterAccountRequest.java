package com.basketry.identity.account;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /accounts/register. Deliberately has only email and password:
 * any other field in the JSON (merchantId, merchantActive, buyerActive, ...)
 * has nowhere to bind and is dropped. Registration can only ever create a
 * buyer (DESIGN.md Section 1).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegisterAccountRequest(

        @NotBlank @Email @Size(max = 255)
        String email,

        // Presence only: AccountRegistrationService checks the password policy
        // and names every failed rule (PasswordPolicy).
        @NotNull
        String password) {

    /** Keeps the plaintext password out of any log line that prints this object. */
    @Override
    public String toString() {
        return "RegisterAccountRequest[email=" + email + ", password=***]";
    }
}
