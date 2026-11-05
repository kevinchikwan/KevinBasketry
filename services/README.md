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
