-- merchant.status.changed (DESIGN.md Section 2 and Section 5).
--
-- The version of the last status event applied to this account's merchant. A
-- merchant starts at version 0 and no event is published at onboarding, so 0 is
-- correct for every row: for unlinked accounts, and for linked ones the consumer
-- has not reached yet.
ALTER TABLE account ADD COLUMN merchant_status_version BIGINT NOT NULL DEFAULT 0;
