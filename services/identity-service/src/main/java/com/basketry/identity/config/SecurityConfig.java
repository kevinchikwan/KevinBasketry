package com.basketry.identity.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Deny by default: only the endpoints listed here are public, and each new
 * endpoint is opened explicitly as it is built. Every other endpoint needs a
 * valid bearer JWT; a request without one gets 401.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Stateless token API: no cookies or server sessions, so no CSRF token.
                .csrf(csrf -> csrf.disable())
                // Uses the corsConfigurationSource bean below; preflight requests are answered before authorization.
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/accounts/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()
                        // Lets error responses from public endpoints through instead of masking them as 401.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                // Bearer JWTs, verified with the public key only (JwtKeyConfig.jwtDecoder), as other services will.
                // Per-endpoint rules on the token's claims (e.g. GET /accounts/{id}) live with the endpoint.
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
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
        // POST /accounts/register answers with a Location header the front-end may read.
        config.setExposedHeaders(List.of("Location"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
