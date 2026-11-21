package com.basketry.promotion.support;

import java.util.List;
import java.util.Map;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Points every Spring test context at one PostgreSQL container, but only when
 * the {@code postgresTest} Gradle task sets {@value #ENABLED}. The default
 * {@code test} task never gets here, so it stays on H2 and needs no Docker.
 * Registered in META-INF/spring.factories, so no test class has to opt in. A
 * copy of order-service's, with the database and user "promotion".
 */
public class PostgresContextCustomizerFactory implements ContextCustomizerFactory {

    public static final String ENABLED = "basketry.test.postgres";

    /** The same image as docker-compose.yml. */
    static final String IMAGE = "postgres:17.11-alpine";

    public static boolean onPostgres() {
        return Boolean.getBoolean(ENABLED);
    }

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass,
                                                     List<ContextConfigurationAttributes> configAttributes) {
        return onPostgres() ? new PostgresCustomizer() : null;
    }

    private static final class SharedContainer {
        // Like the Compose database: named after the service, owned by its own user.
        // Each cached test context keeps its own small pool, hence the higher limit.
        static final PostgreSQLContainer INSTANCE = start(new PostgreSQLContainer(IMAGE)
                .withDatabaseName("promotion")
                .withUsername("promotion")
                .withPassword("test-only-not-a-secret")
                .withCommand("postgres", "-c", "max_connections=300"));

        private static PostgreSQLContainer start(PostgreSQLContainer container) {
            container.start();
            return container;
        }
    }

    private static final class PostgresCustomizer implements ContextCustomizer {

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration config) {
            PostgreSQLContainer postgres = SharedContainer.INSTANCE;
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("basketry-postgres-test",
                    Map.of("spring.datasource.url", postgres.getJdbcUrl(),
                            "spring.datasource.username", postgres.getUsername(),
                            "spring.datasource.password", postgres.getPassword())));
        }

        // Every instance is the same, so Spring's context cache still reuses contexts.
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
