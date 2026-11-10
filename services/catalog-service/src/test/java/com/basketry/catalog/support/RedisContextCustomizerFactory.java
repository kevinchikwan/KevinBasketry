package com.basketry.catalog.support;

import java.util.List;
import java.util.Map;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Points every Spring test context at one Redis container, but only when the
 * {@code postgresTest} Gradle task sets {@value #ENABLED}. Then
 * {@link TestCacheConfig} adds no fake, so the real Redis cache serves the
 * whole suite. The default {@code test} task never gets here.
 */
public class RedisContextCustomizerFactory implements ContextCustomizerFactory {

    public static final String ENABLED = "basketry.test.redis";

    /** The same image and the same flags as docker-compose.yml: no persistence, 64 MB, volatile-lru. */
    static final String IMAGE = "redis:8.10.3-alpine";

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass,
                                                     List<ContextConfigurationAttributes> configAttributes) {
        return Boolean.getBoolean(ENABLED) ? new RedisCustomizer() : null;
    }

    private static final class SharedContainer {
        static final GenericContainer<?> INSTANCE = start(new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withExposedPorts(6379)
                .withCommand("redis-server", "--save", "", "--appendonly", "no",
                        "--maxmemory", "64mb", "--maxmemory-policy", "volatile-lru"));

        private static GenericContainer<?> start(GenericContainer<?> container) {
            container.start();
            return container;
        }
    }

    private static final class RedisCustomizer implements ContextCustomizer {

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration config) {
            GenericContainer<?> redis = SharedContainer.INSTANCE;
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("basketry-redis-test",
                    Map.of("spring.data.redis.host", redis.getHost(),
                            "spring.data.redis.port", redis.getMappedPort(6379))));
        }

        @Override
        public boolean equals(Object other) {
            return other != null && other.getClass() == getClass();
        }

        @Override
        public int hashCode() {
            return getClass().hashCode();
        }
    }
}
