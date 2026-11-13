# Services

Each subfolder is one independently deployable Spring Boot service. See
[`../docs/DESIGN.md`](../docs/DESIGN.md) for what each one owns and how
they talk to each other.

| Service | Local port | Status |
|---|---|---|
| `identity-service` | 8080 | Done (deployment items open) |
| `catalog-service` | 8081 | Done (deployment items open) |
| `merchant-service` | 8082 | Done (deployment items open) |
| `inventory-service` | 8083 | Done (deployment items open) |
| `payment-service` | 8084 | Done (deployment items open) |
| `order-service` | 8085 | In progress (checkout, model and outbox built; coordinator, REST API and security next) |
| `promotion-service` | 8086 | Not started |
| `conversation-service` | 8087 | Not started |
| `platform-service` | 8088 | Not started |

Update this table's status column as each service is built — this file
exists so the repo's progress is visible at a glance without opening
every folder. The ports are fixed so the services can run side by side
locally; each service sets its own in `application.yml`.

## Kafka, Redis and Postgres for local development

All three run from the `docker-compose.yml` at the repo root, with Docker
Desktop running. Postgres starts only with the Compose profile `postgres`:

```
docker compose up -d --wait                     # Kafka and Redis (services on H2)
docker compose --profile postgres up -d --wait  # Kafka, Redis and Postgres
docker compose down                             # stop everything, keep all data
docker compose down -v                          # stop everything, wipe all data
```

Services run from IntelliJ connect to Kafka at `localhost:9092` (other
containers on the Compose network to `kafka:29092`), to Redis at
`localhost:6379` and to Postgres at `localhost:5432`. All three listen on
127.0.0.1 only. If port 5432 is taken, set `BASKETRY_POSTGRES_PORT` (for example
to 5433) for Compose **and** the services; for 6379 the same with
`BASKETRY_REDIS_PORT`.

Redis holds inventory-service's stock counters, which mirror its database, and
catalog-service's cache of product listings. It keeps nothing: no snapshots, no
append-only file, no volume. Every start is empty: inventory-service reloads the
counters from its database at startup, every 5 minutes, and whenever one is
missing, and catalog-service fills its cache again as listings are read. It runs
with 64 MB and `volatile-lru`: when full, it evicts cached listing pages (they
have a TTL) and never a stock counter (no TTL; DESIGN.md Section 7).

All six services also run without Kafka. order-service: a checkout is still
accepted, and its requests wait in its outbox until the broker is back (a
checkout does need identity-service, catalog-service and merchant-service: if
one is down, it is refused with a 503 and nothing is stored). merchant-service:
`PATCH` still works and the events wait in its outbox until the broker is back.
identity-service: login and every other request still work, and the status
consumer catches up once the broker is back. payment-service: the request
listeners connect once the broker is back, and results wait in its outbox
meanwhile. inventory-service: the REST API still works, the request listeners
connect once the broker is back, and results wait in its outbox meanwhile. It
also runs without Redis: reservations are then
decided by its database alone, still correctly, and it logs one WARN per outage.
catalog-service: the REST API still works and the status consumer catches up
once the broker is back (until then a deactivated merchant's products stay
listed). It runs without Redis (listings are then read from its database, one WARN per outage)
and without inventory-service (listings show availability `UNKNOWN`, and new
products wait for their stock row, which the relay creates once inventory-service
is back). Without their database the services do not start.

## Trying catalog-service's REST API by hand

Start identity-service, merchant-service, inventory-service and catalog-service
(H2 is fine; Redis optional; Kafka needed only for merchant status changes,
below), each from a PowerShell window in
which the same internal key is set first (any value of at least 32 characters):

```
$env:BASKETRY_INTERNAL_API_KEY = 'local-dev-only-internal-key-00000000'
.\gradlew.bat :catalog-service:bootRun    # and :identity-service:bootRun, :merchant-service:bootRun, :inventory-service:bootRun
```

catalog-service (port 8081) reaches identity-service's JWKS at
`BASKETRY_IDENTITY_URL` (default `http://localhost:8080`) and inventory-service
at `BASKETRY_INVENTORY_URL` (default `http://localhost:8083`). Then, from another
PowerShell window with the same variable set, onboard a merchant and log in as
it (the admin is the placeholder identity-service seeds):

