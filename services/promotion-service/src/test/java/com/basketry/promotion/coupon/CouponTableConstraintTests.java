package com.basketry.promotion.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basketry.promotion.support.PromotionServiceTestBase;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The CHECKs of V1 hold what CouponCode, Discount and Coupon always guarantee,
 * so a bug that broke one would fail loudly instead of storing a coupon that
 * lies. Plain SQL, past the application, on H2 here and on PostgreSQL in
 * postgresTest. Each refusal names its constraint, so a row refused for some
 * other reason (a NOT NULL, a neighbouring CHECK) cannot pass for it.
 */
class CouponTableConstraintTests extends PromotionServiceTestBase {

    private static final String INSERT = "INSERT INTO coupon"
            + " (id, code, scope, merchant_id, discount_type, discount_value, expires_at, active)"
            + " VALUES (nextval('coupon_id_seq'), ?, ?, ?, ?, ?, TIMESTAMP WITH TIME ZONE '2027-01-01 00:00:00+00', TRUE)";

    /**
     * Every edge the application accepts, the database accepts too. Mutations:
     * {@code BETWEEN 0.01 AND 99.99}; {@code CHAR_LENGTH(code) BETWEEN 5 AND 32}.
     */
    @Test
    void rowsAtEveryEdgeAreStored() {
        insert("ABCD", "PLATFORM", null, "PERCENTAGE", "0.01");
        insert("ABCDEFGHIJKLMNOPQRSTUVWXYZ012345", "PLATFORM", null, "PERCENTAGE", "100.00");
        insert("M5-OFF_1", "MERCHANT", 5L, "FIXED", "0.01");
        insert("BIGGEST", "PLATFORM", null, "FIXED", "9999999999.99");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon", Integer.class)).isEqualTo(4);
    }

    /**
     * Mutations: the NOT REGEXP_LIKE dropped (lower case, space, É and newline
     * pass); the anchored {@code REGEXP_LIKE(code, '^[A-Z0-9_-]{4,32}$')} (the
     * newline passes on H2 only); {@code BETWEEN 1 AND 32} (ABC passes).
     */
    @Test
    void aCodeIsStoredOnlyNormalised() {
        for (String code : new String[] {"save10", "SAVE 10", "SAV\u00C9", "SAVE10\n", "ABC"}) {
            assertRefusedBy("ck_coupon_code", code, "PLATFORM", null, "PERCENTAGE", "10.00");
        }
    }

    /** Mutation: uq_coupon_code dropped. */
    @Test
    void codesAreUniqueAcrossMerchantsAndThePlatform() {
        insert("SAVE10", "MERCHANT", 5L, "PERCENTAGE", "10.00");

        assertRefusedBy("uq_coupon_code", "SAVE10", "PLATFORM", null, "PERCENTAGE", "10.00");
    }

    /** Mutations: ck_coupon_scope dropped; ck_coupon_discount_type dropped. */
    @Test
    void onlyKnownScopesAndTypesAreStored() {
        assertRefusedBy("ck_coupon_scope", "SAVE10", "GLOBAL", null, "PERCENTAGE", "10.00");
        assertRefusedBy("ck_coupon_discount_type", "SAVE10", "PLATFORM", null, "BOGO", "10.00");
    }

    /**
     * Mutations: the one-way {@code (scope <> 'MERCHANT' OR merchant_id IS NOT NULL)}
     * (a platform coupon with merchant 5 passes); {@code merchant_id >= 0}.
     */
    @Test
    void aMerchantIdIsStoredExactlyOnAMerchantCoupon() {
        assertRefusedBy("ck_coupon_merchant_id", "SAVE10", "MERCHANT", null, "FIXED", "1.00");
        assertRefusedBy("ck_coupon_merchant_id", "SAVE10", "PLATFORM", 5L, "FIXED", "1.00");
        assertRefusedBy("ck_coupon_merchant_id", "SAVE10", "MERCHANT", 0L, "FIXED", "1.00");
    }

    /**
     * Mutations: {@code BETWEEN 0 AND 100}; FIXED {@code >= 0}; the type pairing
     * dropped ({@code discount_value BETWEEN 0.01 AND 100.00 OR discount_value > 0}:
     * a 150 percent coupon passes).
     */
    @Test
    void theValueFitsItsType() {
        assertRefusedBy("ck_coupon_discount_value", "SAVE10", "PLATFORM", null, "PERCENTAGE", "0.00");
        assertRefusedBy("ck_coupon_discount_value", "SAVE10", "PLATFORM", null, "PERCENTAGE", "100.01");
        assertRefusedBy("ck_coupon_discount_value", "SAVE10", "PLATFORM", null, "PERCENTAGE", "150.00");
        assertRefusedBy("ck_coupon_discount_value", "SAVE10", "PLATFORM", null, "FIXED", "0.00");
        assertRefusedBy("ck_coupon_discount_value", "SAVE10", "PLATFORM", null, "FIXED", "-1.00");
    }

    /**
     * Not a refusal: both databases round a third decimal place to the column's
     * scale before the CHECK runs, which is why Discount refuses 0.005 itself.
     * Mutation: the column NUMERIC(12, 3).
     */
    @Test
    void theColumnRoundsAThirdDecimalPlaceBeforeTheCheck() {
        insert("ROUNDED", "PLATFORM", null, "FIXED", "0.005");

        assertThat(jdbc.queryForObject("SELECT discount_value FROM coupon WHERE code = 'ROUNDED'", BigDecimal.class))
                .isEqualByComparingTo("0.01");
    }

    private void insert(String code, String scope, Long merchantId, String type, String value) {
        jdbc.update(INSERT, code, scope, merchantId, type, new BigDecimal(value));
    }

    private void assertRefusedBy(String constraint, String code, String scope, Long merchantId, String type,
                                 String value) {
        assertThatThrownBy(() -> insert(code, scope, merchantId, type, value))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }
}
