-- merchant-service schema (DESIGN.md Section 2, merchant-service).
--
-- The id comes from merchant_id_seq before the row is built, so merchant_code
-- (MCH-001, derived from the id) is set in the same INSERT, never by a later
-- UPDATE. Gaps after a rolled-back insert are fine.
CREATE SEQUENCE merchant_id_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE merchant (
    id                  BIGINT                   PRIMARY KEY,
    merchant_code       VARCHAR(20)              NOT NULL,
    name                VARCHAR(100)             NOT NULL,
    category            VARCHAR(50)              NOT NULL,
    kyc_verified        BOOLEAN                  NOT NULL,
    active              BOOLEAN                  NOT NULL,
    commission_rate     NUMERIC(5, 2)            NOT NULL,   -- percentage, e.g. 5.00
    -- The login identity-service links to this merchant. Stored lowercase.
    -- Unique: one account holds at most one merchant, so two merchants with the
    -- same login email could never both be linked.
    login_email         VARCHAR(255)             NOT NULL,
    account_link_status VARCHAR(10)              NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_merchant_code UNIQUE (merchant_code),
    CONSTRAINT uq_merchant_login_email UNIQUE (login_email),
    CONSTRAINT ck_merchant_login_email_lowercase CHECK (login_email = LOWER(login_email)),
    CONSTRAINT ck_merchant_commission_rate CHECK (commission_rate >= 0 AND commission_rate <= 100),
    CONSTRAINT ck_merchant_account_link_status CHECK (account_link_status IN ('PENDING', 'LINKED'))
);
