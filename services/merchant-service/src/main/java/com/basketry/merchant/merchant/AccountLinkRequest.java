package com.basketry.merchant.merchant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /merchants/{code}/account-link, the retry of a failed link.
 * Both fields are optional, and so is the body itself. {@code email} defaults
 * to the merchant's stored {@code loginEmail}; if given, it is trimmed and
 * must not be blank. {@code initialPassword} is never stored or logged.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountLinkRequest(

        @Email @Size(min = 1, max = 255)
        String email,

        String initialPassword) {

    static final AccountLinkRequest EMPTY = new AccountLinkRequest(null, null);

    public AccountLinkRequest {
        email = email == null ? null : email.strip();
    }

    /** Keeps the plaintext password out of any log line that prints this object. */
    @Override
    public String toString() {
        return "AccountLinkRequest[email=" + email + ", initialPassword=***]";
    }
}
