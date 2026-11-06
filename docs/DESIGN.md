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
| `identity-service` | Accounts, auth, buyer/merchant linkage | consumes `merchant.status.changed` (cached `merchantActive`); otherwise foundational — everything else validates tokens issued here |
| `catalog-service` | Products, categories | `merchant-service` (merchant active/inactive), `inventory-service`, Redis (read-through cache) |
| `merchant-service` | Merchant profile, onboarding, commission rate, KYC | `identity-service` (creating merchant login); publishes `merchant.status.changed` (Section 5) |
| `inventory-service` | Stock counts | consumes stock reserve/release request events (Section 5), Redis (atomic pre-check) |
| `payment-service` | Deterministic authorization mock (card-number lookup table) | — (no outbound dependencies) |
| `order-service` | Checkout orchestration, saga coordination (no cart state — cart is client-side) | `identity-service` (live buyer-active check at checkout), `catalog-service`, `promotion-service`, publishes/consumes checkout events |
| `promotion-service` | Coupons | consumes `OrderSucceeded` / `OrderFailed` events |
| `conversation-service` | Order-scoped message threads | `order-service` (validating order/merchant references) |
| `platform-service` | Admin read-model: GMV, commission, order stats | consumes `OrderSucceeded` events (CQRS-style projection) |

Synchronous (REST) vs asynchronous (Kafka event) is deliberately mixed:

- **Synchronous** where a request needs an answer before it can proceed
  (e.g. checkout needs to know a coupon is valid *now*, browsing needs
  current stock *now*).
- **Asynchronous (Kafka)** where the consumer doesn't need to block the
  producer, and where decoupling is the whole point — inventory
  decrementing, commission/GMV projection, and the checkout saga's
  charge/decline/refund messages. The saga is *orchestrated*:
  `order-service` collects every result and decides whether to commit or
  compensate; the other services only react to explicit requests.

Kafka runs locally via Docker for development. Deployment target for
Kafka is a self-managed broker inside the same k3s cluster as the
services (see Section 7) — avoids AWS MSK's per-broker cost entirely,
consistent with the single free-tier-eligible instance target (Section 7).

## 2. Data model, by service

Only fields relevant to design decisions are listed — full field lists
happen at implementation time.

### identity-service
```
Account
  id (PK)
  email (unique)                -- stored lowercase; uniqueness is case-insensitive
  passwordHash                  -- BCrypt; plaintext must meet the password policy
                                --   (REQUIREMENTS Section 4: 8-15 chars, upper, lower,
                                --   digit, special from !@#$%^&*()-_=+)
  buyerActive: boolean          -- the account-level login gate (see below)
  merchantId: nullable(Long)    -- reference to merchant-service's Merchant, if linked
  merchantActive: boolean       -- denormalized cache of merchant-service's Merchant.active;
                                --   set to true when the link is first made, afterwards only by the event
  createdAt
```
`merchantActive` is kept in sync by consuming `merchant.status.changed`,
the same pattern `catalog-service` uses. `merchant-service`'s
`Merchant.active` is the only source of truth: a merchant is activated or
deactivated only via `PATCH /merchants/{code}`, and the change reaches
`identity-service` through the event. The one exception is the internal
merchant-link call (Section 3), which sets `merchantId` and an initial
`merchantActive = true` when the link is first made, because a newly
onboarded merchant starts active. That is a one-time seed, not a general
write API: a repeat call changes nothing. Seeding at link time also
avoids a race in which a status event arrives before the link exists.

**Login rules:** `buyerActive` decides whether the account can log in at
all. A deactivated buyer cannot log in anywhere, including the Merchant
portal, whatever `merchantActive` says. `merchantActive` only adds the
`MERCHANT` capability to an account that can already log in
(REQUIREMENTS Section 4).

**Tokens:**
- JWTs are signed with **RS256**. `identity-service` holds the private key
  and is the only signer. It publishes the public key at a JWKS endpoint
  (`GET /.well-known/jwks.json`). The other services fetch and cache it
  through Spring Security's resource-server support, so a key rotation
  needs no redeploy of the other eight services.
- Lifetime is 1 hour, with no refresh tokens: the user logs in again on
  expiry. Logout is client-side (discard the token); there is no session
  state and no list of revoked tokens.
- Claims: `sub` (account ID), `capabilities` as an array — `["BUYER"]`,
  `["BUYER","MERCHANT"]` or `["ADMIN"]` (admin never combines with the
  others) — and `merchantId` when `MERCHANT` is present, so services scope
  merchant data from the token, not from request parameters. `MERCHANT`
  is included only if `merchantActive` was true at login.
- Because tokens are not revoked, a status change can take up to an hour
  to affect a token that was already issued. Checkout is the exception:
  `order-service` checks live, through `identity-service`, that the buyer
  is still active before it starts the saga (FR-A6).
