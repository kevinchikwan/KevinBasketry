package com.basketry.promotion.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;

/**
 * Request bodies are read strictly (DESIGN.md Section 3, promotion-service): a
 * field given twice is refused, never "the last one wins", and nothing may
 * follow the object. The Kafka parsers of the other services already do this
 * (order-service's ResultParser); no HTTP body did before (ROADMAP F). The
 * scalar rules (no coercion, no float as int) are in application.yml.
 */
@Configuration(proxyBeanMethods = false)
class StrictJsonConfig {

    @Bean
    JsonMapperBuilderCustomizer strictRequestBodies() {
        return builder -> builder
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
}