```
$admin = (Invoke-RestMethod -Method Post http://localhost:8080/auth/login -ContentType 'application/json' -Body '{"email":"admin-placeholder@basketry.example","password":"ChangeMe-Before-Deploy!"}').accessToken
$onboarded = Invoke-RestMethod -Method Post http://localhost:8082/merchants -Headers @{ Authorization = "Bearer $admin" } -ContentType 'application/json' -Body '{"name":"Tea House","category":"Groceries","kycVerified":true,"commissionRate":5.00,"loginEmail":"tea@example.com","initialPassword":"Tea-Pass1"}'
$shopId = $onboarded.id
$shop = (Invoke-RestMethod -Method Post http://localhost:8080/auth/login -ContentType 'application/json' -Body '{"email":"tea@example.com","password":"Tea-Pass1"}').accessToken
```

Create a product, see it in the public listing, give it stock and see it again:

```
$productId = (Invoke-RestMethod -Method Post http://localhost:8081/products -Headers @{ Authorization = "Bearer $shop" } -ContentType 'application/json' -Body '{"name":"Green Tea","description":"Loose leaf, 100 g","category":"Drinks","price":4.50}').id
Invoke-RestMethod http://localhost:8081/products | ConvertTo-Json -Depth 5
Invoke-RestMethod "http://localhost:8081/manage/products/$productId" -Headers @{ Authorization = "Bearer $shop" }
Invoke-RestMethod -Method Post "http://localhost:8083/stock/$productId/adjustments" -Headers @{ Authorization = "Bearer $shop" } -ContentType 'application/json' -Body '{"delta":10}'
Invoke-RestMethod "http://localhost:8081/products/$productId"
```

The new product is listed at once with `availability: OUT_OF_STOCK`. Within a
second the relay creates its stock row in inventory-service, so the management
read shows `stockRow: CREATED` (`PENDING` before), and the adjustment (on
inventory-service, the owner of stock) then makes the detail `IN_STOCK`. If the
adjustment answers 403, the stock row is not there yet: wait a second and repeat
it. Change the price, deactivate, and read what the buyer and order-service see:

```
Invoke-RestMethod -Method Patch "http://localhost:8081/products/$productId" -Headers @{ Authorization = "Bearer $shop" } -ContentType 'application/json' -Body '{"price":4.95}'
Invoke-RestMethod -Method Patch "http://localhost:8081/products/$productId" -Headers @{ Authorization = "Bearer $shop" } -ContentType 'application/json' -Body '{"active":false}'
Invoke-RestMethod http://localhost:8081/manage/products -Headers @{ Authorization = "Bearer $shop" } | ConvertTo-Json -Depth 5
Invoke-RestMethod "http://localhost:8081/internal/products?ids=$productId" -Headers @{ 'X-Internal-Api-Key' = $env:BASKETRY_INTERNAL_API_KEY } | ConvertTo-Json -Depth 5
try { Invoke-RestMethod "http://localhost:8081/products/$productId" } catch { (New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())).ReadToEnd() }
```

The listing shows the new price at once (the cache is invalidated on every
change). The deactivated product stays in the merchant's own list
(`active: false`) and in the internal answer, while the public detail answers
404. `{"active":true}` brings it back. Admin reads every product
(`GET /manage/products`, optionally `?merchantId=`) and changes none. A refusal
makes `Invoke-RestMethod` throw; the `try` line above shows how to read the
problem detail in Windows PowerShell 5.1. DESIGN.md Section 3 lists every
endpoint and rule.

Set `BASKETRY_INTERNAL_API_KEY` in every window, to the same value. Without it,
catalog-service starts with a WARN and refuses every `/internal/**` call, and
inventory-service answers its calls 401: the stock-row relay keeps retrying
(with an ERROR in the log, backing off up to 60 s), so new products stay
`PENDING` and out of stock, and listings show availability `UNKNOWN`. Once the
key is set and catalog-service restarted, the relay creates every waiting stock
row.

### What order-service reads from merchant-service

At checkout order-service also reads each merchant's live status and commission
rate from merchant-service's internal endpoint (same window, same key):

```
Invoke-RestMethod "http://localhost:8082/internal/merchants?ids=$shopId" -Headers @{ 'X-Internal-Api-Key' = $env:BASKETRY_INTERNAL_API_KEY } | ConvertTo-Json -Depth 5
```

It answers `id`, `active` and `commissionRate` (`5.00`, the scale kept) for each
merchant that exists, in id order. Without the key, with a wrong one, or with a
user token instead, it answers a bare 401; with no key configured in
merchant-service, every call does (DESIGN.md Section 3, merchant-service).

### A merchant deactivated and reactivated

With Kafka running (`docker compose up -d --wait`) and catalog-service started
after it or before it (it waits for the broker and for the topic), deactivate
the merchant as Admin and read the listing again:

