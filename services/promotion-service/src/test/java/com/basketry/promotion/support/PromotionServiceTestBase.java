package com.basketry.promotion.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Every Spring test of promotion-service: the real application on the test
 * database (H2, or PostgreSQL in postgresTest), emptied before each test.
 */
@SpringBootTest
public abstract class PromotionServiceTestBase {

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void emptyTheDatabase() {
        jdbc.update("DELETE FROM coupon");
    }
}
