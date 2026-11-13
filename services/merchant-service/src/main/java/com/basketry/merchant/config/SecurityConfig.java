package com.basketry.merchant.config;

import com.basketry.merchant.identityclient.InternalApiKey;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Two chains (DESIGN.md Section 3, merchant-service):
 *
 * <ul>
 *   <li>/internal/**, checked first: the shared API key only, never a user
 *       JWT, no CORS; anything else is a bare 401. As identity-service and
 *       catalog-service.</li>
 *   <li>Everything else: a valid bearer JWT from identity-service; a request
 *       without one gets 401. Which capability an endpoint needs (Admin, or the
 *       merchant itself) is checked at the endpoint. Same shape as
 *       identity-service's main chain.</li>
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
                // No key, a wrong key, or only a user JWT: the same bare 401 in every case.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, denied) ->
                                response.setStatus(HttpStatus.UNAUTHORIZED.value())));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        BearerTokenAuthenticationEntryPoint unauthorized = new BearerTokenAuthenticationEntryPoint();
        TokenVerificationFailureHandler failureHandler = new TokenVerificationFailureHandler(unauthorized);
        http
                // Stateless token API: no cookies or server sessions, so no CSRF token.
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Lets error responses through instead of masking them as 401.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(unauthorized)
                        // The DSL has no setter for this, so it goes in through the filter's post-processor.
                        .withObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                            @Override
                            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                                filter.setAuthenticationFailureHandler(failureHandler);
                                return filter;
                            }
                        }))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorized));
        return http.build();
    }

    /**
     * Browser origins allowed to call this service. Tokens travel in the
     * Authorization header, never in cookies, so credentials stay disabled.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${basketry.cors.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PATCH"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // POST /merchants answers with a Location header the front-end may read.
        config.setExposedHeaders(List.of("Location"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
