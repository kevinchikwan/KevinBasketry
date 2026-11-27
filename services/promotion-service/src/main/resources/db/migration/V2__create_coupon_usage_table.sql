-- One row per counted order.succeeded event (DESIGN.md Section 2,
-- promotion-service; ROADMAP A8). A coupon's usage count is its number of rows.
--
-- The event's eventId is the key: a redelivered event inserts nothing, and the
-- insert, not a lookup before it, decides (CouponUsageService). order_id is not
-- unique: after order-service loses its data (an H2 restart) it reuses order
-- ids, with new eventIds, and those are real orders. CouponUsageTableTests tries
-- each constraint with plain SQL, on H2 and on PostgreSQL.

CREATE TABLE coupon_usage (
    event_id    UUID                     NOT NULL,
    coupon_id   BIGINT                   NOT NULL,
    order_id    BIGINT                   NOT NULL,   -- order-service's order, reference only
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,   -- the event's occurredAt
    CONSTRAINT pk_coupon_usage PRIMARY KEY (event_id),
    -- A coupon is never deleted, so a usage always has its coupon.
    CONSTRAINT fk_coupon_usage_coupon FOREIGN KEY (coupon_id) REFERENCES coupon (id),
    CONSTRAINT ck_coupon_usage_order_id CHECK (order_id > 0)
);

-- The count per coupon: one coupon's, or one list page's in a single grouped query.
CREATE INDEX ix_coupon_usage_coupon_id ON coupon_usage (coupon_id);
