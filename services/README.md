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
| `order-service` | 8085 | Done (deployment items open) |
| `promotion-service` | 8086 | In progress: coupon rules, discount maths, `POST /internal/coupons/price` (ROADMAP A6), the coupon endpoints for the Merchant portal and Admin (A7), the `order.succeeded` listener and the usage count (A8) |
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

All seven services run without Kafka. promotion-service: pricing and the
coupon endpoints still work, and its `order.succeeded` listener connects once
the broker is back, then counts the orders it missed (it starts from its
committed offset). order-service: a checkout is still accepted, and its
requests wait in its outbox until the broker is back (a checkout does need identity-service, catalog-service and merchant-service: if
one is down, it is refused with a 503 and nothing is stored). Its result
listeners connect once the broker is back. An outage longer than the 2-minute
deadline fails the orders it holds up: their unsent requests are cancelled and
compensated (DESIGN.md Section 2, order-service, the timer). merchant-service:
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

## Trying promotion-service's coupon endpoints by hand

Start identity-service, merchant-service and promotion-service (H2 is fine,
Kafka not needed), each from a PowerShell window in which the same internal key
is set first, as above. Then, from another window with the variable set:

```
$admin = (Invoke-RestMethod -Method Post http://localhost:8080/auth/login -ContentType 'application/json' -Body '{"email":"admin-placeholder@basketry.example","password":"ChangeMe-Before-Deploy!"}').accessToken
Invoke-RestMethod -Method Post http://localhost:8082/merchants -Headers @{ Authorization = "Bearer $admin" } -ContentType 'application/json' -Body '{"name":"Corner Shop","category":"Groceries","kycVerified":true,"commissionRate":5.00,"loginEmail":"shop@example.com","initialPassword":"Shop-Pass1"}'
$merchant = (Invoke-RestMethod -Method Post http://localhost:8080/auth/login -ContentType 'application/json' -Body '{"email":"shop@example.com","password":"Shop-Pass1"}').accessToken
$expiry = (Get-Date).ToUniversalTime().AddDays(30).ToString('yyyy-MM-ddTHH:mm:ssZ')
$coupon = Invoke-RestMethod -Method Post http://localhost:8086/coupons -Headers @{ Authorization = "Bearer $merchant" } -ContentType 'application/json' -Body "{`"code`":`"spring10`",`"scope`":`"MERCHANT`",`"discountType`":`"PERCENTAGE`",`"discountValue`":10,`"expiresAt`":`"$expiry`"}"
Invoke-RestMethod -Method Post http://localhost:8086/coupons -Headers @{ Authorization = "Bearer $admin" } -ContentType 'application/json' -Body "{`"code`":`"PLATFORM5`",`"scope`":`"PLATFORM`",`"discountType`":`"FIXED`",`"discountValue`":5.00,`"expiresAt`":`"$expiry`"}"
Invoke-RestMethod http://localhost:8086/coupons -Headers @{ Authorization = "Bearer $merchant" } | ConvertTo-Json -Depth 5
Invoke-RestMethod "http://localhost:8086/coupons?page=0&size=10" -Headers @{ Authorization = "Bearer $admin" } | ConvertTo-Json -Depth 5
Invoke-RestMethod -Method Post "http://localhost:8086/coupons/$($coupon.id)/deactivate" -Headers @{ Authorization = "Bearer $merchant" }
```

The merchant sees only its own coupon (stored as `SPRING10`), Admin both.
Creating `SPRING10` again, in any case, is a 409 `COUPON_CODE_TAKEN`; a merchant
asking for a `PLATFORM` coupon is a 403. After the deactivation the coupon shows
`active: false`, and checkout refuses its code as `COUPON_INACTIVE`. Every
answer shows `usageCount`, the orders counted for the coupon from
`order.succeeded`; it stays 0 here, as order-service sends no coupon until
ROADMAP A9. Every answer is in DESIGN.md Section 3 and
docs/contracts/coupon-admin.json.

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

## Trying order-service by hand

A checkout runs through all six services: start Kafka and Redis
(`docker compose up -d --wait`), then each service from a PowerShell window in
which the internal key and the fingerprint key are set first (any values of at
least 32 characters; the fingerprint key only matters to order-service):

```
$env:BASKETRY_INTERNAL_API_KEY = 'local-dev-only-internal-key-00000000'
$env:BASKETRY_ORDER_FINGERPRINT_KEY = 'local-dev-only-fingerprint-key-00000'
.\gradlew.bat :order-service:bootRun    # and :identity-service, :catalog-service, :merchant-service, :inventory-service, :payment-service
```

