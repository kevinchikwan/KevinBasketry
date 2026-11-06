-- merchant.status.changed (DESIGN.md Section 2 and Section 5).
--
-- status_version counts changes of active. It starts at 0, so the first change
-- publishes version 1. Every existing row is still active as onboarded (active
-- could not be changed before this migration), which is exactly version 0.
ALTER TABLE merchant ADD COLUMN status_version BIGINT NOT NULL DEFAULT 0;

CREATE SEQUENCE outbox_event_id_seq START WITH 1 INCREMENT BY 1;

-- The transactional outbox: one row per status change, written in the same
-- transaction as the change. The relay publishes rows in id order.
CREATE TABLE outbox_event (
    id           BIGINT                   PRIMARY KEY,
    event_id     UUID                     NOT NULL,
    merchant_id  BIGINT                   NOT NULL,   -- the Kafka key
    version      BIGINT                   NOT NULL,   -- merchant.status_version of this change
    -- The exact JSON to publish, so a resend after a crash is byte-identical.
    payload      VARCHAR(1000)            NOT NULL,
    status       VARCHAR(10)              NOT NULL,
    attempts     INTEGER                  NOT NULL DEFAULT 0,   -- failed publish attempts so far
    last_error   VARCHAR(500),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at      TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_outbox_event_event_id UNIQUE (event_id),
    -- A backstop: one merchant can never publish two different events with one version.
    CONSTRAINT uq_outbox_event_merchant_version UNIQUE (merchant_id, version),
    CONSTRAINT ck_outbox_event_status CHECK (status IN ('PENDING', 'SENT'))
);

-- Serves the relay's query. Not a partial index: H2 does not have them.
CREATE INDEX ix_outbox_event_status_id ON outbox_event (status, id);
