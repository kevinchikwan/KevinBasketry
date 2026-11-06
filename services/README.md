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
on the Compose network to `kafka:29092`. No test needs Docker, and
merchant-service also runs without Kafka: `PATCH` still works and the events
wait in its outbox until the broker is back.

## Restarting with H2

With in-memory H2, restarting only merchant-service resets its merchant ids and
versions, while Kafka and a running consumer keep the old ones. A consumer would
then ignore the new events, because their versions look old. So restart both
services and run `docker compose down` together. This goes away with Postgres.