Admin is not an `Account` row — a small, separate `AdminAccount` table in
`identity-service`'s own schema. **No API endpoint creates it, ever** — it
exists only via a database migration/seed script (e.g. Flyway) run once
at first deploy. There is deliberately no `POST /admin/register` and no
"promote to admin" endpoint anywhere in the system — the only way to add
or change an admin account is to edit the seed script and redeploy, which
is the point: the attack surface for "how does someone become platform
admin" is zero application code, only deploy-time access to the
migration scripts/infrastructure.

**Email uniqueness spans both tables:** an email may exist in `Account`
or `AdminAccount`, never both. Every write path checks both tables
before it creates a row and rejects a collision with a clear conflict
error: public registration, the merchant-link upsert, and the admin
seed migration. Without this, login (which checks `Account` first) could
hide an admin account.

### merchant-service
```
Merchant
  id (PK)                       -- from the sequence merchant_id_seq, taken before the row is built
  merchantCode (unique, human-readable, e.g. MCH-001)
  name, category
  kycVerified: boolean
  active: boolean
  statusVersion: long           -- counts changes of active; starts at 0; the event's `version`
  commissionRate: BigDecimal    -- percentage, e.g. 5.00
  loginEmail (unique)           -- the login identity-service links to this merchant; stored lowercase
  accountLinkStatus: PENDING | LINKED
  createdAt

OutboxEvent                     -- one row per status change (Section 5)
  id (PK)                       -- from outbox_event_id_seq; the relay's publish order
  eventId (unique)              -- UUID, also in the payload; for tracing
  merchantId, version           -- unique together; merchantId is the Kafka key
  payload                       -- the exact JSON to publish
  status: PENDING | SENT
  attempts, lastError           -- failed publish attempts so far, and the latest reason
  createdAt, sentAt
```
- **`merchantCode`** is `MCH-` followed by the id, zero-padded to at least
  three digits: `MCH-001` … `MCH-999`, then `MCH-1000`. The id is taken from
  the sequence before the row is built, so the code is part of the one
  `INSERT`, never set by a later update. It never changes. A unique
  constraint backs it up, and gaps after a rolled-back insert are fine.
  Lookups by code are exact (case-sensitive).
- **Field rules** (a violation is a 400):
  - `name`: required, trimmed, 1–100 characters, not unique.
  - `category`: required, trimmed, 1–50 characters, free text.
  - `commissionRate`: required JSON number (a string is a 400),
    0.00–100.00 inclusive, at most 2 decimal places. More decimals is a
    400, never rounded.
  - `kycVerified`: required at onboarding, with no default. A real JSON
    boolean: the string `"false"` or the number `0` is a 400, never
    converted. The same goes for `kycVerified` and `active` in `PATCH`.
  - `active`: always `true` at onboarding and not part of the request.
    identity-service seeds `merchantActive = true` when it links the login
    (Section 2, identity-service).
- **`kycVerified`** can be changed later through `PATCH /merchants/{code}`.
  It is independent of `active`: either can be true without the other, and
  changing it publishes nothing.
- **`loginEmail`** is unique. One account holds at most one merchant, so two
  merchants with the same login could never both be linked. It is set at
  onboarding and **changes only when a link attempt succeeds** (the merchant
  becomes `LINKED`). A failed retry with a different email leaves the stored
  one as it was.
- **`accountLinkStatus`**: onboarding saves the merchant as `PENDING`
  before it calls identity-service, because the link needs the merchant's
  id. Any 2xx from the link call makes it `LINKED`, and it never goes back.
  While a merchant is `PENDING`, a `PATCH` that contains `active` is refused
  with a 409, even if the value is unchanged. Otherwise identity-service's
  `merchantActive = true` seed at link time would silently undo a
  deactivation made before the link.
- **`statusVersion`** increases by one with every change of `active`, in the
  same transaction as the outbox row. It starts at 0, the state of a newly
  onboarded merchant, so the first change is version 1. Commission and KYC
  changes do not move it, which is why it is not a JPA `@Version`.
- **Row lock.** Hibernate writes every column of a changed row, so two
  overlapping updates from plain reads would let the later one put back what
  the earlier one changed: a commission `PATCH` could undo a deactivation with
  no event, or turn `LINKED` back into `PENDING`. So `PATCH` and the step that
  marks a merchant `LINKED` read the row with `SELECT ... FOR UPDATE`. The lock
  is held only in those short transactions, **never during the HTTP call to
  identity-service**.

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

StockReservation
  orderId, productId            -- composite PK; doubles as the idempotency key
  quantity: int
