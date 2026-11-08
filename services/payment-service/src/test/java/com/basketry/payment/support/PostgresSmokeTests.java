package com.basketry.payment.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Only in postgresTest: proves the run really is on PostgreSQL, so the rest of
 * the suite passing there means something. The whole application started, so
 * Flyway ran every migration and Hibernate validated every entity against them.
 */
@Tag("postgres")
@SpringBootTest
class PostgresSmokeTests {

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${spring.jpa.hibernate.ddl-auto}")
    private String ddlAuto;

    @Test
    void theDatabaseIsPostgres17OwnedByTheServiceUser() {
        assertThat(jdbc.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL 17.11");
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("payment");
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("payment");
    }

    @Test
    void flywayAppliedEveryMigrationAndHibernateOnlyValidated() {
        assertThat(jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
                .containsExactly("1");
        assertThat(ddlAuto).isEqualTo("validate");
    }

    /** Both entities use allocationSize 1; another increment would fail or skip ids. */
    @Test
    void bothSequencesStepByOne() {
        assertThat(jdbc.queryForList(
                "SELECT sequencename || '=' || increment_by FROM pg_sequences ORDER BY sequencename", String.class))
                .containsExactly("outbox_event_id_seq=1", "payment_transaction_id_seq=1");
    }
}
