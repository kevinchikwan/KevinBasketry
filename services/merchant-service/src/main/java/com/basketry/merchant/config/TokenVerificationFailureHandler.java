package com.basketry.merchant.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * Decides the answer when a bearer token cannot be accepted:
 *
 * <ul>
 *   <li><b>503</b> when the token could not be checked at all: its key is not
 *       cached and the JWKS could not be fetched (identity-service down,
 *       timing out or answering with an error). The token may be perfectly
 *       valid, so a 401 would wrongly tell the front-end to log the user out.
 *       Spring's default here would be a 500.</li>
 *   <li><b>401</b> for everything else: a malformed, expired or tampered
 *       token, or a key that identity-service does not publish (for example a
 *       token signed before it restarted with a new key).</li>
 * </ul>
 */
class TokenVerificationFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(TokenVerificationFailureHandler.class);

    private static final String UNAVAILABLE_BODY = "{\"type\":\"about:blank\",\"title\":\"Service Unavailable\","
            + "\"status\":503,\"detail\":\"Token verification is temporarily unavailable. Try again shortly.\"}";

    private final AuthenticationEntryPoint unauthorized;

    TokenVerificationFailureHandler(AuthenticationEntryPoint unauthorized) {
        this.unauthorized = unauthorized;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        // JwtAuthenticationProvider wraps every decoder failure that is not a BadJwtException (such as
        // the JWKS fetch failing) in AuthenticationServiceException; invalid tokens never get here.
        if (exception instanceof AuthenticationServiceException) {
            log.warn("Cannot verify bearer token: {}", exception.getMessage());
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getOutputStream().write(UNAVAILABLE_BODY.getBytes(StandardCharsets.UTF_8));
            return;
        }
        unauthorized.commence(request, response, exception);
    }
}
