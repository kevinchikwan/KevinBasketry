package com.basketry.promotion.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.promotion.support.PromotionServiceTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Flyway runs at startup, on H2 here and on PostgreSQL in postgresTest. There
 * is no migration until the coupon table (ROADMAP A3): with none, Flyway logs
 * "No migrations found" as a WARN, still creates its history table and lets the
 * application start (checked on both databases before this test was written).
 * A3 replaces the empty history below with its V1.
 */
class FlywayWiringTests extends PromotionServiceTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    /** Mutation: {@code spring.flyway.enabled: false} (no history table: the query fails). */
    @Test
    void flywayRanAndAppliedNoMigrationYet() {
        // On H2 Flyway also writes a marker row without a version ("<< Flyway Schema
        // History table created >>", type TABLE); on PostgreSQL the table stays empty.
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE version IS NOT NULL",
                String.class)).isEmpty();
    }
}
