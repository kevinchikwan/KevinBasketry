package com.basketry.promotion.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Only in postgresTest: proves the run really is on PostgreSQL, so the rest of
 * the suite passing there means something. Which migrations Flyway applied is
 * FlywayWiringTests' job, on both databases.
 */
@Tag("postgres")
@SpringBootTest
class PostgresSmokeTests {

    @Autowired
    private JdbcTemplate jdbc;

    /** Mutation: the test container's database and user left as "payment" in PostgresContextCustomizerFactory. */
    @Test
    void theDatabaseIsPostgres17OwnedByTheServiceUser() {
        assertThat(jdbc.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL 17.11");
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("promotion");
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("promotion");
    }
}
