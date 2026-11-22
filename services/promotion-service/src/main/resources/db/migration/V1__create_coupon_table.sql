-- promotion-service schema (DESIGN.md Section 2, promotion-service).
--
-- The CHECKs hold what the application always guarantees (CouponCode, Discount,
-- Coupon), so a bug that broke a rule fails loudly instead of storing a coupon
-- that lies. CouponTableConstraintTests tries each one with plain SQL, on H2 and
-- on PostgreSQL. CouponUsage comes with the order.succeeded consumer (ROADMAP A8).

CREATE SEQUENCE coupon_id_seq START WITH 1 INCREMENT BY 1;

-- A merchant's or the platform's coupon. Never deleted: a deactivated coupon has
-- active = FALSE. Only active changes after creation.
CREATE TABLE coupon (
    id             BIGINT                   PRIMARY KEY,
    code           VARCHAR(32)              NOT NULL,   -- normalised: 4 to 32 of A-Z 0-9 _ -
    scope          VARCHAR(10)              NOT NULL,
    merchant_id    BIGINT,                              -- merchant-service's merchant, reference only
    discount_type  VARCHAR(10)              NOT NULL,
    discount_value NUMERIC(12, 2)           NOT NULL,   -- at most 2 decimal places: the application's rule,
                                                        -- as the column rounds a third one before the CHECK
    expires_at     TIMESTAMP WITH TIME ZONE NOT NULL,   -- expired when now >= expires_at
    active         BOOLEAN                  NOT NULL,
    -- Merchant and platform coupons share one namespace (DESIGN.md Section 2).
    CONSTRAINT uq_coupon_code UNIQUE (code),
    -- No anchored pattern: H2's $ also matches before a final newline, PostgreSQL's
    -- does not. A length and "no other character" mean the same on both.
    CONSTRAINT ck_coupon_code CHECK (CHAR_LENGTH(code) BETWEEN 4 AND 32 AND NOT REGEXP_LIKE(code, '[^A-Z0-9_-]')),
    CONSTRAINT ck_coupon_scope CHECK (scope IN ('MERCHANT', 'PLATFORM')),
    -- A merchant id exactly on a merchant coupon.
    CONSTRAINT ck_coupon_merchant_id
        CHECK ((scope = 'MERCHANT') = (merchant_id IS NOT NULL) AND (merchant_id IS NULL OR merchant_id > 0)),
    CONSTRAINT ck_coupon_discount_type CHECK (discount_type IN ('PERCENTAGE', 'FIXED')),
    -- FIXED's maximum, 9,999,999,999.99, is the column's own.
    CONSTRAINT ck_coupon_discount_value
        CHECK ((discount_type = 'PERCENTAGE' AND discount_value BETWEEN 0.01 AND 100.00)
            OR (discount_type = 'FIXED' AND discount_value > 0))
);
