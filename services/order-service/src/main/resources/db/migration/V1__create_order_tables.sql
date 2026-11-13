-- order-service schema (DESIGN.md Section 2, order-service).
--
-- The table is customer_order, not order: ORDER is an SQL keyword.
CREATE SEQUENCE customer_order_id_seq START WITH 1 INCREMENT BY 1;

-- One row per checkout. Its id is the orderId of every saga event, and the
-- idempotency key of payment-service and inventory-service.
CREATE TABLE customer_order (
    id                  BIGINT                   PRIMARY KEY,
    order_reference     VARCHAR(24)              NOT NULL,   -- ORD- and 20 random base32 characters
    buyer_account_id    BIGINT                   NOT NULL,   -- the token's sub; reference only
    idempotency_key     VARCHAR(64)              NOT NULL,
    request_fingerprint VARCHAR(64)              NOT NULL,   -- HMAC-SHA256, hex; never the card number
    status              VARCHAR(10)              NOT NULL,
    needs_attention     BOOLEAN                  NOT NULL,
    coupon_code         VARCHAR(50),                         -- always null until promotion-service exists
    total_amount        NUMERIC(12, 2)           NOT NULL,   -- the sum of the charge legs' amounts
    deadline_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_at          TIMESTAMP WITH TIME ZONE,
    completed_at        TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_customer_order_reference UNIQUE (order_reference),
    -- Checkout idempotency: concurrent first requests with one key cannot both insert.
    CONSTRAINT uq_customer_order_buyer_key UNIQUE (buyer_account_id, idempotency_key),
    CONSTRAINT ck_customer_order_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILING', 'FAILED')),
    CONSTRAINT ck_customer_order_total CHECK (total_amount >= 0),
    CONSTRAINT ck_customer_order_decided_at CHECK ((status = 'PENDING') = (decided_at IS NULL)),
    CONSTRAINT ck_customer_order_completed_at
        CHECK ((status IN ('SUCCEEDED', 'FAILED')) = (completed_at IS NOT NULL))
);

-- The charge leg: one per merchant whose amount is above zero. Its key is
-- payment-service's idempotency key.
CREATE TABLE merchant_charge (
    order_id            BIGINT                   NOT NULL,
    merchant_id         BIGINT                   NOT NULL,   -- reference only
    subtotal            NUMERIC(12, 2)           NOT NULL,
    discount            NUMERIC(12, 2)           NOT NULL,
    amount              NUMERIC(12, 2)           NOT NULL,   -- what is charged
    commission_rate     NUMERIC(5, 2)            NOT NULL,   -- snapshot at checkout, a percentage
    commission          NUMERIC(12, 2)           NOT NULL,
    status              VARCHAR(20)              NOT NULL,
    transaction_ref     VARCHAR(36),
    reason_code         VARCHAR(40),
    resends             INTEGER                  NOT NULL,
    compensation_due_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_merchant_charge PRIMARY KEY (order_id, merchant_id),
    CONSTRAINT fk_merchant_charge_order FOREIGN KEY (order_id) REFERENCES customer_order (id),
    CONSTRAINT ck_merchant_charge_status CHECK (status IN ('REQUESTED', 'CHARGED', 'DECLINED', 'TIMED_OUT',
                                                           'REFUND_REQUESTED', 'REFUNDED', 'STUCK')),
    CONSTRAINT ck_merchant_charge_amount CHECK (amount = subtotal - discount AND amount > 0 AND discount >= 0),
    CONSTRAINT ck_merchant_charge_rate CHECK (commission_rate BETWEEN 0 AND 100),
    CONSTRAINT ck_merchant_charge_commission CHECK (commission BETWEEN 0 AND amount)
);

-- One per cart line, and its reserve leg. Its key is inventory-service's
-- idempotency key.
CREATE TABLE order_line (
    order_id            BIGINT                   NOT NULL,
    product_id          BIGINT                   NOT NULL,   -- reference only
    merchant_id         BIGINT                   NOT NULL,   -- snapshot at checkout
    product_name        VARCHAR(100)             NOT NULL,   -- snapshot, for the order history
    unit_price          NUMERIC(12, 2)           NOT NULL,   -- snapshot of catalog-service's current price
    quantity            INTEGER                  NOT NULL,
    line_amount         NUMERIC(12, 2)           NOT NULL,
    status              VARCHAR(20)              NOT NULL,
    reason_code         VARCHAR(40),
    resends             INTEGER                  NOT NULL,
    compensation_due_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_order_line PRIMARY KEY (order_id, product_id),
    CONSTRAINT fk_order_line_order FOREIGN KEY (order_id) REFERENCES customer_order (id),
    CONSTRAINT ck_order_line_status CHECK (status IN ('REQUESTED', 'RESERVED', 'FAILED', 'TIMED_OUT',
                                                      'RELEASE_REQUESTED', 'RELEASED', 'STUCK')),
    CONSTRAINT ck_order_line_quantity CHECK (quantity BETWEEN 1 AND 1000),
    CONSTRAINT ck_order_line_amount CHECK (line_amount = unit_price * quantity AND unit_price > 0)
);

CREATE SEQUENCE outbox_event_id_seq START WITH 1 INCREMENT BY 1;

-- The transactional outbox (DESIGN.md Section 5): one row per request or order
-- event to publish, written in the transaction that decided it. A charge
-- request's payload holds the card number only while the row is PENDING: the
-- update that marks it SENT or CANCELLED replaces it with null.
CREATE TABLE outbox_event (
    id          BIGINT                   PRIMARY KEY,
    event_id    UUID                     NOT NULL,
    topic       VARCHAR(100)             NOT NULL,
    message_key VARCHAR(20)              NOT NULL,   -- the orderId
    -- The exact JSON to publish, so a resend after a crash is byte-identical.
    -- Large enough for order.succeeded with 100 items.
    payload     VARCHAR(100000)          NOT NULL,
    status      VARCHAR(10)              NOT NULL,
    attempts    INTEGER                  NOT NULL DEFAULT 0,   -- failed publish attempts so far
    last_error  VARCHAR(500),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_outbox_event_event_id UNIQUE (event_id),
    CONSTRAINT ck_outbox_event_status CHECK (status IN ('PENDING', 'SENT', 'CANCELLED')),
    CONSTRAINT ck_outbox_event_sent_at CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);

-- Serves the relay's query. Not a partial index: H2 does not have them.
CREATE INDEX ix_outbox_event_status_id ON outbox_event (status, id);
-- Serves the cancel of one order's unsent requests.
CREATE INDEX ix_outbox_event_key_status ON outbox_event (message_key, status);