```
Invoke-RestMethod -Method Patch "http://localhost:8082/merchants/$($onboarded.merchantCode)" -Headers @{ Authorization = "Bearer $admin" } -ContentType 'application/json' -Body '{"active":false}'
Invoke-RestMethod "http://localhost:8081/products?merchantId=$shopId" | ConvertTo-Json -Depth 5
Invoke-RestMethod "http://localhost:8081/internal/products?ids=$productId" -Headers @{ 'X-Internal-Api-Key' = $env:BASKETRY_INTERNAL_API_KEY } | ConvertTo-Json -Depth 5
```

merchant-service publishes `merchant.status.changed` from its outbox within a
second, and catalog-service applies it: the merchant's products leave the public
listing (the cache is invalidated after the change commits), the public detail
answers 404, and the internal answer shows `merchantActive: false`. The first
status change ever creates the topic, and catalog-service notices a new topic
within 10 s, so the first change can take that long; later ones take about a
second. `{"active":true}` brings the products back.

A record catalog-service can never apply goes to its own dead-letter topic,
`merchant.status.changed.catalog-service.DLT` (identity-service has its own,
`merchant.status.changed.DLT`), with the record's exact bytes and two headers:
the exception class and a message that never quotes the value. To see one, from
the repo root, produce a record whose `active` is a string (one `key|value` per
line; Ctrl+C to stop), then read the dead-letter topic:

```
docker compose exec kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:29092 --topic merchant.status.changed --property parse.key=true --property key.separator="|"
1|{"schemaVersion":1,"merchantId":1,"active":"false","version":99}
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic merchant.status.changed.catalog-service.DLT --from-beginning --property print.key=true --property print.headers=true --timeout-ms 10000
```

The dead letter's headers include
`kafka_dlt-exception-message:active must be a JSON boolean`. identity-service
reads the same record and sends it to its own dead-letter topic. Nothing else
changes, and the next status change of merchant 1 is applied as usual.

`scripts/e2e-catalog.ps1` runs all of this, the listing cache checks below and a
Redis outage against real processes (`-Postgres` adds a restart of
catalog-service; see Tests).

## Sending payment-service a request by hand

order-service owns the two request topics and creates them on its first publish.
Without it running, the broker does not create topics by itself, so create them
once (they survive `docker compose down`, not `down -v`; `--if-not-exists` makes
this harmless if order-service already did). payment-service creates its own
result and dead-letter topics. From the repo root, with Compose and
payment-service running:

```
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --create --if-not-exists --topic order.merchant-charge.requested --partitions 3 --replication-factor 1
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --create --if-not-exists --topic order.merchant-refund.requested --partitions 3 --replication-factor 1
```

Produce a charge request, keyed by its orderId. Type the line after the
producer starts, then Ctrl+C:

```
docker compose exec kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:29092 --topic order.merchant-charge.requested --property parse.key=true --property key.separator="|"
7|{"schemaVersion":1,"orderId":7,"merchantId":3,"amount":25.50,"cardNumber":"4000000000000002"}
```

`4000000000000010` declines instead (DESIGN.md Section 2 has the table). A
refund request goes to `order.merchant-refund.requested` the same way, with
`"transactionRef"` (from the `charged` result) in place of `amount` and
`cardNumber`. Read the results, and the dead-letter topic with its headers:

```
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic order.merchant.charged --from-beginning --property print.key=true --timeout-ms 10000
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic order.merchant-charge.requested.DLT --from-beginning --property print.key=true --property print.headers=true --timeout-ms 10000
```

The other result topics are `order.merchant.declined` and
`order.merchant.refunded`. Each consumer stops after 10 s without a new record.
A request with an unknown or wrongly typed field, or
another `schemaVersion`, goes to the dead-letter topic (DESIGN.md Section 5).
Request topics and dead-letter topics carry real card numbers in their values;
only the test numbers belong there.

## Trying inventory-service's REST API by hand

To try inventory-service on its own, create a stock row through the internal
endpoint catalog-service uses for the products it creates. Product 42 here is
made up: if catalog-service also runs and creates its own product 42 for another
merchant, that product gets a stock-row `CONFLICT` and stays out of stock
(DESIGN.md Section 2, catalog-service). Start identity-service, merchant-service and
inventory-service (H2 is fine; Redis optional, Kafka not needed), each from a
PowerShell window in which the same internal key is set first (any value of at
least 32 characters):

```
$env:BASKETRY_INTERNAL_API_KEY = 'local-dev-only-internal-key-00000000'
.\gradlew.bat :inventory-service:bootRun    # and :identity-service:bootRun, :merchant-service:bootRun
```

