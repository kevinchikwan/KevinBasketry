-- catalog-service schema (DESIGN.md Section 2, catalog-service).
--
-- The CHECKs hold only what the application always guarantees. Lengths and the
-- price range are enforced by the application (ProductLimits); the columns are
-- at least as wide, and ColumnLimitsTests stores the largest valid values.

CREATE SEQUENCE product_id_seq START WITH 1 INCREMENT BY 1;

-- A merchant's product. merchant_id is the owner, taken from the token at
-- creation; it never changes. Never deleted: a deactivated product has
-- active = FALSE, and stock rows and orders keep referring to it.
CREATE TABLE product (
    id          BIGINT                   PRIMARY KEY,
    merchant_id BIGINT                   NOT NULL,   -- merchant-service's merchant, reference only
    name        VARCHAR(100)             NOT NULL,
    description VARCHAR(2000)            NOT NULL,   -- '' when the merchant gave none
    category    VARCHAR(50)              NOT NULL,   -- free text, as merchant-service's category
    price       NUMERIC(12, 2)           NOT NULL,   -- GBP; the application allows 0.01 to 99,999.99
    active      BOOLEAN                  NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_product_merchant_id CHECK (merchant_id > 0),
    CONSTRAINT ck_product_price CHECK (price > 0)
);

-- A merchant's products, in id order: the merchant filter and the management list.
CREATE INDEX ix_product_merchant_id ON product (merchant_id, id);

-- The local copy of merchant-service's Merchant.active, written only by the
-- merchant.status.changed consumer (DESIGN.md Section 5). A merchant with no
-- row has never changed status: it is active at version 0. So no row is
-- created at onboarding or when a merchant creates a product.
CREATE TABLE merchant_status (
    merchant_id BIGINT                   PRIMARY KEY,   -- reference only
    active      BOOLEAN                  NOT NULL,
    version     BIGINT                   NOT NULL,      -- the event's version; only a newer one replaces it
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_merchant_status_merchant_id CHECK (merchant_id > 0),
    CONSTRAINT ck_merchant_status_version CHECK (version > 0)
);

-- The outbox of the stock-row creation (DESIGN.md Section 2): one row per
-- product, inserted in the product's transaction, so a product exists if and
-- only if its request does. The relay calls inventory-service's
-- POST /internal/stock and records the answer. A separate table, not a column
-- of product, so the relay never writes a product row.
CREATE TABLE stock_row_request (
    product_id   BIGINT                   PRIMARY KEY,
    merchant_id  BIGINT                   NOT NULL,   -- the owner sent to inventory-service
    status       VARCHAR(10)              NOT NULL,
    attempts     INTEGER                  NOT NULL DEFAULT 0,   -- failed calls so far
    last_error   VARCHAR(500),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_stock_row_request_product FOREIGN KEY (product_id) REFERENCES product (id),
    CONSTRAINT ck_stock_row_request_status CHECK (status IN ('PENDING', 'CREATED', 'CONFLICT', 'REJECTED')),
    CONSTRAINT ck_stock_row_request_completed_at CHECK ((status = 'PENDING') = (completed_at IS NULL))
);

-- Serves the relay's query. Not a partial index: H2 does not have them.
CREATE INDEX ix_stock_row_request_status_product ON stock_row_request (status, product_id);
