# Services

Each subfolder is one independently deployable Spring Boot service. See
[`../docs/DESIGN.md`](../docs/DESIGN.md) for what each one owns and how
they talk to each other.

| Service | Local port | Status |
|---|---|---|
| `identity-service` | 8080 | In progress |
| `catalog-service` | 8081 | Not started |
| `merchant-service` | 8082 | In progress |
| `inventory-service` | 8083 | In progress |
| `payment-service` | 8084 | In progress |
| `order-service` | 8085 | Not started |
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

Redis holds only inventory-service's stock counters, which mirror its database.
It keeps nothing: no snapshots, no append-only file, no volume. Every start is
empty, and inventory-service reloads the counters from its database at startup,
every 5 minutes, and whenever one is missing.

All four services also run without Kafka. merchant-service:
`PATCH` still works and the events wait in its outbox until the broker is back.
identity-service: login and every other request still work, and the status
consumer catches up once the broker is back. payment-service: the request
listeners connect once the broker is back, and results wait in its outbox
meanwhile. inventory-service: the REST API still works, the request listeners
connect once the broker is back, and results wait in its outbox meanwhile. It
also runs without Redis: reservations are then
decided by its database alone, still correctly, and it logs one WARN per outage.
Without their database the services do not start.

## Sending payment-service a request by hand

order-service will own the two request topics and does not exist yet, and the
broker does not create topics by itself, so create them once (they survive
`docker compose down`, not `down -v`). payment-service creates its own result
and dead-letter topics. From the repo root, with Compose and payment-service
running:

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

catalog-service does not exist yet, so stock rows are created through the
internal endpoint it will use. Start identity-service, merchant-service and
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

As for payment-service, order-service will own the two request topics, so
create them once; inventory-service creates its own result and dead-letter
topics. Start inventory-service with Kafka (Redis optional) and give a product
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
  (8080, 8082, 8083 or 8084); stop it if there is.

| Variable | Default (local only) | Used by |
|---|---|---|
| `BASKETRY_POSTGRES_HOST` | `localhost` | every service |
| `BASKETRY_POSTGRES_PORT` | `5432` | Compose and every service |
| `BASKETRY_DB_USERNAME` | `identity` / `merchant` / `payment` / `inventory` | each service |
| `BASKETRY_DB_PASSWORD` | `local-dev-only-identity` / `local-dev-only-merchant` / `local-dev-only-payment` / `local-dev-only-inventory` | each service |
| `BASKETRY_IDENTITY_DB_PASSWORD`, `BASKETRY_MERCHANT_DB_PASSWORD`, `BASKETRY_PAYMENT_DB_PASSWORD`, `BASKETRY_INVENTORY_DB_PASSWORD` | the same four values | Compose's init script |
| `BASKETRY_POSTGRES_ADMIN_PASSWORD` | `local-dev-only-admin` (user `postgres`) | Compose |
| `BASKETRY_REDIS_HOST` | `localhost` | inventory-service |
| `BASKETRY_REDIS_PORT` | `6379` | Compose and inventory-service |
| `BASKETRY_REDIS_PASSWORD` | empty (no password locally) | inventory-service |

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

## Looking at the data

**psql**, for everyone, while the Compose Postgres runs:

```
docker compose exec postgres psql -U identity identity
docker compose exec postgres psql -U merchant merchant
docker compose exec postgres psql -U payment payment
docker compose exec postgres psql -U inventory inventory
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
`stock_reservation`, `outbox_event` (inventory), and `flyway_schema_history` in
each. For inventory-service, use `inventory` as user and database and
`local-dev-only-inventory` as password. `payment_transaction` holds only the
last 4 digits of a card number, never the full number.

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

## Restarting

Kafka and Postgres are kept or wiped together, because each depends on the
other: `docker compose down` keeps both, `docker compose down -v` wipes both,
also the Postgres data when you did not pass `--profile postgres`. Never wipe
only one of them: without Kafka's data, events already sent but not yet read by
identity-service are lost; without Postgres's, the version problem below comes
back.

- **On Postgres**, each service can be restarted on its own.
- **Redis** can be restarted or wiped at any time: it holds nothing that
  inventory-service cannot reload from its database.
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
  an old reservation stores a tombstone and gives nothing back.

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

inventory-service's `postgresTest` also starts one Testcontainers Redis (the
Compose image, with the same no-persistence flags) and runs every test against
it with the real Lua scripts. Its default `test` uses an in-memory fake counter
instead, which passes the same contract tests. Tests tagged `redis` run only in
`postgresTest`: the contract against Redis, 16 threads taking the last unit 100
times over, and a check that the run really is on Redis 8.10.3.

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
