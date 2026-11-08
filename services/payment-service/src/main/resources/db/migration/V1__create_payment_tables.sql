-- payment-service schema (DESIGN.md Section 2, payment-service).
--
-- The table is payment_transaction, not transaction: TRANSACTION is an SQL
-- keyword.
CREATE SEQUENCE payment_transaction_id_seq START WITH 1 INCREMENT BY 1;

-- One row per (order, merchant) charge, whatever the outcome. The row is the
-- idempotency record: a repeated charge request finds it through
-- uq_payment_transaction_order_merchant, and a refund moves it to REFUNDED.
-- The full card number is never stored, only its last 4 digits.
CREATE TABLE payment_transaction (
    id              BIGINT                   PRIMARY KEY,
    transaction_ref VARCHAR(36)              NOT NULL,   -- TXN- and 32 random hex digits
    order_id        BIGINT                   NOT NULL,   -- reference only
    merchant_id     BIGINT                   NOT NULL,   -- reference only
    amount          NUMERIC(12, 2)           NOT NULL,   -- single currency, GBP
    card_last4      VARCHAR(4)               NOT NULL,
    response_code   VARCHAR(2)               NOT NULL,   -- ISO 8583-style
    status          VARCHAR(10)              NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    refunded_at     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_payment_transaction_ref UNIQUE (transaction_ref),
    -- The idempotency key: concurrent duplicates of one charge cannot both insert.
    CONSTRAINT uq_payment_transaction_order_merchant UNIQUE (order_id, merchant_id),
    CONSTRAINT ck_payment_transaction_status CHECK (status IN ('APPROVED', 'DECLINED', 'REFUNDED')),
    CONSTRAINT ck_payment_transaction_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_transaction_refunded_at CHECK ((status = 'REFUNDED') = (refunded_at IS NOT NULL))
);

CREATE SEQUENCE outbox_event_id_seq START WITH 1 INCREMENT BY 1;

-- The transactional outbox (DESIGN.md Section 5): one row per result to
-- publish, written in the same transaction as the payment_transaction change.
-- A repeated request writes a new row with the stored result. The relay that
-- publishes the rows comes with the Kafka side.
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
