-- Indexes for the reads (DESIGN.md Section 3, order-service) and for the
-- timer's due lists (Section 2, order-service, the timer). V1 had none for
-- any of them: each would read its whole table. Not partial indexes: H2 does
-- not have them.

-- GET /orders for a buyer: its own orders, newest first.
CREATE INDEX ix_customer_order_buyer_id ON customer_order (buyer_account_id, id);

-- GET /orders?view=merchant: the orders with a line of this merchant.
CREATE INDEX ix_order_line_merchant_order ON order_line (merchant_id, order_id);

-- GET /orders?needsAttention=true for Admin: the repair queue, newest first.
CREATE INDEX ix_customer_order_attention_id ON customer_order (needs_attention, id);

-- The timer: orders past their deadline (PENDING or FAILING).
CREATE INDEX ix_customer_order_status_deadline ON customer_order (status, deadline_at);

-- The timer: refunds due again (REFUND_REQUESTED), and from them STUCK.
CREATE INDEX ix_merchant_charge_status_due ON merchant_charge (status, compensation_due_at);

-- The timer: releases due again (RELEASE_REQUESTED or TIMED_OUT), and from them STUCK.
CREATE INDEX ix_order_line_status_due ON order_line (status, compensation_due_at);
