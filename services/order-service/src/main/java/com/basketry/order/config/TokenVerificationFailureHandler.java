package com.basketry.order.config;

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
 * Copied from catalog-service. <b>503</b> when a token could not be checked at
 * all (its key is not cached and the JWKS could not be fetched): the token may
 * be valid, so a 401 would wrongly log the user out. <b>401</b> for everything
 * else: malformed, expired, tampered, or a key identity-service does not
 * publish.
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
