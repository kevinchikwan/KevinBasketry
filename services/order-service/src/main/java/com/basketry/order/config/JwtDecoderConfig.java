package com.basketry.order.config;

import com.basketry.order.downstream.DownstreamServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

/**
 * Verifies identity-service's RS256 tokens with the public keys it publishes
 * at GET /.well-known/jwks.json (DESIGN.md Section 2, Tokens), as
 * catalog-service does: fetched on first use (the services start in any
 * order), cached, fetched again for an unknown {@code kid}, with identity's
 * timeouts (2 s / 5 s), so a hanging identity-service fails a request quickly
 * with 503.
 */
@Configuration
public class JwtDecoderConfig {

    @Bean
    JwtDecoder jwtDecoder(DownstreamServices services) {
        DownstreamServices.Endpoint identity = services.identity();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(identity.connectTimeout());
        requestFactory.setReadTimeout(identity.readTimeout());
        return NimbusJwtDecoder.withJwkSetUri(identity.baseUrl() + "/.well-known/jwks.json")
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(new RestTemplate(requestFactory))
                .build();
    }
}
