package com.basketry.promotion.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Two chains (DESIGN.md Section 3, promotion-service):
 *
 * <ul>
 *   <li>/internal/**, checked first: the shared API key only, never a bearer
 *       token, no CORS; no key, a wrong key or no key configured is the same
 *       bare 401. A copy of merchant-service's internal chain.</li>
 *   <li>Everything else: only the health endpoint (and its probe groups) is
 *       open; any other path is a bare 401. There is no JWT endpoint yet (the
 *       coupon endpoints, ROADMAP A7, bring the resource server).</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain internalFilterChain(HttpSecurity http, InternalApiKey apiKey) throws Exception {
        http
                .securityMatcher("/internal/**")
                .csrf(csrf -> csrf.disable())
                // No CORS configuration: browsers get no Access-Control-Allow-Origin from here.
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // No oauth2ResourceServer here, so the Authorization header is never read.
                .addFilterBefore(new InternalApiKeyFilter(apiKey), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority(InternalApiKeyFilter.AUTHORITY))
                // No key, a wrong key, or an unset key: the same bare 401 in every case.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, denied) ->
                                response.setStatus(HttpStatus.UNAUTHORIZED.value())));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain defaultFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Lets error responses through instead of masking them as 401.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, denied) ->
                                response.setStatus(HttpStatus.UNAUTHORIZED.value())));
        return http.build();
    }
}
