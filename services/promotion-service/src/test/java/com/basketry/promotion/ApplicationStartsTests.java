package com.basketry.promotion;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.promotion.support.PromotionServiceTestBase;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The whole application starts on the test database. */
class ApplicationStartsTests extends PromotionServiceTestBase {

    @Autowired
    private DataSource dataSource;

    /** Mutation: the H2 driver dropped from build.gradle.kts (no DataSource, the context fails). */
    @Test
    void theContextStartsWithADataSource() {
        assertThat(dataSource).isNotNull();
    }
}
