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
| `payment-service` | 8084 | Not started |
| `order-service` | 8085 | Not started |
| `promotion-service` | 8086 | Not started |
| `conversation-service` | 8087 | Not started |
| `platform-service` | 8088 | Not started |

Update this table's status column as each service is built — this file
exists so the repo's progress is visible at a glance without opening
every folder. The ports are fixed so the services can run side by side
locally; each service sets its own in `application.yml`.

## Kafka for local development

Kafka runs from the `docker-compose.yml` at the repo root, with Docker Desktop
running:

```
docker compose up -d --wait    # start; returns once the broker is healthy
docker compose down            # stop; also deletes the Kafka data
```

Services run from IntelliJ connect to `localhost:9092`, and other containers
on the Compose network to `kafka:29092`. No test needs Docker, and both services
also run without Kafka. merchant-service: `PATCH` still works and the events wait
in its outbox until the broker is back. identity-service: login and every other
request still work, and the status consumer catches up once the broker is back.

## Restarting with H2

With in-memory H2, restart **all** services and run `docker compose down`
together, whichever one you meant to restart. Restarting only merchant-service
resets its merchant ids and versions, while Kafka and a running consumer keep the
old ones, so the consumer would ignore new events because their versions look
old. Restarting only identity-service loses every account, while merchant-service
still shows its merchants as `LINKED`, and they cannot be linked again. This goes
away with Postgres.

## End-to-end check

`scripts/e2e-merchant-status.ps1` runs the whole `merchant.status.changed` chain
on a real broker and real processes. From PowerShell at the repo root, with
Docker running:

```
powershell -ExecutionPolicy Bypass -File scripts\e2e-merchant-status.ps1
```

It needs ports 8080, 8082 and 9092 free, and refuses to start otherwise. It stops
only what it started, and its exit codes are listed at the top of the script.
Service logs go to `build\e2e`.
