package com.basketry.promotion.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Every Spring test of promotion-service: the real application on the test
 * database (H2, or PostgreSQL in postgresTest), emptied before each test.
 * Bearer tokens are verified against a fake identity-service publishing
 * TestTokens.KEY.
 */
@SpringBootTest
public abstract class PromotionServiceTestBase {

    @Autowired
    protected JdbcTemplate jdbc;

    @DynamicPropertySource
    static void identityServer(DynamicPropertyRegistry registry) {
        registry.add("basketry.identity.base-url", () -> FakeIdentityServer.shared().baseUrl());
    }

    @BeforeEach
    void emptyTheDatabase() {
        // Usages first: each references its coupon.
        jdbc.update("DELETE FROM coupon_usage");
        jdbc.update("DELETE FROM coupon");
    }
}
