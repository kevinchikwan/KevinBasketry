package com.basketry.merchant.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Every endpoint needs a valid bearer JWT from identity-service; a request
 * without one gets 401. Which capability an endpoint needs (Admin, or the
 * merchant itself) is checked at the endpoint. Same shape as
 * identity-service's main chain. There is no /internal/** chain: this service
 * calls an internal endpoint but serves none.
 */
@Configuration
public class SecurityConfig {

    @Bean
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
