package com.basketry.catalog.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Only in postgresTest: the whole application starts with the real "postgres"
 * profile, so application-postgres.yml itself is checked, not only the schema.
 * The container's URL, user and password still win over the profile's
 * defaults (PostgresContextCustomizerFactory); everything else comes from it.
 */
@Tag("postgres")
@SpringBootTest
@ActiveProfiles("postgres")
@Import(TestCacheConfig.class)
class PostgresProfileTests {

    @Autowired
    private DataSource dataSource;

    @Test
    void thePoolSettingsOfThePostgresProfileApply() {
        HikariDataSource pool = (HikariDataSource) dataSource;

        assertThat(pool.getJdbcUrl()).startsWith("jdbc:postgresql://");
        assertThat(pool.getMaximumPoolSize()).isEqualTo(5);
        assertThat(pool.getConnectionTimeout()).isEqualTo(5000);
    }
}
