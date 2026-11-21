#!/bin/sh
# One database and one user per service (DESIGN.md Section 7). Runs once, when
# the Postgres volume is empty. Passwords come from the environment, never from
# this file. Each user owns its database, so Flyway can create the schema, and
# no user can connect to another service's database.
set -eu

create_service_database() {
    name="$1"
    password="$2"
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
        -v name="$name" -v password="$password" <<'SQL'
CREATE ROLE :"name" LOGIN PASSWORD :'password';
CREATE DATABASE :"name" OWNER :"name";
REVOKE CONNECT, TEMPORARY ON DATABASE :"name" FROM PUBLIC;
SQL
}

create_service_database identity "$BASKETRY_IDENTITY_DB_PASSWORD"
create_service_database merchant "$BASKETRY_MERCHANT_DB_PASSWORD"
create_service_database payment "$BASKETRY_PAYMENT_DB_PASSWORD"
create_service_database inventory "$BASKETRY_INVENTORY_DB_PASSWORD"
create_service_database catalog "$BASKETRY_CATALOG_DB_PASSWORD"
# order-service's database and user are "orders": ORDER is an SQL keyword.
create_service_database orders "$BASKETRY_ORDER_DB_PASSWORD"
create_service_database promotion "$BASKETRY_PROMOTION_DB_PASSWORD"
