package com.basketry.identity.auth;

/** Body of a successful POST /auth/login. {@code expiresIn} is in seconds. */
public record LoginResponse(String accessToken, String tokenType, long expiresIn) {
}