```
Postgres is the source of truth for `quantity` and the gate that decides
whether a reservation succeeds. Redis holds a mirrored counter per product
(`inventory:stock:{productId}`) used as a fast atomic pre-check, so
checkouts that will obviously fail are rejected before touching Postgres.

**Concurrency design:** stock is **reserved at checkout initiation**, in
parallel with the per-merchant payment authorizations (see Section 4) —
not deferred until `OrderSucceeded`. This closes the race where two
buyers could both pass authorization against the same last unit before
either order finishes. Each reservation runs these steps in order:

1. **Redis pre-check** — a Lua script in one `EVAL` call reads the counter
   (`tonumber(GET)`), and if `quantity >= requestedQty` decrements it with
   `DECRBY`, otherwise returns "insufficient". Because the check and the
   decrement happen in one script, there is no race window between them.
   A missing key returns a distinct "not loaded" result (not zero stock),
   so the service reloads that counter from Postgres and retries.
2. **Postgres gate** — in one transaction:
   `UPDATE stock_level SET quantity = quantity - :q WHERE product_id = :id AND quantity >= :q`
   plus an insert into `StockReservation`. If a `StockReservation` row
   already exists for this `(orderId, productId)`, the message is a
   redelivery and is treated as a no-op.
3. **Publish** — `StockReserved` only after step 2 commits. If step 2
   updates zero rows or fails, Redis is incremented back and
   `StockReservationFailed` is published.

Because a reservation is never confirmed before Postgres records it,
Postgres is never behind Redis. If Redis restarts, a startup
reconciliation reloads its counters from Postgres, and this cannot lead to
overselling. The cost is one synchronous Postgres write per line item,
which is acceptable at this scale. Rejected alternatives: Redis AOF
persistence only shrinks the window in which a crash can lose a
decrement (to about a second); using Postgres alone would be correct
but would remove the atomic Redis step this design is meant to show.

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
Built from scratch as a **deterministic mock**. There is no real card
network or acquirer (REQUIREMENTS Section 3). The outcome of an
authorization depends only on the card number, looked up in a fixed
table:

| Card number | Result | ISO 8583 response code |
|---|---|---|
| `4000000000000002` | Approved | `00` |
| `4000000000000010` | Declined — insufficient funds | `51` |
| `4000000000000028` | Declined — do not honor | `05` |
| `4000000000000036` | Declined — expired card | `54` |
| *any other number* | Approved | `00` |

```
Transaction
  id (PK), transactionRef (unique)
  orderId, merchantId           -- references only
  amount: BigDecimal
  responseCode                  -- ISO 8583-style, from the table above
  status: APPROVED | DECLINED | REFUNDED
  createdAt