order-service (port 8085) creates its request topics, `order.succeeded` and
`order.failed` on its first publish; payment-service and inventory-service
create their result topics on theirs, and order-service's listeners find them
within 10 s. None needs creating by hand. Onboard a merchant, create a product
and give it stock first ("Trying catalog-service's REST API by hand" above:
`$admin`, `$shop`, `$productId`, ten units of Green Tea at 4.50). Then, in the
same window, register a buyer and log in:

```
Invoke-RestMethod -Method Post http://localhost:8080/accounts/register -ContentType 'application/json' -Body '{"email":"buyer@example.com","password":"Buyer-Pass1"}'
$buyer = (Invoke-RestMethod -Method Post http://localhost:8080/auth/login -ContentType 'application/json' -Body '{"email":"buyer@example.com","password":"Buyer-Pass1"}').accessToken
```

Check out two units with an approved test card. `expectedTotal` is the total
the buyer was shown; the `Idempotency-Key` (8 to 64 characters of
`A-Za-z0-9_-`) makes a retry safe:

```
$cart = @{ lines = @(@{ productId = $productId; quantity = 2 }); cardNumber = '4000000000000002'; expectedTotal = 9.00 } | ConvertTo-Json -Depth 3
$key = 'my-first-order-0001'
$order = Invoke-WebRequest -UseBasicParsing -Method Post http://localhost:8085/orders/checkout -Headers @{ Authorization = "Bearer $buyer"; 'Idempotency-Key' = $key } -ContentType 'application/json' -Body $cart
$order.StatusCode; $order.Headers.Location
```

It answers 202, `Location: /orders/ORD-...` and the order in `PENDING`; the saga
runs after the answer. Sending the same line again answers 200 with the same
`Location` and the order as it stands, and creates nothing. Read the order as
its buyer, and as Admin (raw leg states, `deadlineAt`, `decidedAt`,
`completedAt`, commission, `transactionRef`, resends):

```
Invoke-RestMethod "http://localhost:8085$($order.Headers.Location)" -Headers @{ Authorization = "Bearer $buyer" } | ConvertTo-Json -Depth 5
Invoke-RestMethod "http://localhost:8085$($order.Headers.Location)" -Headers @{ Authorization = "Bearer $admin" } | ConvertTo-Json -Depth 5
```

