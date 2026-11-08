# Services

Each subfolder is one independently deployable Spring Boot service. See
[`../docs/DESIGN.md`](../docs/DESIGN.md) for what each one owns and how
they talk to each other.

| Service | Local port | Status |
|---|---|---|
| `identity-service` | 8080 | In progress |
| `catalog-service` | 8081 | Not started |
| `merchant-service` | 8082 | In progress |
| `inventory-service` | 8083 | Not started |
| `payment-service` | 8084 | In progress |
| `order-service` | 8085 | Not started |
| `promotion-service` | 8086 | Not started |
| `conversation-service` | 8087 | Not started |
| `platform-service` | 8088 | Not started |

Update this table's status column as each service is built — this file
exists so the repo's progress is visible at a glance without opening
every folder. The ports are fixed so the services can run side by side
locally; each service sets its own in `application.yml`.

## Kafka and Postgres for local development

Both run from the `docker-compose.yml` at the repo root, with Docker Desktop
running. Postgres starts only with the Compose profile `postgres`:

```
docker compose up -d --wait                     # Kafka only (services on H2)
docker compose --profile postgres up -d --wait  # Kafka and Postgres
docker compose down                             # stop everything, keep all data
docker compose down -v                          # stop everything, wipe all data
```

Services run from IntelliJ connect to Kafka at `localhost:9092` (other
containers on the Compose network to `kafka:29092`) and to Postgres at
`localhost:5432`. Both listen on 127.0.0.1 only. If port 5432 is taken, set
`BASKETRY_POSTGRES_PORT` (for example to 5433) for Compose **and** the services.

identity-service and merchant-service also run without Kafka. merchant-service:
`PATCH` still works and the events wait in its outbox until the broker is back.
identity-service: login and every other request still work, and the status
consumer catches up once the broker is back. payment-service does not use Kafka
yet. Without their database the services do not start.

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
  (8080, 8082 or 8084); stop it if there is.

| Variable | Default (local only) | Used by |
|---|---|---|
| `BASKETRY_POSTGRES_HOST` | `localhost` | every service |
| `BASKETRY_POSTGRES_PORT` | `5432` | Compose and every service |
| `BASKETRY_DB_USERNAME` | `identity` / `merchant` / `payment` | each service |
| `BASKETRY_DB_PASSWORD` | `local-dev-only-identity` / `local-dev-only-merchant` / `local-dev-only-payment` | each service |
| `BASKETRY_IDENTITY_DB_PASSWORD`, `BASKETRY_MERCHANT_DB_PASSWORD`, `BASKETRY_PAYMENT_DB_PASSWORD` | the same three values | Compose's init script |
| `BASKETRY_POSTGRES_ADMIN_PASSWORD` | `local-dev-only-admin` (user `postgres`) | Compose |

The defaults are for local development only, and a service logs a WARN at
startup when it uses one. Each service has its own database and user, and no
user can connect to another service's database. The init script
(`docker/postgres/init`) runs only when the Postgres volume is empty: after
changing it or a Compose password, run `docker compose down -v`.

**The `payment` database is new.** A Postgres volume created before it has no
`payment` database or user, so payment-service cannot connect on Postgres. Run
`docker compose down -v` once, then start Compose again. That **also wipes
Kafka's data**, not only Postgres's (see Restarting): every service starts from
empty, so restart the ones running on H2 too.

## Looking at the data

**psql**, for everyone, while the Compose Postgres runs:

```
docker compose exec postgres psql -U identity identity
docker compose exec postgres psql -U merchant merchant
docker compose exec postgres psql -U payment payment
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
`payment_transaction`, `outbox_event` (payment), and `flyway_schema_history` in
each. `payment_transaction` holds only the last 4 digits of a card number, never
the full number.

## Restarting

Kafka and Postgres are kept or wiped together, because each depends on the
other: `docker compose down` keeps both, `docker compose down -v` wipes both,
also the Postgres data when you did not pass `--profile postgres`. Never wipe
only one of them: without Kafka's data, events already sent but not yet read by
identity-service are lost; without Postgres's, the version problem below comes
back.

- **On Postgres**, each service can be restarted on its own.
- **On H2**, every restart empties that service's database. Restart **all**
  services and run `docker compose down -v` together, whichever one you meant
  to restart. Restarting only merchant-service resets its merchant ids and
  versions, while Kafka and a running consumer keep the old ones, so the
  consumer would ignore new events because their versions look old. Restarting
  only identity-service loses every account, while merchant-service still shows
  its merchants as `LINKED`, and they cannot be linked again.

## Tests

```
.\gradlew.bat build          # every test on H2; no Docker needed
.\gradlew.bat postgresTest   # every test again on PostgreSQL 17.11; needs Docker
```

`postgresTest` (also per service, e.g. `:merchant-service:postgresTest`) starts
one Testcontainers Postgres for the run and points every test at it (the tests
do not use the `postgres` profile, only its database). Tests tagged `postgres`
run only there: they check that the run really is on Postgres, that each
service starts with its real `postgres` profile, and the 5 s row-lock timeout.

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
