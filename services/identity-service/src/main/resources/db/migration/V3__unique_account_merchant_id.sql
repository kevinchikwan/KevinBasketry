-- One account per merchant (DESIGN.md Section 3, merchant-link). The other
-- direction, at most one merchant per account, is the single merchant_id
-- column itself. NULLs stay allowed, so any number of unlinked buyers can exist.
CREATE UNIQUE INDEX uq_account_merchant_id ON account (merchant_id);