Then, from another PowerShell window with the same variable set (the admin is
the placeholder that identity-service seeds; DESIGN.md Section 7):

```
$admin = (Invoke-RestMethod -Method Post http://localhost:8080/auth/login -ContentType 'application/json' -Body '{"email":"admin-placeholder@basketry.example","password":"ChangeMe-Before-Deploy!"}').accessToken
$merchantId = (Invoke-RestMethod -Method Post http://localhost:8082/merchants -Headers @{ Authorization = "Bearer $admin" } -ContentType 'application/json' -Body '{"name":"Corner Shop","category":"Groceries","kycVerified":true,"commissionRate":5.00,"loginEmail":"shop@example.com","initialPassword":"Shop-Pass1"}').id
$merchant = (Invoke-RestMethod -Method Post http://localhost:8080/auth/login -ContentType 'application/json' -Body '{"email":"shop@example.com","password":"Shop-Pass1"}').accessToken
$key = @{ 'X-Internal-Api-Key' = $env:BASKETRY_INTERNAL_API_KEY }
Invoke-RestMethod -Method Post http://localhost:8083/internal/stock -Headers $key -ContentType 'application/json' -Body "{`"productId`":42,`"merchantId`":$merchantId}"
Invoke-RestMethod -Method Post http://localhost:8083/stock/42/adjustments -Headers @{ Authorization = "Bearer $merchant" } -ContentType 'application/json' -Body '{"delta":10}'
Invoke-RestMethod http://localhost:8083/stock -Headers @{ Authorization = "Bearer $merchant" }
Invoke-RestMethod "http://localhost:8083/internal/stock?productIds=42,43" -Headers $key
```

The create answers `outcome: CREATED` at quantity 0 (`ALREADY_EXISTS` when
repeated), the adjustment answers the new quantity, and the last call leaves
out product 43, which has no stock row. A refusal makes `Invoke-RestMethod`
throw. Windows PowerShell 5.1 does not show the problem detail (with its `code`
for a 409); read it from the response:

```
try { Invoke-RestMethod -Method Post http://localhost:8083/stock/42/adjustments -Headers @{ Authorization = "Bearer $merchant" } -ContentType 'application/json' -Body '{"delta":-1000}' } catch { (New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())).ReadToEnd() }
```

Admin
can read every product (`GET /stock`, `GET /stock/{productId}`) but not adjust
any. Without the key, or with a user token instead of it, `/internal/**`
answers a bare 401. inventory-service finds identity-service's JWKS at
`BASKETRY_IDENTITY_URL` (default `http://localhost:8080`), as merchant-service
does. DESIGN.md Section 3 lists every endpoint and rule.

## Sending inventory-service a request by hand

As for payment-service, order-service owns the two request topics; without it
running, create them once; inventory-service creates its own result and
dead-letter topics. Start inventory-service with Kafka (Redis optional) and give a product
some stock first ("Trying inventory-service's REST API by hand" above). From the
repo root:

```
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --create --if-not-exists --topic order.stock-reserve.requested --partitions 3 --replication-factor 1
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --create --if-not-exists --topic order.stock-release.requested --partitions 3 --replication-factor 1
```

Produce a reserve request, keyed by its orderId. Type the line after the
producer starts, then Ctrl+C:

```
docker compose exec kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:29092 --topic order.stock-reserve.requested --property parse.key=true --property key.separator="|"
7|{"schemaVersion":1,"orderId":7,"productId":42,"quantity":3}
```

A release request goes to `order.stock-release.requested` the same way, with
the same fields. Read the results, and the dead-letter topic with its headers:

```
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic order.stock.reserved --from-beginning --property print.key=true --timeout-ms 10000
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic order.stock-reserve.requested.DLT --from-beginning --property print.key=true --property print.headers=true --timeout-ms 10000
```

The other result topics are `order.stock.reservation-failed` and
`order.stock.released`, and the other dead-letter topic is
`order.stock-release.requested.DLT`. Each consumer stops after 10 s without a
new record. A quantity above 1,000 is answered `QUANTITY_OVER_LIMIT`; a quantity
of 0 or less, a non-whole number, an unknown field or another `schemaVersion`
goes to the dead-letter topic (DESIGN.md Section 5).

## Running a service on Postgres

Each service runs on in-memory H2 by default, and on Postgres with the Spring
profile `postgres` (`application-postgres.yml`). Flyway creates the schema on
the first start.

- IntelliJ: in the service's run configuration, put `postgres` in *Active
  profiles*, or add the environment variable `SPRING_PROFILES_ACTIVE=postgres`.
- PowerShell, from the repo root:

  ```
  $env:SPRING_PROFILES_ACTIVE = 'postgres'
  .\gradlew.bat :identity-service:bootRun
  ```

  After stopping `bootRun`, check that no JVM is still holding the port
  (8080 to 8085); stop it if there is.

| Variable | Default (local only) | Used by |
|---|---|---|
| `BASKETRY_POSTGRES_HOST` | `localhost` | every service |
| `BASKETRY_POSTGRES_PORT` | `5432` | Compose and every service |
| `BASKETRY_DB_USERNAME` | `identity` / `merchant` / `payment` / `inventory` / `catalog` / `orders` | each service |
| `BASKETRY_DB_PASSWORD` | `local-dev-only-identity` / `local-dev-only-merchant` / `local-dev-only-payment` / `local-dev-only-inventory` / `local-dev-only-catalog` / `local-dev-only-orders` | each service |
| `BASKETRY_IDENTITY_DB_PASSWORD`, `BASKETRY_MERCHANT_DB_PASSWORD`, `BASKETRY_PAYMENT_DB_PASSWORD`, `BASKETRY_INVENTORY_DB_PASSWORD`, `BASKETRY_CATALOG_DB_PASSWORD`, `BASKETRY_ORDER_DB_PASSWORD` | the same six values | Compose's init script |
| `BASKETRY_POSTGRES_ADMIN_PASSWORD` | `local-dev-only-admin` (user `postgres`) | Compose |
| `BASKETRY_REDIS_HOST` | `localhost` | inventory-service, catalog-service |
| `BASKETRY_REDIS_PORT` | `6379` | Compose, inventory-service, catalog-service |
| `BASKETRY_REDIS_PASSWORD` | empty (no password locally) | inventory-service, catalog-service |
| `BASKETRY_INVENTORY_URL` | `http://localhost:8083` | catalog-service |
| `BASKETRY_CATALOG_URL` | `http://localhost:8081` | order-service |
| `BASKETRY_MERCHANT_URL` | `http://localhost:8082` | order-service |
| `BASKETRY_ORDER_FINGERPRINT_KEY` | none: unset, every checkout is refused (503) | order-service (at least 32 characters, like the internal key) |

The defaults are for local development only, and a service logs a WARN at
startup when it uses one. Each service has its own database and user, and no
user can connect to another service's database. The init script
(`docker/postgres/init`) runs only when the Postgres volume is empty: after
changing it or a Compose password, run `docker compose down -v`.

**The `inventory` database is new** (as `payment` was before it). A Postgres
volume created before it has no `inventory` database or user, so
inventory-service cannot connect on Postgres. Either run `docker compose down -v`
once and start Compose again, which **also wipes Kafka's data** and every other
service's (see Restarting), so restart the ones running on H2 too; or, keeping
all data, create just the new database and user in the running container
(change the password if you overrode `BASKETRY_INVENTORY_DB_PASSWORD`):

```
docker compose exec postgres psql -U postgres -v ON_ERROR_STOP=1 -c "CREATE ROLE inventory LOGIN PASSWORD 'local-dev-only-inventory';" -c "CREATE DATABASE inventory OWNER inventory;" -c "REVOKE CONNECT, TEMPORARY ON DATABASE inventory FROM PUBLIC;"
```

This does what the init script would have done for `inventory`. A volume that
also predates `payment` needs the same three statements for `payment`.

**The `catalog` database is new too.** The same choice: `docker compose down -v`
(wiping everything), or, keeping all data, create it in the running container
(change the password if you overrode `BASKETRY_CATALOG_DB_PASSWORD`):

```
docker compose exec postgres psql -U postgres -v ON_ERROR_STOP=1 -c "CREATE ROLE catalog LOGIN PASSWORD 'local-dev-only-catalog';" -c "CREATE DATABASE catalog OWNER catalog;" -c "REVOKE CONNECT, TEMPORARY ON DATABASE catalog FROM PUBLIC;"
```

**The `orders` database is new too** (order-service's; `order` is an SQL keyword).
The same choice: `docker compose down -v` (wiping everything), or, keeping all
data, create it in the running container (change the password if you overrode
`BASKETRY_ORDER_DB_PASSWORD`):

```
docker compose exec postgres psql -U postgres -v ON_ERROR_STOP=1 -c "CREATE ROLE orders LOGIN PASSWORD 'local-dev-only-orders';" -c "CREATE DATABASE orders OWNER orders;" -c "REVOKE CONNECT, TEMPORARY ON DATABASE orders FROM PUBLIC;"
```

The Redis change to `volatile-lru` needs nothing: Redis keeps no data, so
`docker compose up -d --wait` recreates it with the new setting.

## Looking at the data

**psql**, for everyone, while the Compose Postgres runs:

```
docker compose exec postgres psql -U identity identity
docker compose exec postgres psql -U merchant merchant
docker compose exec postgres psql -U payment payment
docker compose exec postgres psql -U inventory inventory
docker compose exec postgres psql -U catalog catalog
docker compose exec postgres psql -U orders orders
```

Then for example `\dt`, `select * from account;`, `\q` to quit.

**IntelliJ Database tool** (IntelliJ IDEA Ultimate): *Database* tool window →
*+* → *Data Source* → *PostgreSQL*, then:

| Field | identity-service | merchant-service | payment-service |
|---|---|---|---|
| Host | `localhost` | `localhost` | `localhost` |
| Port | `5432` | `5432` | `5432` |
| Authentication | User & Password | User & Password | User & Password |
| User | `identity` | `merchant` | `payment` |
| Password | `local-dev-only-identity` | `local-dev-only-merchant` | `local-dev-only-payment` |
| Database | `identity` | `merchant` | `payment` |
| URL (filled in) | `jdbc:postgresql://localhost:5432/identity` | `jdbc:postgresql://localhost:5432/merchant` | `jdbc:postgresql://localhost:5432/payment` |

Download the driver files if asked, click *Test Connection*, and on the
*Schemas* tab select only `public`. Tick *Read-only* on the *Options* tab, so
browsing cannot change a row by accident. The tables: `account`,
`admin_account` (identity), `merchant`, `outbox_event` (merchant),
`payment_transaction`, `outbox_event` (payment), `stock_level`,
`stock_reservation`, `outbox_event` (inventory), `product`, `merchant_status`,
`stock_row_request` (catalog), `customer_order`, `merchant_charge`, `order_line`,
`outbox_event` (orders), and `flyway_schema_history` in each. For
inventory-service, use `inventory` as user and database and
`local-dev-only-inventory` as password; for catalog-service, `catalog` and
`local-dev-only-catalog`; for order-service, `orders` and `local-dev-only-orders`.
`payment_transaction` holds only the last 4 digits of a card number, never the
full number. order-service's `outbox_event` holds a charge request's full card
number only while the row is `PENDING`; once `SENT` or `CANCELLED` its
`cardNumber` is null.

**The stock counters in Redis**, while Compose runs (one key per product,
`inventory:stock:<productId>`; a missing key means "not loaded", not zero):

```
docker compose exec redis redis-cli --scan --pattern "inventory:stock:*"
docker compose exec redis redis-cli GET inventory:stock:42
```

A counter may differ from `stock_level.quantity` for a while (DESIGN.md
Section 2 lists why); only the database decides a reservation, and the next
reconciliation (at most 5 minutes) sets the counter back to the database's
value. Never change a counter by hand to fix stock: use the adjustment
endpoint (above), and the counter follows.

**catalog-service's listing cache** has a version per scope
(`catalog:v2:ver:all`, `catalog:v2:ver:m:<merchantId>`) and pages under
`catalog:v2:list:...` that expire after 60 s. A product change or a merchant
status change bumps the versions, so old pages are never read again:

```
docker compose exec redis redis-cli --scan --pattern "catalog:v2:*"
docker compose exec redis redis-cli TTL catalog:v2:ver:all
docker compose exec redis redis-cli CONFIG GET maxmemory-policy
```

A version answers TTL `-1` (never evicted), a page a number up to 60, and the
policy is `volatile-lru`.
Deleting cache keys by hand is safe but never needed.

## Restarting

Kafka and Postgres are kept or wiped together, because each depends on the
other: `docker compose down` keeps both, `docker compose down -v` wipes both,
also the Postgres data when you did not pass `--profile postgres`. Never wipe
only one of them: without Kafka's data, events already sent but not yet read by
identity-service are lost; without Postgres's, the version problem below comes
back, and catalog-service, whose consumer group keeps its offset in Kafka, never
reads the old status events again, so every merchant reads as active.

- **On Postgres**, each service can be restarted on its own.
- **Redis** can be restarted or wiped at any time: it holds nothing that
  inventory-service cannot reload from its database, or catalog-service cannot
  read again.
- **On H2**, every restart empties that service's database. Restart **all**
  services and run `docker compose down -v` together, whichever one you meant
  to restart. Restarting only merchant-service resets its merchant ids and
  versions, while Kafka and a running consumer keep the old ones, so the
  consumer would ignore new events because their versions look old. Restarting
  only identity-service loses every account, while merchant-service still shows
  its merchants as `LINKED`, and they cannot be linked again. Restarting only
  payment-service loses every transaction: a repeated charge request is charged
  again under a new reference, and refunds of old references go to the
  dead-letter topic. Restarting only inventory-service loses every stock row,
  reservation and unpublished result: a repeated reserve is decided again
  (`UNKNOWN_PRODUCT` until the stock rows are created again), and a release of
  an old reservation stores a tombstone and gives nothing back. Restarting only
  catalog-service loses every product and starts the ids at 1 again, while
  inventory-service keeps the old stock rows: a new product takes over an old
  one's stock row (same merchant) or stays out of stock with a `CONFLICT` (another
  merchant), and every merchant reads as active until its next status change.
  Restarting only order-service loses every order and starts order ids at 1
  again, while payment-service and inventory-service keep their rows keyed by
  the order id: a new order 7 then gets the stored answers of the old order 7
  instead of being decided (DESIGN.md Section 2, order-service).

## Tests

```
.\gradlew.bat build          # every test on H2; no Docker needed
.\gradlew.bat postgresTest   # every test again on PostgreSQL 17.11 (and Redis 8.10.3); needs Docker
```

payment-service's card-number leak tests on their own (both are also part of
the two runs above):

```
.\gradlew.bat :payment-service:test --tests "*CardNumberLeakTests"
```

`postgresTest` (also per service, e.g. `:merchant-service:postgresTest`) starts
one Testcontainers Postgres for the run and points every test at it (the tests
do not use the `postgres` profile, only its database). Tests tagged `postgres`
run only there: they check that the run really is on Postgres, that each
service starts with its real `postgres` profile, and the 5 s row-lock timeout.

inventory-service's and catalog-service's `postgresTest` also start one
Testcontainers Redis (the Compose image, with the same flags: no persistence,
64 MB, `volatile-lru`) and run every test against it, inventory-service's with
the real Lua scripts. Its default `test` uses an in-memory fake counter
instead, which passes the same contract tests. Tests tagged `redis` run only in
`postgresTest`: the contract against Redis, 16 threads taking the last unit 100
times over, a check that the run really is on Redis 8.10.3, and that a counter
has no TTL. catalog-service's default `test` uses an in-memory fake of the
listing cache (the same contract tests as Redis) and a fake inventory-service
over real HTTP; its `redis` tests check the contract on Redis, that a page has
its TTL and a version key none, and Redis's memory policy.

## End-to-end check

`scripts/e2e-merchant-status.ps1` runs the whole `merchant.status.changed` chain
on a real broker and real processes. From PowerShell at the repo root, with
Docker running:

```
powershell -ExecutionPolicy Bypass -File scripts\e2e-merchant-status.ps1
powershell -ExecutionPolicy Bypass -File scripts\e2e-merchant-status.ps1 -Postgres
```

`-Postgres` runs the services on Postgres and restarts both halfway: the
merchant, the link and the status must survive, and the next status change must
still reach identity-service. The first reactivation after the restart can take
about 25 s: the script force-kills the services, so the old consumer stays in its
Kafka group until the session timeout ends. It needs ports 8080, 8082 and 9092 free (with
`-Postgres` also 5432, or `BASKETRY_POSTGRES_PORT`), and refuses to start
otherwise. It uses its own Compose project (`basketry-e2e`), stops only what it
started, and deletes only that project's data, before and after the run. Its
exit codes are listed at the top of the script. Service logs go to `build\e2e`.

`scripts/e2e-payment.ps1` does the same for payment-service: a real broker and
a real process, with requests produced and results read by the Kafka commands
of "Sending payment-service a request by hand" above, so those are tested too.
It sends an approved charge, the same request again, a declined charge, a
malformed charge carrying a sentinel card number, a refund and a refund of an
unknown reference, then checks the results, both dead-letter topics with their
headers, and that the sentinel is in no header and no service log line.

```
powershell -ExecutionPolicy Bypass -File scripts\e2e-payment.ps1
powershell -ExecutionPolicy Bypass -File scripts\e2e-payment.ps1 -Postgres
```

`-Postgres` runs payment-service on Postgres, checks the stored rows for the
sentinel, and restarts the service halfway: a repeated charge must still get
the same `transactionRef`. The script force-stops the service, so the restarted
listeners wait for the old ones' session to time out (up to 45 s) before they
get their partitions; the script reports how long that took. It needs ports
8084 and 9092 free (with `-Postgres` also 5432, or `BASKETRY_POSTGRES_PORT`),
uses the same Compose project as the script above (so run one at a time), and
exits with 6 if this README no longer has the exact Kafka commands it runs.
Its logs go to `build\e2e\payment`.

`scripts/e2e-inventory.ps1` does the same for inventory-service, with
identity-service and merchant-service running too, because adding stock needs a
merchant's token. Every command it runs is one of this README's, run as shown:
the REST commands of "Trying inventory-service's REST API by hand" give product
42 ten units, the Kafka commands of "Sending inventory-service a request by
hand" produce the requests and read the results, and the `redis-cli` command of
"Looking at the data" reads the counter. It sends the README's reserve and the
same record again, a reserve over the limit, a reserve of a product with no
stock row, a release before its reserve and then that reserve, a malformed
reserve and a malformed release, and the release of the first order. It checks
every result, the stock and the counter after each step, both dead-letter
topics with their headers, that nothing was answered for the malformed records,
and that no service log contains the internal API key or a database password of
the run.

```
powershell -ExecutionPolicy Bypass -File scripts\e2e-inventory.ps1
powershell -ExecutionPolicy Bypass -File scripts\e2e-inventory.ps1 -Postgres
```

`-Postgres` runs inventory-service on Postgres (identity-service and
merchant-service stay on H2: they are never restarted), sets the counter of
product 42 to a wrong value and restarts inventory-service halfway: startup
reconciliation must load the counter from Postgres again, and a replayed reserve
and release must take and give back nothing. As above, the restarted listeners
wait up to 45 s for their partitions. It needs ports 8080, 8082, 8083, 9092 and
6379 free (`BASKETRY_REDIS_PORT` instead of 6379 if set; with `-Postgres` also
5432, or `BASKETRY_POSTGRES_PORT`), uses its own Compose project
(`basketry-e2e-inventory`; the ports are the same, so still run one script at a
time), and exits with 6 if this README no longer has the exact commands it runs.
A run takes about 4 minutes, 5 with `-Postgres`. Its logs go to
`build\e2e\inventory`.

`scripts/e2e-catalog.ps1` does the same for catalog-service, with
identity-service, merchant-service and inventory-service running too. Every
command it runs is one of this README's, run as shown: the REST commands of
"Trying catalog-service's REST API by hand" onboard a merchant, create a product
and deactivate and reactivate the merchant, the Kafka commands of "A merchant
deactivated and reactivated" produce the malformed status record and read
catalog-service's dead-letter topic, and the `redis-cli` commands of "Looking at
the data" read the cache keys, their TTLs and the eviction policy. It checks
that the product is listed once its stock row is `CREATED`; that a deactivation
(the first status change, which also creates the topic) takes it out of both
cached listings, makes the detail 404, makes `/internal/products` say
`merchantActive: false` and raises both cache versions by exactly one; that a
reactivation brings it back; that the malformed record reaches
`merchant.status.changed.catalog-service.DLT` byte for byte with our two
headers only, while the real event behind it is still applied; that every
catalog key has the `catalog:v2:` prefix, versions and an inventory counter have
TTL -1, a page at most 60 s, and the policy is `volatile-lru`; that with Redis
stopped the listing still answers from the database and a deactivation still
hides the product; that the dead-letter topic holds only the one record; and
that no service log contains the internal API key or a database password of the
run. inventory-service runs with a counter reconciliation every 5 s, so a
counter exists for the TTL check.

```
powershell -ExecutionPolicy Bypass -File scripts\e2e-catalog.ps1
powershell -ExecutionPolicy Bypass -File scripts\e2e-catalog.ps1 -Postgres
```

`-Postgres` runs catalog-service on Postgres (the other services stay on H2:
they are never restarted) and force-stops it halfway. The merchant is
deactivated while it is down; once restarted, catalog-service must pick its
consumer group back up from the committed offset and apply that event, which
waits up to 45 s for the old session to time out (the script reports the
delay). It needs ports 8080 to 8083, 9092 and 6379 free (`BASKETRY_REDIS_PORT`
instead of 6379 if set; with `-Postgres` also 5432, or
`BASKETRY_POSTGRES_PORT`), uses its own Compose project (`basketry-e2e-catalog`;
the ports are the same, so still run one script at a time), and exits with 6 if
this README no longer has the exact commands it runs. A run takes about 2
minutes, 3 with `-Postgres`. Its logs go to `build\e2e\catalog`.
