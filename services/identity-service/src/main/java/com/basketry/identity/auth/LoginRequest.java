package com.basketry.identity.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Body of POST /auth/login. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoginRequest(

        @NotBlank
        String email,

        @NotNull
        String password) {

    /** Keeps the plaintext password out of any log line that prints this object. */
    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=***]";
    }
}
