package com.basketry.promotion.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.promotion.support.PromotionServiceTestBase;
import org.junit.jupiter.api.Test;

/** Flyway runs at startup, on H2 here and on PostgreSQL in postgresTest, and applies every migration. */
class FlywayWiringTests extends PromotionServiceTestBase {

    /**
     * Mutations: {@code spring.flyway.enabled: false} (no history table: the query fails); the
     * migration renamed V1_create_coupon_table.sql (one underscore: Flyway skips it).
     */
    @Test
    void flywayAppliedTheCouponTable() {
        // On H2 Flyway also writes a marker row without a version ("<< Flyway Schema
        // History table created >>", type TABLE); on PostgreSQL it does not.
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE version IS NOT NULL"
                + " ORDER BY installed_rank", String.class)).containsExactly("1");
    }
}
