package com.basketry.identity.auth;

/**
 * Body of a successful POST /auth/login. {@code expiresIn} is in seconds.
 * {@link #toString()} never shows the access token: Spring logs the object it
 * writes at TRACE ("Writing [...]"); the JSON answer still holds the token.
 */
public record LoginResponse(String accessToken, String tokenType, long expiresIn) {

    @Override
    public String toString() {
        return "LoginResponse[accessToken=***, tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
    }
}