Within a second or two it is `SUCCEEDED`: the charge leg `CHARGED`, the line
`RESERVED`, and `GET /stock` (as the merchant) shows two units fewer. The
merchant reads it too, its own leg and lines only, with
`-Headers @{ Authorization = "Bearer $shop" }`. List orders, as the buyer
(newest first; `?view=merchant` with a merchant's token), as Admin, and Admin's
repair queue, the orders with a leg `STUCK`:

```
Invoke-RestMethod http://localhost:8085/orders -Headers @{ Authorization = "Bearer $buyer" } | ConvertTo-Json -Depth 5
Invoke-RestMethod http://localhost:8085/orders -Headers @{ Authorization = "Bearer $admin" } | ConvertTo-Json -Depth 5
Invoke-RestMethod "http://localhost:8085/orders?needsAttention=true" -Headers @{ Authorization = "Bearer $admin" } | ConvertTo-Json -Depth 5
```

A refusal makes `Invoke-WebRequest` throw; read its problem detail with the
`try` line of the catalog section above. A price changed since the cart was
shown is a 409 `PRICE_CHANGED` with the current prices and total, and nothing
is stored. The card `4000000000000010` declines (DESIGN.md Section 2,
payment-service, has the table); a quantity above the stock fails the
reservation; either way every charge taken is refunded, every reservation
released, and the order ends `FAILED` (`FAILING` while that runs). Read the
outcomes from the repo root (each consumer stops after 10 s without a new
record):

```
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic order.succeeded --from-beginning --property print.key=true --timeout-ms 10000
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic order.failed --from-beginning --property print.key=true --timeout-ms 10000
```

Both are keyed by the orderId (the number in every saga event; the API shows
only the reference). `order.succeeded` carries each merchant's commission rate
and commission; `order.failed` carries the reasons, and comes again with a new
`eventId` when a late charge reopens a failed order. The results and requests
in between are on the topics of the two sections above, read with the same
consumer line.

A result order-service can never apply goes to its own dead-letter topic,
`<result topic>.order-service.DLT`, with the record's exact bytes and our two
exception headers. To see one, produce a `charged` result with a malformed
`transactionRef` (one `key|value` line; Ctrl+C to stop), then read the
dead-letter topic:

```
docker compose exec kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:29092 --topic order.merchant.charged --property parse.key=true --property key.separator="|"
7|{"schemaVersion":1,"orderId":7,"merchantId":1,"transactionRef":"TXN-not-a-reference"}
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic order.merchant.charged.order-service.DLT --from-beginning --property print.key=true --property print.headers=true --timeout-ms 10000
```

Its headers include
`kafka_dlt-exception-message:transactionRef must be TXN- and 32 lowercase hex digits`,
and nothing else changes. order-service's six result listeners are the six
members of one consumer group, `order-service` (as payment-service's two are
of `payment-service`); see whether it is `Stable` with its six members (a group
shows `PreparingRebalance` or `CompletingRebalance` while it is being
rebalanced):

```
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:29092 --describe --all-groups --state
```

Without the fingerprint key, every checkout is refused with 503
`FINGERPRINT_KEY_NOT_CONFIGURED`; without the internal key, with 503
`INTERNAL_API_KEY_NOT_CONFIGURED`; with identity-, catalog- or merchant-service
down, with 503 and nothing stored. A result that does not come within 2 minutes
counts as failed (DESIGN.md Section 2, order-service, the timer).
`scripts/e2e-order.ps1` runs all of this and the saga's failure paths against
real processes (see End-to-end check).

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
  (8080 to 8086); stop it if there is.

| Variable | Default (local only) | Used by |
|---|---|---|
| `BASKETRY_POSTGRES_HOST` | `localhost` | every service |
| `BASKETRY_POSTGRES_PORT` | `5432` | Compose and every service |
| `BASKETRY_DB_USERNAME` | `identity` / `merchant` / `payment` / `inventory` / `catalog` / `orders` / `promotion` | each service |
| `BASKETRY_DB_PASSWORD` | `local-dev-only-identity` / `local-dev-only-merchant` / `local-dev-only-payment` / `local-dev-only-inventory` / `local-dev-only-catalog` / `local-dev-only-orders` / `local-dev-only-promotion` | each service |
| `BASKETRY_IDENTITY_DB_PASSWORD`, `BASKETRY_MERCHANT_DB_PASSWORD`, `BASKETRY_PAYMENT_DB_PASSWORD`, `BASKETRY_INVENTORY_DB_PASSWORD`, `BASKETRY_CATALOG_DB_PASSWORD`, `BASKETRY_ORDER_DB_PASSWORD`, `BASKETRY_PROMOTION_DB_PASSWORD` | the same seven values | Compose's init script |
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
changing a Compose password, run `docker compose down -v`.

**A Postgres volume older than a service's database** has no database or user
for it, so that service cannot connect on Postgres. `payment`, `inventory`,
`catalog`, `orders` (order-service's; `order` is an SQL keyword) and
`promotion` were each added after the first volumes were made. Keeping all
data, create whichever are missing in the running container, from PowerShell at
the repo root; this does what the init script would have done, and skips a
database that already exists (if you overrode a `BASKETRY_<NAME>_DB_PASSWORD`,
use your password instead of the default):

```
foreach ($name in 'payment', 'inventory', 'catalog', 'orders', 'promotion') {
    $exists = docker compose exec -T postgres psql -U postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '$name'"
    if ($exists -ne '1') {
        docker compose exec -T postgres psql -U postgres -v ON_ERROR_STOP=1 -c "CREATE ROLE $name LOGIN PASSWORD 'local-dev-only-$name';" -c "CREATE DATABASE $name OWNER $name;" -c "REVOKE CONNECT, TEMPORARY ON DATABASE $name FROM PUBLIC;"
    }
}
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
- **An idle service on H2 keeps its database**: the URL ends in
  `DB_CLOSE_DELAY=-1`, so H2 does not drop it when every connection is closed.
  Only a restart empties it.
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

`scripts/e2e-order.ps1` runs the checkout saga across all six services. Every
command it runs is one of this README's, run as shown: the REST commands of
"Trying catalog-service's REST API by hand" onboard two merchants (the second
with another name, email and a 7.50 % rate), create three products, add stock
and change a price; inventory-service's `GET /stock` line reads the stock; the
commands of "Trying order-service by hand" register the buyer, check out, read
the order as buyer and Admin, list orders, read `order.succeeded`,
`order.failed` and the dead-letter topic, produce the malformed result and
describe the consumer groups; the consumer lines of the payment-service and
inventory-service sections, with only the topic swapped, read the results and
the refund and release requests. Its scenarios, and what proves each:

- **Two merchants, an approved card:** `SUCCEEDED` in the buyer and Admin
  views; the stock down by the quantities; two `charged` and two `reserved`
  records; one `order.succeeded` with each merchant's rate snapshot and exact
  commission (0.225 rounds to 0.23: HALF_UP, where HALF_EVEN would give 0.22).
- **A replay and a price change:** the same key and body answers 200 with the
  same `Location`, and the order count and `charged` records do not change;
  after the merchant's price change, the old `expectedTotal` gets 409
  `PRICE_CHANGED` and nothing is stored.
- **A decline:** both legs `DECLINED` 51, both lines released, the stock back,
  one `order.failed` with the two reasons, no refund requested.
- **One product short of stock:** both merchants charged, the reservation
  failed `INSUFFICIENT_STOCK`, both charges refunded, the other line released.
  `FAILING` is proven from the evidence (`decidedAt` before `completedAt`, the
  refund and release requests), because it lasts milliseconds.
- **A malformed `charged` result** ahead of the real one for the same order (the
  same key): the dead letter has its exact bytes and our two headers, and the
  real result behind it is applied.
- **payment-service stopped past the deadline:** both charge legs `TIMED_OUT`
  (`NO_RESULT`), the lines released, `FAILED` with an `order.failed`. Then
  payment-service starts, the late charges arrive, and the order is reopened:
  proven by a second `order.failed` with another `eventId`, the refund requests
  and their `refunded` results between the two, and `completedAt` set again.
  The buyer view is polled every 200 ms meanwhile; whether it caught `FAILING`
  is printed, never a failure.
- **inventory-service stopped past the deadline:** payment charges; at the
  deadline the line is `TIMED_OUT` and the order `FAILING`, and it stays
  `FAILING` while inventory-service is down (read 10 times over 5 s); the
  charge is refunded and the line's release is requested while it is down
  (both shown by `occurredAt`, before the restart). Once inventory-service is
  back, the line is `RELEASED`, the stock is back where it started, and the
  order ends `FAILED`. inventory-service answers the late reserve once:
  `RELEASED_BEFORE_RESERVE`, from the tombstone its release left (or `reserved`
  and then released, if it happens to read the reserve first; the script prints
  which).
- **With `-Postgres`, order-service force-killed** with an order whose requests
  are sent: payment-service and inventory-service answer while it is down (they
  are stopped before the checkout and started once it is down, because their
  results otherwise arrive within a second, before any kill); it
  is restarted after the order's deadline, and within its 60 s startup grace the
  listeners apply the queued results: `SUCCEEDED`, decided after the deadline.
  The consumer group's rejoin time is printed, and the run fails above 50 s;
  here the old members' 45 s session has expired before the restart, so it is
  short.
- **With `-Postgres`, the same, restarted within about 5 s of the kill**
  (payment-service and inventory-service start with it): the new members wait
  for the old ones' session to time out. The rejoin time is printed, and the
  run fails above 50 s; the order ends `SUCCEEDED`, decided after its deadline,
  which passes during the wait.

Last, it checks that no leg is `STUCK`, that every saga topic exists although
nobody created one by hand, that the dead-letter topic holds only the one
record, and that no service log contains the card number used, the internal
key, the fingerprint key, a database password or a bearer token of the run.
With `-Postgres` also the legs, the outbox and `payment_transaction` rows of the
first orders (`APPROVED`, then `REFUNDED`, with only the last 4 digits), and that
the card number is in no stored value of `orders` or `payment`. It does not
cover, live: one merchant declining while another is charged (the mock decides
by card number and an order has one card; the shortage scenario covers the
refund path, the tests the rest); a leg going `STUCK` after 3 resends
(`SagaTimerTests` only); and a late `reserved` on a `TIMED_OUT` line
(inventory-service has answered the late reserve `RELEASED_BEFORE_RESERVE` in
every run; `SagaResultTableTests` only).

order-service runs with `--basketry.order.saga-deadline=30s`,
`--basketry.order.timer-interval=1s` and
`--basketry.order.compensation.resend-interval=10s` on its command line (the
startup grace stays 60 s), and the script waits until order-service's consumer
group is `Stable` with its six members before the first scenario and before
each one that waits for the deadline. inventory-service runs on an H2 file in
`build\e2e\order`, so its restart keeps its stock. Every service it starts gets
`JAVA_TOOL_OPTIONS=-Xmx384m`, and it runs `gradlew --stop` before building;
with Docker Desktop already running, the run needs about 4 GB of free memory (the six services peaked at 2.4 GB together; Kafka, Redis, Postgres and the console consumers run in Docker Desktop, whose VM peaked at 3.1 GB).

```
powershell -ExecutionPolicy Bypass -File scripts\e2e-order.ps1
powershell -ExecutionPolicy Bypass -File scripts\e2e-order.ps1 -Postgres
```

`-Postgres` runs order-service, payment-service and inventory-service, the three
it restarts, on Postgres (the others stay on H2: they are never restarted) and
adds the two force-kills of order-service. It needs ports 8080 to 8085, 9092 and
6379 free (`BASKETRY_REDIS_PORT` instead of 6379 if set; with `-Postgres` also
5432, or `BASKETRY_POSTGRES_PORT`), uses its own Compose project
(`basketry-e2e-order`; the ports are the same, so still run one script at a
time), and exits with 6 if this README no longer has the exact commands it runs.
A run takes about 6 minutes, 8 with `-Postgres` (measured: 339 s and 472 s, the jars already built; a fresh build adds about a minute).
Its logs go to `build\e2e\order`.
