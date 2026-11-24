package com.basketry.promotion.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.promotion.support.PromotionServiceTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * How expires_at stores a value finer than a microsecond, on H2 and on
 * PostgreSQL: the reason the Coupon truncates expiresAt to microseconds
 * (DESIGN.md Section 2, promotion-service, amount rules).
 */
class ExpiryStorageTests extends PromotionServiceTestBase {

    private static final String INSERT = "INSERT INTO coupon"
            + " (id, code, scope, merchant_id, discount_type, discount_value, expires_at, active)"
            + " VALUES (nextval('coupon_id_seq'), ?, 'PLATFORM', NULL, 'PERCENTAGE', 10.00, ?, TRUE)";

    @Autowired
    private CouponRepository coupons;

    private Instant storedAndReadBack(String code, Instant expiresAt) {
        jdbc.update(INSERT, code, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        return jdbc.queryForObject("SELECT expires_at FROM coupon WHERE code = ?", OffsetDateTime.class, code)
                .toInstant();
    }

    /**
     * Both databases keep six digits and round the seventh (half up), so a
     * nanosecond value could move up to half a microsecond either way.
     * Mutation: {@code expires_at TIMESTAMP(9) WITH TIME ZONE} in V1 (H2 then
     * keeps the nanoseconds).
     */
    @Test
    void theColumnRoundsToTheMicrosecond() {
        assertThat(storedAndReadBack("DOWN", Instant.parse("2027-03-01T12:00:00.000000400Z")))
                .isEqualTo(Instant.parse("2027-03-01T12:00:00.000000Z"));
        assertThat(storedAndReadBack("UPUP", Instant.parse("2027-03-01T12:00:00.000000700Z")))
                .isEqualTo(Instant.parse("2027-03-01T12:00:00.000001Z"));
    }

    /**
     * Through JPA the stored expiry is the coupon's own, truncated, never
     * rounded up past it. Mutation: the truncation dropped from the Coupon
     * constructor (the row then holds .000001, the coupon .000000700).
     */
    @Test
    void aSavedCouponKeepsItsTruncatedExpiry() {
        Coupon coupon = coupons.save(new Coupon(new CouponCode("SAVE10"), CouponScope.PLATFORM, null,
                new Discount(DiscountType.PERCENTAGE, new BigDecimal("10")),
                Instant.parse("2027-03-01T12:00:00.000000700Z")));

        assertThat(coupons.findById(coupon.getId()).orElseThrow().getExpiresAt())
                .isEqualTo(coupon.getExpiresAt())
                .isEqualTo(Instant.parse("2027-03-01T12:00:00Z"));
    }
}
