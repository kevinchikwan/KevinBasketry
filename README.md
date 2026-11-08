# Basketry

A multi-merchant marketplace built as independently deployable Spring Boot
microservices, with an event-driven (Kafka) all-or-nothing checkout saga,
Redis-backed stock concurrency control, three React/TypeScript front-ends
(Buyer, Merchant, Admin), and a self-managed Kubernetes deployment on AWS.

Built as a portfolio project to demonstrate hands-on experience across the
full delivery lifecycle: requirements, architecture design, implementation,
containerization, orchestration, cloud infrastructure, and CI/CD — not just
application code in isolation.

**Status: early development.** Design is complete and documented below;
services are being implemented one at a time. This README will be updated
as each piece lands — it's describing where the project is, not presenting
unfinished work as done.

Built so far: `identity-service` and `merchant-service`, including the
`merchant.status.changed` event between them, from the outbox to the consumer.
In progress: `payment-service`, whose charge and refund logic is done and whose
Kafka side comes next.
[`services/README.md`](services/README.md) has the status of every service and
how to run the end-to-end check of that event.

## Start here

- [`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md) — functional and
  non-functional requirements, organized by Buyer / Merchant / Admin,
  including the key design decisions (all-or-nothing checkout, commission
  model, coupons) and why each was chosen over the alternative.
- [`docs/DESIGN.md`](docs/DESIGN.md) — service decomposition, data
  ownership per service, API contracts, the Kafka event catalog, and the
  technology stack with reasoning for each choice.
- [`docs/checkout-saga.mermaid`](docs/checkout-saga.mermaid) — sequence
  diagram for the checkout saga, happy path and compensating-rollback path.

## Architecture at a glance

| Service | Owns |
|---|---|
| `identity-service` | Accounts, auth, buyer/merchant linkage |
| `catalog-service` | Products, categories |
| `merchant-service` | Merchant profile, onboarding, commission rate, KYC |
| `inventory-service` | Stock, reserved via Redis at checkout time |
| `payment-service` | Card/QR authorization simulator |
| `order-service` | Checkout orchestration, saga coordination |
| `promotion-service` | Coupons |
| `conversation-service` | Order-scoped buyer↔merchant message threads |
| `platform-service` | Admin read-model: GMV, commission, order stats |

Full detail, including why each service owns what it owns, is in
`docs/DESIGN.md`.

## Stack

Java 25 (LTS) · Spring Boot 4.1 · Apache Kafka · Redis · Docker ·
Kubernetes (k3s) · AWS · React + TypeScript

## License

MIT — see [`LICENSE`](LICENSE).
