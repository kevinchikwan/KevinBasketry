package com.basketry.catalog.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates /internal/** callers by {@link InternalApiKey#HEADER} only.
 * The Authorization header is never read, so a user JWT (even an admin's)
 * cannot stand in for the key. Never logs the key. Not a bean, so Boot does
 * not also register it for every request.
 */
class InternalApiKeyFilter extends OncePerRequestFilter {

    static final String AUTHORITY = "INTERNAL_SERVICE";

    private final InternalApiKey apiKey;

    InternalApiKeyFilter(InternalApiKey apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (apiKey.matches(request.getHeader(InternalApiKey.HEADER))) {
            var authentication = new PreAuthenticatedAuthenticationToken(
                    "internal-service", null, List.of(new SimpleGrantedAuthority(AUTHORITY)));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        chain.doFilter(request, response);
    }
}
