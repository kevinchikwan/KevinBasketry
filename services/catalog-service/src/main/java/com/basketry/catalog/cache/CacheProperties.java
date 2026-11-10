package com.basketry.catalog.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code basketry.cache} in application.yml.
 *
 * @param ttl              how long a cached listing page lives
 * @param skipAfterFailure after a Redis failure, listings skip Redis for this long
 */
@ConfigurationProperties("basketry.cache")
public record CacheProperties(@DefaultValue("60s") Duration ttl, @DefaultValue("10s") Duration skipAfterFailure) {
}
