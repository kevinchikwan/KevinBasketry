-- inventory-service schema (DESIGN.md Section 2, inventory-service).
--
-- The CHECKs hold only what the application always guarantees. The upper
-- bounds (reservation size, adjustment size, stock limit) are enforced by the
-- application alone: a release can lift a product above the stock limit, and a
-- database error caused by a valid request would be retried forever by the
-- Kafka listener.

-- One row per product. Created once, never deleted, and its owner never
-- changes. quantity is the stock still available: reservations in progress
-- have already been taken from it. BIGINT, because releases can lift it above
-- the adjustment limit again and again, with no bound an INTEGER would hold.
CREATE TABLE stock_level (
    product_id  BIGINT                   PRIMARY KEY,   -- catalog-service's product, reference only
    merchant_id BIGINT                   NOT NULL,      -- the owner; reference only
    quantity    BIGINT                   NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_stock_level_product_id CHECK (product_id > 0),
    CONSTRAINT ck_stock_level_merchant_id CHECK (merchant_id > 0),
    -- The backstop behind the conditional decrement: stock never goes negative.
    CONSTRAINT ck_stock_level_quantity CHECK (quantity >= 0)
);

-- A merchant's own stock, in product order.
CREATE INDEX ix_stock_level_merchant_product ON stock_level (merchant_id, product_id);

-- One row per (order, product) whatever the outcome: the idempotency record.
-- It is never deleted: a release moves it to RELEASED, so a replayed reserve
-- finds it. FAILED rows are kept too, so a redelivered request gets the same
-- answer after a restock. No foreign key to stock_level: an UNKNOWN_PRODUCT row
-- and a release that came before its reserve have no stock row.
CREATE TABLE stock_reservation (
    order_id    BIGINT                   NOT NULL,   -- reference only
    product_id  BIGINT                   NOT NULL,   -- reference only
    quantity    INTEGER                  NOT NULL,   -- as requested; taken only when RESERVED
    status      VARCHAR(10)              NOT NULL,
    reason_code VARCHAR(30),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    released_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_stock_reservation PRIMARY KEY (order_id, product_id),
    CONSTRAINT ck_stock_reservation_quantity CHECK (quantity > 0),
    CONSTRAINT ck_stock_reservation_status CHECK (status IN ('RESERVED', 'RELEASED', 'FAILED')),
    CONSTRAINT ck_stock_reservation_reason_code CHECK (reason_code IN
        ('INSUFFICIENT_STOCK', 'UNKNOWN_PRODUCT', 'QUANTITY_OVER_LIMIT', 'RELEASED_BEFORE_RESERVE')),
    CONSTRAINT ck_stock_reservation_failed_reason CHECK ((status = 'FAILED') = (reason_code IS NOT NULL)),
    CONSTRAINT ck_stock_reservation_released_at CHECK ((status = 'RELEASED') = (released_at IS NOT NULL))
);

CREATE SEQUENCE outbox_event_id_seq START WITH 1 INCREMENT BY 1;

-- The transactional outbox (DESIGN.md Section 5), the same table as
-- payment-service's: one row per result to publish, written in the same
-- transaction as the stock_reservation change. A repeated request writes a new
-- row with the stored result. The relay that publishes the rows comes with the
-- Kafka side.
CREATE TABLE outbox_event (
    id          BIGINT                   PRIMARY KEY,
    event_id    UUID                     NOT NULL,
    topic       VARCHAR(100)             NOT NULL,
    message_key VARCHAR(20)              NOT NULL,   -- the orderId
    -- The exact JSON to publish, so a resend after a crash is byte-identical.
    payload     VARCHAR(1000)            NOT NULL,
    status      VARCHAR(10)              NOT NULL,
    attempts    INTEGER                  NOT NULL DEFAULT 0,   -- failed publish attempts so far
    last_error  VARCHAR(500),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_outbox_event_event_id UNIQUE (event_id),
    CONSTRAINT ck_outbox_event_status CHECK (status IN ('PENDING', 'SENT'))
);

-- Serves the relay's query. Not a partial index: H2 does not have them.
CREATE INDEX ix_outbox_event_status_id ON outbox_event (status, id);