```
An approved authorization is captured immediately (authorize + capture
in one step). A refund of an `APPROVED` transaction always succeeds and
moves it to `REFUNDED`. Because results are deterministic, every
single-outcome saga path in Section 4 can be reproduced in demos just by
choosing a card number.

**Test strategy for mixed outcomes:** a buyer pays a whole order with
one card, so with the live mock every merchant in an order gets the same
result. The mixed-outcome path ("2+ merchants, one declines", plus its
compensating refunds) is therefore verified by **integration tests** that
publish fake `MerchantCharged` / `MerchantDeclined` events directly to
`order-service`'s consumer, bypassing `payment-service`. It is validated
by tests, not by a live demo. There is deliberately no per-merchant
override in the mock.

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
There is no server-side cart. The cart lives only in the Buyer front-end
and is sent whole in the `POST /orders/checkout` body. `order-service`
re-validates every line at checkout (product active, merchant active,
current price from `catalog-service`) and does not trust the prices or
availability the client sends. Hiding unavailable products in the UI
(FR-B6/FR-M6) is a convenience; the real check is server-side.
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

**`FIXED` coupons — capping and splitting:**

- *Merchant coupon:* the discount is capped at that merchant's own
  subtotal.
- *Platform coupon:* the discount is first capped at the cart's total
  subtotal, then split between merchants in proportion to each
  merchant's subtotal (`exact_m = cappedDiscount × subtotal_m / cartSubtotal`).
  Because of the cap, no merchant's share can exceed that merchant's
  subtotal.
- *Rounding (largest-remainder method):* floor each `exact_m` to the
  cent, sum the floors, and give the leftover cents to the merchants with
  the largest fractional remainders, one cent each. Ties go to the
  merchant that appears first in the cart. The shares always sum exactly
  to `cappedDiscount`.
- *Zero net amount:* if a merchant's net amount owed
  (`subtotal_m − share_m`) is zero, that merchant gets **no payment leg**
  (no zero-amount authorization), but their stock-reservation leg still
  runs. For a merchant coupon this happens whenever the cap applies. For
  a platform coupon, the clean case is a capped discount equal to the
  whole cart subtotal: every merchant's net is zero at once (the full
  order is comped), and the order succeeds on stock reservation alone.
  Otherwise a single merchant's net can only reach exactly zero through a
  rounding coincidence.

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
  logged in from). One endpoint for everyone: it checks `Account` first,
  then falls back to `AdminAccount`. An `Account` with `buyerActive =
  false` is refused (Section 2, login rules)
- `GET /accounts` (Admin, paginated — FR-A5). Lists every row in
  `Account` (merchant-linked ones included); `AdminAccount` rows never
  appear. `page` starts at 0 (default 0) and `size` is 1..100 (default
  20); anything out of range is a 400, not silently clamped. Order is
  fixed, `id` ascending. A page past the end is 200 with empty
  `content`. Response: `{content, page, size, totalElements,
  totalPages}`, where each item has the same fields as `GET
  /accounts/{id}`.
- `GET /.well-known/jwks.json` (public — RS256 verification key, consumed
  by every other service's resource-server config)
- `GET /accounts/{id}` (self or Admin; also used by `order-service`'s
  live buyer-active check at checkout). A token whose `capabilities`
  contains `ADMIN` is always allowed and its `sub` is never compared
  with the path: `Account` and `AdminAccount` ids are separate sequences
  that both start at 1, so an admin's `sub` can equal a buyer's id. Any
  other token is allowed only if its `sub` equals `{id}`, and gets 403
  otherwise, decided before the lookup so it does not reveal whether the
  account exists.
- `PATCH /accounts/{id}/buyer-status` (Admin — FR-A6). Body
  `{"buyerActive": true|false}`; a missing or null value is a 400, never
  treated as `false`, and so is anything but a real JSON boolean (the
  string `"false"` or the number `0`), never converted. Returns 200 with
  the updated account. The admin
  check comes before the lookup, so only an admin can get a 404. Tokens
  already issued are not revoked (Section 2, Tokens); checkout's live
  check through `GET /accounts/{id}` sees the change at once.
- For the Admin endpoints above, request validation runs before the
  admin check: an authenticated non-admin with an invalid request gets
  400, not 403. Nothing is changed or revealed either way.
- Everywhere in this service, a number with a decimal (e.g. `1.5`) sent
  for a whole-number JSON field is a 400, never silently truncated.
  Likewise a boolean or number field takes only that JSON type: `"false"`
  or `0` for a boolean and `"42"` for a number are 400s, never converted.
  One global Jackson setting covers every request body, present and future.
- *(No merchant-status endpoint here — merchant activation happens only
  via `PATCH /merchants/{code}` on `merchant-service`; see Section 2.)*
- `POST /internal/accounts/merchant-link` (internal-only — called by
  `merchant-service` during onboarding, not exposed to the public Buyer
  or Merchant front-ends). The body carries `email` (normalized to
  lowercase), `merchantId` (positive), and an optional `initialPassword`.
  It is an **upsert by email**. Checks run in this order, and the first
  that applies decides the answer:
  1. Email belongs to an `AdminAccount`: 409 `EMAIL_BELONGS_TO_ADMIN`.
  2. `merchantId` is already linked to a different account: 409
     `MERCHANT_ALREADY_LINKED_TO_OTHER_ACCOUNT`. A merchant has at most
     one account (unique index on `merchant_id`, NULLs allowed). A repeat
     call by the account that already holds it is not a conflict.
  3. No account with that email: create one with `initialPassword`, linked
     (**201**, `outcome: CREATED`). `initialPassword` is required here
     (400 if missing) and must meet the password policy (400 naming every
     failed rule, REQUIREMENTS Section 4).
  4. Account already linked to a different merchant: 409
     `ACCOUNT_LINKED_TO_OTHER_MERCHANT` (at most one merchant per
     account, REQUIREMENTS Section 4).
  5. Account already linked to this merchant: no change at all, not even
     `merchantActive` (**200**, `outcome: ALREADY_LINKED`). This comes
     before step 6, so repeating an earlier successful call is always 200,
     even if the buyer has been deactivated since.
  6. `buyerActive = false`: 409 `BUYER_DEACTIVATED`, telling Admin to
     reactivate the buyer account first. Otherwise it would create a
     merchant who can never log in.
  7. Link the existing account and keep its password (**200**, `outcome:
     LINKED`).

  For an existing account, `initialPassword` is ignored completely (not
  checked, not stored). `merchantId` and `merchantActive = true` are set
  only when the link is first made (the one-time seed, Section 2). An
  invalid body (bad or missing `email`, missing or non-positive
  `merchantId`, malformed JSON) is a 400. 409s are problem details with a
  `code` property. A success body has the `GET /accounts/{id}` fields plus
  `outcome`, never a password or hash. If a concurrent write (a
  registration of the same email, or a link of the same `merchantId`)
  lands between the checks and the write, a unique constraint rejects it
  and the call is retried once, which then answers from the new state.

  Secured by a **shared API key**, never a user JWT. The caller sends it
  in the `X-Internal-Api-Key` header. `identity-service` reads it from
  `basketry.internal.api-key`, filled from the `BASKETRY_INTERNAL_API_KEY`
  environment variable (a Kubernetes Secret when deployed; never
  committed). Leading and trailing whitespace in the configured key (such
  as the trailing newline a Secret often carries) is stripped before the
  length check and the hash. A key shorter than 32 characters fails
  startup. With no key
  set (e.g. local dev), the service starts, logs a warning, and refuses
  every internal call. Keys are compared in constant time (SHA-256 of
  both, then `MessageDigest.isEqual`). `/internal/**` has its own security
  chain that never reads the `Authorization` header and sends no CORS
  headers: a missing key, a wrong key, or a user JWT (even an admin's)
  without the key all get the same bare 401. The Ingress must never route
  `/internal/**` externally (Section 7). Optionally, a k3s NetworkPolicy
  can also restrict which pods can reach it, but the API key is the
  primary control. There is no OAuth client-credentials flow.
- *(No endpoint for creating an Admin account exists anywhere in this
  service, or any other. See Section 2 — Admin is seeded via migration.)*

**merchant-service**

Every endpoint needs a bearer JWT from identity-service. Tokens are verified
with the keys at identity-service's `GET /.well-known/jwks.json` (Section 2,
Tokens).
- The JWKS is fetched on first use, not at startup, so the two services
  can start in either order.
- Keys are cached. A token whose `kid` is not cached makes the service
  fetch the JWKS again. In local dev, identity-service makes a new key on
  every restart: new tokens work at once, and tokens signed with the old
  key get 401.
- If the JWKS cannot be fetched (identity-service down, timing out or
  answering with an error) and the token's key is not cached, the answer
  is **503** (problem detail), not 401 and not Spring's default 500. The
  token may be valid, and a 401 would make the front-end log the user out.
- A malformed, expired or tampered token, or a key identity-service does
  not publish, is 401.
- A key that is already cached keeps working while the JWKS is unreachable.

As in identity-service, request validation runs before the admin check:
an authenticated non-admin with an invalid request gets 400, not 403.
Every error is a problem detail, and a 409 or 503 that a client may need
to tell apart carries a `code` property.

- `POST /merchants` (Admin — FR-A1). Body: `name`, `category`,
  `kycVerified`, `commissionRate` (rules in Section 2), `loginEmail`, and
  an optional `initialPassword`. The password is only needed when no
  account exists for the email. It is passed to identity-service and never
  stored or logged. Any other field, including `active`, is a 400 that
  names it (the same rule as `PATCH`), so Admin never believes a value was
  applied when it was not. A new merchant always starts active.
  1. If `BASKETRY_INTERNAL_API_KEY` is unset: 503
     `INTERNAL_API_KEY_NOT_CONFIGURED`, before anything is saved.
  2. If another merchant already has this `loginEmail` (case-insensitive):
     409 `LOGIN_EMAIL_IN_USE`, with that merchant's `merchantCode`, so Admin
     can retry it instead of creating a duplicate.
  3. The merchant is saved as `PENDING` and committed. Then
     identity-service's internal merchant-link is called, outside any
     database transaction.
  4. The answer is always **201** with a `Location` header once the
     merchant is saved, whatever the link result. The body has the
     merchant's fields plus exactly one of:
     - `accountOutcome` when the link succeeded (any 2xx) and the merchant
       is `LINKED`. `CREATED`: a new account was created with the initial
       password. `LINKED`: an existing account was linked, and its password
       was kept. `ALREADY_LINKED`: the account was already linked to this
       merchant, and nothing changed. This can also be the answer when an
       earlier attempt timed out after identity-service had already created
       the account, so the initial password may have been set by that
       earlier attempt.
     - `accountLinkError {code, detail, violations}` when the merchant stays
       `PENDING`:

       | `code` | Cause | `detail` shown to Admin |
       |---|---|---|
       | `EMAIL_BELONGS_TO_ADMIN` | identity 409 | This email is an admin login. Use a different email. |
       | `ACCOUNT_LINKED_TO_OTHER_MERCHANT` | identity 409 | This login already runs another merchant. Use a different email. |
       | `BUYER_DEACTIVATED` | identity 409 | This buyer account is deactivated. Reactivate it first (Accounts page), then retry. |
       | `MERCHANT_ALREADY_LINKED_TO_OTHER_ACCOUNT` | identity 409; only possible if an earlier attempt that timed out actually succeeded with another email | An earlier attempt already linked this merchant to a different login. Retry with the original email. |
       | `WEAK_PASSWORD` | identity 400 with `violations` | identity-service's detail; `violations` lists every failed rule |
       | `INITIAL_PASSWORD_REQUIRED` | identity 400, no password sent | No account exists for this email, so an initial password is required. |
       | `IDENTITY_UNAVAILABLE` | timeout, connection failure or 5xx, twice | Merchant saved; login not linked yet. Retry. |
       | `IDENTITY_AUTH_FAILED` | identity 401 (the API keys differ); logged as an error | Fix the configuration, then retry. |
       | `LINK_REJECTED` | any other 4xx or an unknown 409 code; logged as an error | identity-service rejected the request. |
- `POST /merchants/{code}/account-link` (Admin). Retries the link of a
  `PENDING` merchant with the **same `merchantId`**, so a repeat after a
  timeout that actually succeeded gets `ALREADY_LINKED` and nothing is
  created twice. The body is optional: `email` defaults to the stored
  `loginEmail`, and `initialPassword` is needed only if no account exists
  for the email. The stored `loginEmail` changes only if this attempt
  succeeds (Section 2).
  - 404 for an unknown code (only an admin can get it).
  - 409 `MERCHANT_ALREADY_LINKED` if the merchant is already `LINKED`.
  - 503 `INTERNAL_API_KEY_NOT_CONFIGURED`.
  - 409 `LOGIN_EMAIL_IN_USE` if another merchant has the email.
  - Otherwise **200**, with the same body as `POST /merchants`.
- `GET /merchants` (Admin — FR-A2). Same paging as `GET /accounts`: `page`
  from 0 (default 0), `size` 1..100 (default 20), anything out of range is
  a 400, fixed `id`-ascending order, a page past the end is 200 with empty
  `content`. Response: `{content, page, size, totalElements, totalPages}`,
  where each item has the fields of `GET /merchants/{code}`.
- `GET /merchants/{code}` (Admin, or the merchant itself: a `MERCHANT`
  token whose `merchantId` claim is this merchant's id). Fields: `id`,
  `merchantCode`, `name`, `category`, `kycVerified`, `active`,
  `commissionRate`, `loginEmail`, `accountLinkStatus`, `createdAt`.
  Ownership can only be checked after the lookup, so a non-admin gets 403
  both for someone else's merchant and for a code that does not exist.
  Only an admin can get a 404.
- `PATCH /merchants/{code}` (Admin — FR-A3, FR-A4). Accepts `commissionRate`,
  `kycVerified` and `active`, and at least one is required. An explicit
  `null` is a 400, and so is any other field, named in the error, so Admin
  never believes a change was applied when it was not. Returns 200 with the
  merchant (no `version` field); values that do not change are a 200 no-op.
  The fields can be combined and are applied in one transaction: all of them
  or none.
  - This is the only way to change `active`. Only a real change bumps
    `statusVersion` and writes the `merchant.status.changed` outbox row
    (Section 5). The `PATCH` never waits for Kafka and succeeds while it is
    down.
  - Checks run in this order: the body (400), admin (403), the lookup (404),
    then 409 `MERCHANT_NOT_LINKED` if the body contains `active` and the
    merchant is `PENDING` (Section 2), even with an unchanged value. The 409
    applies nothing, not even the other fields. Without `active`, a `PENDING`
    merchant can still be changed.

**Calling identity-service.** merchant-service uses Spring `RestClient` on
the JDK HttpClient.
- Base URL: `basketry.identity.base-url`, from the `BASKETRY_IDENTITY_URL`
  environment variable (default `http://localhost:8080`). The JWKS fetch
  uses the same base URL.
- Timeouts: connect 2 s, read 5 s, for both merchant-link and the JWKS
  fetch.
- Retry: a merchant-link call that fails with a timeout, a connection
  error or a 5xx is retried once after 200 ms. This is safe because the
  call is idempotent. A 4xx is never retried.
- API key: sent in `X-Internal-Api-Key`, from `basketry.internal.api-key`
  (`BASKETRY_INTERNAL_API_KEY`), with the same rules as identity-service:
  whitespace is stripped, fewer than 32 characters fails startup, and unset
  means a warning and 503 on onboarding.
- Neither the key nor the initial password is ever logged, and request
  objects mask the password in `toString`.

**catalog-service**
- `GET /products?merchantCode=`
- `POST /products` (Merchant, own products only)
- `PATCH /products/{id}` (Merchant, own products only)

**order-service**
- `POST /orders/checkout` — starts the saga (see Section 4). The body
  carries the whole client-side cart (`productId`, `quantity` per line),
  an optional `couponCode`, and `cardNumber`. *(A real system would send
  a tokenized payment-method reference, never a raw card number.)*
- `GET /orders?buyerAccountId=` / `?merchantId=`
- `GET /orders/{reference}`

**payment-service**
- No REST endpoints for the front-ends. Its interface is the Kafka topics
  in Section 5 (`order.merchant-charge.requested`,
  `order.merchant-refund.requested` in; charged / declined / refunded
  out). Response codes follow the lookup table in Section 2.

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
2. `order-service` → `identity-service`: confirm the buyer is still
   active (sync, live check — FR-A6; a deactivated buyer is rejected
   before anything else happens), then `order-service` →
   `promotion-service`: validate coupon (sync, if present)
3. `order-service` creates `Order` in `PENDING`, then publishes, in
   parallel: one `MerchantChargeRequested` event per merchant whose net
   amount owed is above zero (a merchant whose net is zero after the
   coupon gets no payment leg — Section 2, promotion-service), **and**
   one `StockReserveRequested` event per product line, for every line
4. `payment-service` consumes each charge request, looks up the card in
   the mock table (Section 2), authorizes+captures, and publishes
   `MerchantCharged` (or `MerchantDeclined` with the response code)
5. `inventory-service` consumes each reservation request, pre-checks and
   decrements in Redis, then commits the conditional decrement in Postgres
   (Section 2), and only then publishes `StockReserved` (or
   `StockReservationFailed` if insufficient quantity)
6. `order-service` waits for a result for **every** merchant charge it
   requested and **every** stock reservation in the order — not just one
   or the other.
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
   deletes the `StockReservation` row and increments `stock_level` in one
   Postgres transaction (a missing row means already released, so no-op),
   then increments the Redis counter back and confirms `StockReleased`
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
| `merchant.status.changed` | merchant-service | catalog-service, identity-service | schemaVersion, eventId, merchantId, merchantCode, active, version, occurredAt |
| `order.merchant-charge.requested` | order-service | payment-service | orderId, merchantId, amount, cardNumber *(real system: tokenized payment-method reference, not a raw card number)* |
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

**`merchant.status.changed`** (merchant-service Stage B1 builds the producer;
the consumers come later):
- **`version`** increases by one with every status change of that merchant,
  and the Kafka key is `merchantId`, as a string. A consumer ignores an event
  whose `version` is not newer than the one it has already applied. That
  covers duplicates and out-of-order delivery.
- **No event is published at onboarding.** A consumer that has not seen a
  merchant yet assumes it is active at version 0, which is how every merchant
  starts, until the first event arrives.
- **`merchantCode`** is carried because catalog-service filters products
  by code.
- **Topic:** 3 partitions, replication factor 1, default retention. The
  partition count is fixed, because changing it moves keys between partitions
  and breaks per-merchant order. merchant-service creates the topic itself, on
  first publish and not at startup, so startup never waits for Kafka. The
  broker does not auto-create topics.
  After a publish failure the publisher rechecks the topic, so a broker
  restarted without its data (no volume) gets the topic created again.
- **Value:** JSON, sent as a plain string with no Java type header. The payload
  is built once, in the `PATCH` transaction, and stored in the outbox row, so a
  resend is byte-identical:
  ```json
  {"schemaVersion":1, "eventId":"<uuid>", "merchantId":42, "merchantCode":"MCH-042",
   "active":false, "version":3, "occurredAt":"2026-11-06T01:20:22.864Z"}
  ```
  `schemaVersion` lets a consumer detect a format change. `eventId` is for
  tracing only: duplicates are dropped by `version`, not by `eventId`.
- **Publishing uses a transactional outbox.** The outbox row is written in
  the same database transaction as the `PATCH`, and a scheduled relay
  publishes it and marks it sent. Delivery is at least once. A plain
  publish after the commit could lose the event if the publish failed,
  leaving the cached `merchantActive` wrong for good. Kafka transactions
  cannot include the database, and CDC (Debezium) is too heavy here.
- **The relay** runs every second (fixed delay, so passes never overlap) and
  takes up to 100 `PENDING` rows in `id` order. It sends one row, waits for
  the acknowledgement, then marks it `SENT` in a short transaction. No
  transaction is open during a send.
  - **Order.** The merchant's row lock (Section 2) means one merchant's rows get
    ids in version order, and one key always goes to one partition.
  - **On a failure the pass stops**, so a later event of the same merchant
    cannot overtake the one that failed. This holds up the other merchants'
    events too, which is acceptable because the only expected failure is the
    broker being unreachable, and that affects every row alike. Revisit this if
    a single row could ever fail on its own, because that row would then block
    all the others.
  - **Kafka down.** The row stays `PENDING`, with `attempts` and `lastError`
    updated, and the relay waits 1, 2, 4 … up to 60 s between attempts, resetting
    on success. There is no `FAILED` status, because giving up on a row would
    lose the event. Nothing is lost while the database keeps the row (see the
    H2 note in Section 7).
  - **Crash after the send, before the mark.** The row is sent again, the same
    bytes, and consumers drop it by `version`.
  - **Two instances.** There is no leader lock. A rolling update can briefly
    run two; both may publish a row, which is only a duplicate, and a mark
    applies only to a `PENDING` row. `SKIP LOCKED` is deliberately not used:
    it would let one instance send a merchant's next event while another still
    holds the previous one.
  - **Sent rows are kept** as an audit trail, and the `(status, id)` index
    keeps the relay's query cheap. Status changes are rare admin actions, so
    there is no cleanup job yet.
- **Producer:** `acks=all`, idempotence on, `max.block.ms` 5 s,
  `request.timeout.ms` 5 s, `delivery.timeout.ms` 10 s. Any send failure or
  timeout counts as not sent, and the broker address comes from
  `BASKETRY_KAFKA_BOOTSTRAP_SERVERS` (default `localhost:9092`).

The `order.*` events carry the `orderId` (and `merchantId`/`productId` where
relevant) as a natural idempotency key — consumers must treat redelivery
as a no-op if they've already processed that combination, since Kafka's
at-least-once delivery means duplicates are expected, not exceptional.
`merchant.status.changed` uses `version` for the same purpose.

## 6. Open questions for implementation

- Exact idempotency-key strategy on `payment-service`'s consumer side
  (dedupe table vs. relying on `PaymentService`'s existing reference
  generation) — decide when we build `payment-service`'s Kafka listener.
- Whether `order-service` needs its own outbox table to guarantee
  `MerchantChargeRequested` is published exactly once per DB commit
  (transactional outbox pattern) — worth doing properly rather than
  skipping, since it's a genuinely common real-world gap; decide before
  writing `order-service`'s checkout handler.
- `order-service` needs the commission rate in force at order time, and
  nothing provides it yet. `merchant-service` holds only the current rate,
  so a rate changed later must not alter the commission of an earlier order.
- Tokens carry no `iss` or `aud` claim, so a service accepts any token
  signed by identity-service's key. Accepted for now.
- Known limitation, accepted for now: one admin retries a merchant's link
  with a new email while another admin onboards a different merchant with
  that same email at the same moment. identity-service can end up linked to
  the first merchant while merchant-service keeps it `PENDING` with its old
  email (the unique `loginEmail` refuses the update). There is no delete
  endpoint to clean up.

## 7. Technology stack & deployment

This project is explicitly built to demonstrate hands-on ability across a
specific stack, not just to solve the marketplace problem in whatever way
was fastest — worth stating plainly since it explains some choices (e.g.
Kubernetes) that a smaller/cheaper deployment could have skipped.

| Layer | Choice | Why |
|---|---|---|
| Language | **Java 25 (LTS)** | Current LTS as of 2026 (Spring Boot 3.5, previously assumed, reached OSS end-of-life 30 June 2026). Records for DTOs, sealed interfaces + pattern matching for the saga's event types, virtual threads for the I/O-heavy inter-service calls — current, interview-relevant Java |
| Framework | **Spring Boot 4.1** | Current stable line, built on Spring Framework 7; officially supports Java 25. Consistent across all 9 services |
| Messaging | **Apache Kafka** | Event transport for the orchestrated checkout saga (Section 4/5) and read-model projections |
| Caching / atomic ops | **Redis** | Two specific uses, not general-purpose caching everywhere: (1) atomic stock decrement in `inventory-service` to prevent overselling under concurrent checkouts, (2) read-through cache for `catalog-service`'s product listings, which are read far more often than written |
| Containerization | **Docker** | Every service ships a Dockerfile; Kafka, Redis, and per-service Postgres run via Docker Compose for local dev |
| Orchestration | **Kubernetes (k3s)** | Manifests for Deployments, Services, ConfigMaps, Secrets, and Ingress — real K8s objects, portable to a managed cluster (EKS) later without rewriting |
| Cloud | **AWS** | Single free-tier-eligible EC2 instance running k3s; see below |
| Frontend | **React + TypeScript** | Three separate apps — Buyer, Merchant, Admin (Section 1) |

### Deployment shape

- One EC2 instance (free-tier eligible for the first 12 months of an AWS
  account) runs a single-node **k3s** cluster — a lightweight, fully
  conformant Kubernetes distribution, not a toy subset. All 9 backend
  services, Kafka (in KRaft mode, no Zookeeper), and the 3 frontend apps run as
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
  `/api/<service>` paths to the right backend pod. The Ingress must
  never route `/internal/**` from outside the cluster; those paths are
  reached only service-to-service (Section 3, merchant-link).
- `BASKETRY_INTERNAL_API_KEY` (at least 32 characters) must be set in
  `identity-service`'s and `merchant-service`'s Kubernetes Secret before
  deployment. Without it, `identity-service` still starts but refuses
  every internal call, so merchant onboarding fails. `merchant-service`
  also needs `BASKETRY_IDENTITY_URL` (in a ConfigMap; not a secret) to reach
  identity-service for the JWKS and for merchant-link, and
  `BASKETRY_KAFKA_BOOTSTRAP_SERVERS` (also a ConfigMap) to reach Kafka.
- Each service's own datastore (H2 in early development, Postgres for
  the deployed version) runs as its own pod with a persistent volume —
  acceptable for a single-node demo cluster; a real multi-node setup
  would use managed RDS instances instead, which is worth naming as the
  production-grade alternative even though it's not what's built here.

### Local development

- The root `docker-compose.yml` runs one Kafka broker in KRaft mode
  (`apache/kafka:4.2.2`, the same 4.2 line as the client). Start it with
  `docker compose up -d --wait` and stop it with `docker compose down`. Services
  run from IntelliJ reach it at `localhost:9092`; other containers on the
  Compose network reach it at `kafka:29092`. It has no volume, so `down` deletes
  its data.
- **Interim:** REQUIREMENTS asks for the whole system to start with one
  documented command, and the stack table above puts Redis and Postgres in
  Compose too.
  For now Compose holds Kafka only, and the services run from IntelliJ on
  in-memory H2. The rest joins as each piece is built.
- No automated test needs Docker: merchant-service's Kafka tests use an
  embedded KRaft broker.
- **Restarting with H2.** Restarting only merchant-service resets its merchant
  ids and versions, and loses any `PENDING` outbox rows, while Kafka and a
  running consumer keep the old ones. A new merchant 1 at version 1 would then
  be ignored by a consumer that already applied version 5 of the old merchant
  1. So restart both services and run `docker compose down` together. This
  goes away with Postgres.

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
