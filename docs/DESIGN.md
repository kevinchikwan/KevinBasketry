# Design — Basketry

**Status:** Draft v1 for review
**Depends on:** `REQUIREMENTS.md`
**Purpose:** Service decomposition, data ownership, API contracts, and
event catalog, written before implementation begins.

---

## 1. Architecture overview

Three separate front-end applications, each its own URL, each its own
deployment — not one app with a role switcher:

| App | URL (shape) | Who can reach it |
|---|---|---|
| Buyer marketplace | `app.basketry.example` | Public — includes self-registration |
| Merchant portal | `merchant.basketry.example` | Login only — no sign-up page. An account must already exist, created by Admin onboarding (FR-A1) |
| Admin console | `admin.basketry.example` | Login only, seeded account(s), not linked from either public site |

This isn't just a routing detail — it means `identity-service`'s public
`POST /accounts/register` endpoint must only ever create a Buyer
capability. There is no equivalent public endpoint for creating a
Merchant login; the only way a `merchantActive` account comes into
existence is via `merchant-service`'s onboarding flow calling
`identity-service` internally (see Section 3, `merchant-service` API).
That's an authorization rule worth testing explicitly, not just a UI
omission — a person hitting the API directly should not be able to grant
themselves merchant status.

Nine Spring Boot backend services, each independently deployable, each
owning its own data (no shared database, no cross-service foreign keys —
services reference each other only by ID and, where needed, by calling
the owning service's API):

| Service | Owns | Talks to |
|---|---|---|
| `identity-service` | Accounts, auth, buyer/merchant linkage | — (foundational; everything else validates tokens issued here) |
| `catalog-service` | Products, categories | `merchant-service` (merchant active/inactive), `inventory-service`, Redis (read-through cache) |
| `merchant-service` | Merchant profile, onboarding, commission rate, KYC | `identity-service` (creating merchant login) |
| `inventory-service` | Stock counts | consumes `OrderSucceeded` events, Redis (atomic decrement) |
| `payment-service` | Authorization simulator (existing) | — (no outbound dependencies) |
| `order-service` | Cart→checkout orchestration, saga coordination | `catalog-service`, `promotion-service`, publishes/consumes checkout events |
| `promotion-service` | Coupons | consumes `OrderSucceeded` / `OrderFailed` events |
| `conversation-service` | Order-scoped message threads | `order-service` (validating order/merchant references) |
| `platform-service` | Admin read-model: GMV, commission, order stats | consumes `OrderSucceeded` events (CQRS-style projection) |

Synchronous (REST) vs asynchronous (Kafka event) is deliberately mixed:

- **Synchronous** where a request needs an answer before it can proceed
  (e.g. checkout needs to know a coupon is valid *now*, browsing needs
  current stock *now*).
- **Asynchronous (Kafka)** where the consumer doesn't need to block the
  producer, and where decoupling is the whole point — inventory
  decrementing, commission/GMV projection, and the checkout saga's own
  charge/decline/refund choreography.

Kafka runs locally via Docker for development. Deployment target for
Kafka is a self-managed broker inside the same k3s cluster as the
services (see Section 7) — avoids AWS MSK's per-broker cost entirely,
consistent with NFR-5.

## 2. Data model, by service

Only fields relevant to design decisions are listed — full field lists
happen at implementation time.

### identity-service
```
Account
  id (PK)
  email (unique)
  passwordHash
  buyerActive: boolean          -- can this account act as a buyer
  merchantId: nullable(Long)    -- FK to merchant-service's Merchant, if linked
  merchantActive: boolean       -- separate from buyerActive; see Section 4 of REQUIREMENTS.md
  createdAt
```
Admin is not an `Account` row — a small, separate `AdminAccount` table in
`identity-service`'s own schema. **No API endpoint creates it, ever** — it
exists only via a database migration/seed script (e.g. Flyway) run once
at first deploy. There is deliberately no `POST /admin/register` and no
"promote to admin" endpoint anywhere in the system — the only way to add
or change an admin account is to edit the seed script and redeploy, which
is the point: the attack surface for "how does someone become platform
admin" is zero application code, only deploy-time access to the
migration scripts/infrastructure.

### merchant-service
```
Merchant
  id (PK)
  merchantCode (unique, human-readable, e.g. MCH-001)
  name, category
  kycVerified: boolean
  active: boolean
  commissionRate: BigDecimal    -- percentage, e.g. 5.00
```

### catalog-service
```
Product
  id (PK)
  merchantId                    -- reference only, not a DB-level FK (different service's DB)
  name, description, category
  price: BigDecimal
  active: boolean
```
`catalog-service` caches `merchantActive` locally (denormalized), updated
by consuming a `MerchantStatusChanged` event from `merchant-service`
rather than calling out synchronously on every product-browse request.

**Redis cache:** `GET /products` and `GET /products?merchantCode=` reads
go through a cache-aside pattern — check Redis first (key
`catalog:products:{merchantCode|"all"}`), fall through to Postgres on a
miss, populate Redis with a short TTL (e.g. 60s). Cache is explicitly
invalidated (not just left to expire) on product create/update/deactivate
and on a consumed `MerchantStatusChanged` event — TTL alone would let a
merchant's edit be invisible to buyers for up to the TTL window, which is
a real (if minor) correctness gap worth naming rather than silently
accepting.

### inventory-service
```
StockLevel
  productId (PK, reference only)
  quantity: int
```
Postgres remains the source of truth for `quantity` (durable, survives a
Redis restart). Redis holds a mirrored counter per product
(`inventory:stock:{productId}`) used only for the atomic
check-and-decrement at checkout time.

**Concurrency design:** stock is **reserved at checkout initiation**, in
parallel with the per-merchant payment authorizations (see Section 4) —
not deferred until `OrderSucceeded`. This closes the race where two
buyers could both pass authorization against the same last unit before
either order finishes. The reservation runs a small Lua script via
`EVAL` that atomically checks `quantity > requestedQty` and decrements in
one round trip per product — this is what actually prevents overselling;
a non-atomic "read then write" from the application layer would have a
race window between the check and the decrement. Postgres is updated
asynchronously afterward to stay consistent with Redis, with Redis as the
concurrency-safe fast path and Postgres as the durable record. If Redis
and Postgres ever disagree (e.g. after a Redis restart with no
persistence configured), Postgres wins — a startup reconciliation step
reloads Redis's counters from Postgres.

**Compensating release:** because stock is reserved eagerly, it becomes a
third leg of the saga alongside the per-merchant charges (Section 4) — if
the order ultimately fails for *any* reason (a merchant declined, or a
different product in the same cart was out of stock), every product whose
stock was successfully reserved must be released back, exactly as a
charged merchant must be refunded. `inventory-service` treats
`StockReserved` and `MerchantCharged` symmetrically: both are provisional
outcomes that only become final once `order-service` confirms the whole
order succeeded.

### payment-service
Unchanged from the existing implementation: `Transaction`,
`TransactionStatus` (ISO 8583-style codes), `authorize` / `capture` /
`refund` / `void`.

### order-service
```
Order
  id (PK), orderReference (unique)
  buyerAccountId                -- reference only
  status: PENDING | SUCCEEDED | FAILED
  couponCode: nullable
  totalAmount: BigDecimal
  createdAt

OrderItem
  id (PK)
  orderId (FK, same service/DB)
  productId, merchantId          -- references only, snapshotted at checkout
  quantity, unitPrice
  merchantChargeId: nullable     -- correlates to the saga's per-merchant charge attempt
```
`Order.status` is intentionally simpler than the old `COMPLETE` /
`PARTIALLY_PAID` / `FAILED` — since the all-or-nothing decision means
there's no partial state to represent (see `REQUIREMENTS.md` Section 10).

### promotion-service
```
Coupon
  id (PK)
  code (unique)
  scope: MERCHANT | PLATFORM
  merchantId: nullable           -- set when scope = MERCHANT
  discountType: PERCENTAGE | FIXED
  discountValue: BigDecimal
  expiryDate
  active: boolean
```
Coupon "used" state is derived from consumed `OrderSucceeded` events, not
a flag set optimistically at checkout time — this is what makes FR
"a coupon in a failed order is not consumed" correct by construction
rather than by remembering to roll it back.

### conversation-service
```
Thread
  id (PK)
  orderId, merchantId, buyerAccountId   -- references only
  status: OPEN | RESOLVED

Message
  id (PK)
  threadId (FK, same service/DB)
  senderType: BUYER | MERCHANT
  body, sentAt
```

### platform-service
```
OrderSummaryProjection      -- built entirely from consumed events, not queried live from order-service
  orderId, merchantId, amount, commission, succeededAt
```

## 3. API contracts (representative endpoints)

Full OpenAPI specs happen at implementation time; this is the contract
shape for review.

**identity-service**
- `POST /accounts/register` — buyer self-registration **only**; this
  endpoint never accepts or sets merchant status, regardless of request
  body — enforced server-side, not just hidden client-side
- `POST /auth/login` → JWT (shared across all three front-ends; the JWT's
  claims determine which capabilities the holder has, not which URL they
  logged in from)
- `GET /accounts/{id}` (self or Admin)
- `PATCH /accounts/{id}/buyer-status` (Admin)
- `PATCH /accounts/{id}/merchant-status` (Admin)
- `POST /accounts/{id}/merchant-link` (internal-only — called by
  `merchant-service` during onboarding, not exposed to the public Buyer
  or Merchant front-ends; secured separately, e.g. service-to-service
  auth rather than a user JWT)
- *(No endpoint for creating an Admin account exists anywhere in this
  service, or any other. See Section 2 — Admin is seeded via migration.)*

**merchant-service**
- `POST /merchants` (Admin — onboarding, also triggers merchant account
  linkage in identity-service)
- `GET /merchants`, `GET /merchants/{code}`
- `PATCH /merchants/{code}` (Admin — commission rate, active flag)

**catalog-service**
- `GET /products?merchantCode=`
- `POST /products` (Merchant, own products only)
- `PATCH /products/{id}` (Merchant, own products only)

**order-service**
- `POST /orders/checkout` — starts the saga (see Section 4)
- `GET /orders?buyerAccountId=` / `?merchantId=`
- `GET /orders/{reference}`

**promotion-service**
- `POST /coupons` (Merchant — scope=MERCHANT, or Admin — scope=PLATFORM)
- `GET /coupons/validate?code=&merchantIds=` — used synchronously during
  checkout, before the saga starts

**conversation-service**
- `POST /orders/{reference}/threads/{merchantCode}/messages`
- `PATCH /threads/{id}/resolve`

**platform-service**
- `GET /platform/stats` — GMV, commission, order counts

## 4. Checkout saga — sequence

See `checkout-saga.mermaid` for the full diagram. Summary:

**Happy path:**
1. Buyer → `order-service`: `POST /orders/checkout`
2. `order-service` → `promotion-service`: validate coupon (sync, if present)
3. `order-service` creates `Order` in `PENDING`, then publishes, in
   parallel: one `MerchantChargeRequested` event per merchant in the
   cart, **and** one `StockReserveRequested` event per product line
4. `payment-service` consumes each charge request, authorizes+captures,
   publishes `MerchantCharged` (or `MerchantDeclined`)
5. `inventory-service` consumes each reservation request, atomically
   decrements via the Redis Lua script (Section 2), publishes
   `StockReserved` (or `StockReservationFailed` if insufficient quantity)
6. `order-service` waits for a result for **every** merchant charge and
   **every** stock reservation in the order — not just one or the other.
   If **all** are positive (`MerchantCharged` + `StockReserved`): sets
   `Order.status = SUCCEEDED`, publishes `OrderSucceeded`
7. `promotion-service` and `platform-service` consume `OrderSucceeded`
   independently and update their own state. `inventory-service` does
   **not** need to react further — the stock was already decremented at
   reservation time in step 5, which is the point of reserving eagerly

**Decline path (compensating refund + stock release):**
1. Same as above through step 6
2. `order-service` sees at least one `MerchantDeclined` or
   `StockReservationFailed`
3. For every merchant that already returned `MerchantCharged`,
   `order-service` publishes `MerchantRefundRequested`. For every product
   that already returned `StockReserved`, it publishes
   `StockReleaseRequested` — both compensating actions fire together,
   not sequentially, since they're independent of each other
4. `payment-service` consumes refund requests and confirms
   `MerchantRefunded`; `inventory-service` consumes release requests,
   atomically increments the Redis counter back, and confirms
   `StockReleased`
5. Once **all** outstanding compensations (refunds and releases) are
   confirmed, `order-service` sets `Order.status = FAILED`, publishes
   `OrderFailed`
6. No consumer of `OrderFailed` needs to do anything further (no coupon
   consumption, no platform projection) — the compensations in steps 3–4
   already returned the system to its pre-checkout state, which is
   precisely why the all-or-nothing decision keeps the failure path
   conceptually simple even though it now has two kinds of compensation
   (refund and release) instead of one

**Design note:** treating stock reservation as a full saga leg (with its
own compensating action) rather than a simple "decrement on success" is
more moving parts, but it's the honest consequence of choosing to reserve
stock eagerly (Section 2) instead of only decrementing after the whole
order is known to succeed. The alternative — decrement only on
`OrderSucceeded` — would have been simpler here but reopens the
overselling race window this design exists to close.

## 5. Event catalog (Kafka)

| Topic | Producer | Consumers | Payload (key fields) |
|---|---|---|---|
| `merchant.status.changed` | merchant-service | catalog-service | merchantId, active |
| `order.merchant-charge.requested` | order-service | payment-service | orderId, merchantId, amount |
| `order.merchant.charged` | payment-service | order-service | orderId, merchantId, transactionRef |
| `order.merchant.declined` | payment-service | order-service | orderId, merchantId, reasonCode |
| `order.merchant-refund.requested` | order-service | payment-service | orderId, merchantId, transactionRef |
| `order.merchant.refunded` | payment-service | order-service | orderId, merchantId |
| `order.stock-reserve.requested` | order-service | inventory-service | orderId, productId, quantity |
| `order.stock.reserved` | inventory-service | order-service | orderId, productId |
| `order.stock.reservation-failed` | inventory-service | order-service | orderId, productId, reasonCode |
| `order.stock-release.requested` | order-service | inventory-service | orderId, productId, quantity |
| `order.stock.released` | inventory-service | order-service | orderId, productId |
| `order.succeeded` | order-service | promotion-service, platform-service | orderId, items[], couponCode |
| `order.failed` | order-service | *(none currently — reserved for future audit/notification use)* | orderId, reasonCode |

Note `inventory-service` is no longer a consumer of `order.succeeded` —
stock was already committed at reservation time (Section 2), so there's
nothing left for it to do on success. It only acts on the
reserve/release request topics, which keeps its responsibility narrow:
react to explicit requests, not infer action from a general-purpose
success event meant for other services.

Each event carries the `orderId` (and `merchantId`/`productId` where
relevant) as a natural idempotency key — consumers must treat redelivery
as a no-op if they've already processed that combination, since Kafka's
at-least-once delivery means duplicates are expected, not exceptional.

## 6. Open questions for implementation

- Exact idempotency-key strategy on `payment-service`'s consumer side
  (dedupe table vs. relying on `PaymentService`'s existing reference
  generation) — decide when we build `payment-service`'s Kafka listener.
- Whether `order-service` needs its own outbox table to guarantee
  `MerchantChargeRequested` is published exactly once per DB commit
  (transactional outbox pattern) — worth doing properly rather than
  skipping, since it's a genuinely common real-world gap; decide before
  writing `order-service`'s checkout handler.

## 7. Technology stack & deployment

This project is explicitly built to demonstrate hands-on ability across a
specific stack, not just to solve the marketplace problem in whatever way
was fastest — worth stating plainly since it explains some choices (e.g.
Kubernetes) that a smaller/cheaper deployment could have skipped.

| Layer | Choice | Why |
|---|---|---|
| Language | **Java 25 (LTS)** | Current LTS as of 2026 (Spring Boot 3.5, previously assumed, reached OSS end-of-life 30 June 2026). Records for DTOs, sealed interfaces + pattern matching for the saga's event types, virtual threads for the I/O-heavy inter-service calls — current, interview-relevant Java |
| Framework | **Spring Boot 4.1** | Current stable line, built on Spring Framework 7; officially supports Java 25. Consistent across all 9 services |
| Messaging | **Apache Kafka** | Checkout saga choreography (Section 4/5) |
| Caching / atomic ops | **Redis** | Two specific uses, not general-purpose caching everywhere: (1) atomic stock decrement in `inventory-service` to prevent overselling under concurrent checkouts, (2) read-through cache for `catalog-service`'s product listings, which are read far more often than written |
| Containerization | **Docker** | Every service ships a Dockerfile; Kafka, Redis, and per-service Postgres run via Docker Compose for local dev |
| Orchestration | **Kubernetes (k3s)** | Manifests for Deployments, Services, ConfigMaps, Secrets, and Ingress — real K8s objects, portable to a managed cluster (EKS) later without rewriting |
| Cloud | **AWS** | Single free-tier-eligible EC2 instance running k3s; see below |
| Frontend | **React + TypeScript** | Three separate apps — Buyer, Merchant, Admin (Section 1) |

### Deployment shape

- One EC2 instance (free-tier eligible for the first 12 months of an AWS
  account) runs a single-node **k3s** cluster — a lightweight, fully
  conformant Kubernetes distribution, not a toy subset. All 9 backend
  services, Kafka (and Zookeeper/KRaft), and the 3 frontend apps run as
  pods on this one node.
- This is a deliberate trade-off, recorded here so it isn't mistaken for
  an oversight: a *single-node* cluster gives none of Kubernetes' real
  high-availability benefit (no multi-node failover) — the point of using
  it here is to demonstrate genuine K8s manifest authoring, service
  discovery, and container orchestration skills, not to build a
  production-grade HA system. Worth saying exactly this if asked about it
  in an interview.
- Ingress routes `app.`, `merchant.`, and `admin.` subdomains to their
  respective frontend pods; an API gateway (or Ingress path rules) routes
  `/api/<service>` paths to the right backend pod.
- Each service's own datastore (H2 in early development, Postgres for
  the deployed version) runs as its own pod with a persistent volume —
  acceptable for a single-node demo cluster; a real multi-node setup
  would use managed RDS instances instead, which is worth naming as the
  production-grade alternative even though it's not what's built here.

### What this deliberately does not include

- No auto-scaling (HPA) tuned for real load — the cluster has one node,
  so horizontal pod autoscaling wouldn't have anywhere to scale to. The
  manifests can still declare resource requests/limits and an HPA object
  to show the config is understood, while being honest that it's
  inert at this scale.
- No service mesh (Istio/Linkerd) — genuinely out of scope for a
  9-service demo; would add complexity disproportionate to the project's
  actual traffic.
- No CI/CD pipeline yet — tracked as a future extension (see
  `REQUIREMENTS.md`'s spirit of flagging real gaps rather than silently
  skipping them).
