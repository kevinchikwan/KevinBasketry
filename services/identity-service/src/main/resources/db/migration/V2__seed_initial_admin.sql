-- =============================================================================
-- PLACEHOLDER ADMIN — REPLACE BEFORE ANY REAL DEPLOYMENT
--
--   email:    admin-placeholder@basketry.example
--   password: ChangeMe-Before-Deploy!   (BCrypt hash below)
--
-- Both values are public in this repository. Before deploying anywhere real,
-- replace them with a real email and the BCrypt hash of a real password.
-- =============================================================================
--
-- Cross-table email uniqueness (DESIGN.md Section 2): this runs straight after
-- V1 creates an empty account table, so no collision is possible here. Any
-- later admin-seed migration must check the account table first and fail on a
-- collision.

INSERT INTO admin_account (email, password_hash, created_at)
VALUES ('admin-placeholder@basketry.example',
        '$2a$10$8WbVb5jWMM8iIWG3r20XaeDMxyuKpSkQa6d2kEgS8MQhGrSExwdQ2',
        CURRENT_TIMESTAMP);
