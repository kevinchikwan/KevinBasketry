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
| `catalog-service` | Products (with a free-text category) | consumes `merchant.status.changed` (cached `merchantActive`); `inventory-service` (creates stock rows, reads availability); Redis (read-through cache of listings) |
| `merchant-service` | Merchant profile, onboarding, commission rate, KYC | `identity-service` (creating merchant login); publishes `merchant.status.changed` (Section 5); serves `order-service` the live status and commission rate (`GET /internal/merchants`) |
| `inventory-service` | Stock counts | consumes stock reserve/release request events (Section 5), Redis (atomic pre-check) |
| `payment-service` | Deterministic authorization mock (card-number lookup table) | — (no outbound dependencies) |
| `order-service` | Checkout orchestration, saga coordination (no cart state — cart is client-side) | `identity-service` (live buyer-active check at checkout), `catalog-service` (re-validation of every line), `merchant-service` (live status and commission rate), `promotion-service` (not yet: coupons are refused until it exists), publishes/consumes checkout events |
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
  merchantStatusVersion: long   -- version of the last event applied (Section 5); 0 until the first one
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
write API: a repeat call changes nothing. A status event cannot arrive
before the link exists, because merchant-service refuses to change `active`
with a 409 while the merchant is `PENDING` (Section 2, merchant-service).

**Writers of an `Account` row.** Each writer owns separate columns, and
`@DynamicUpdate` makes an update write only the columns it changed, so
overlapping writers cannot undo each other:
- The buyer-status `PATCH` owns `buyer_active`.
- The merchant link and the status consumer own `merchant_id`,
  `merchant_active` and `merchant_status_version`. They never overlap: the
  consumer's update matches a row only once its link is committed, and a link
  is made only once.

**Any new writer of a column that another writer already owns must take a row
lock**, because `@DynamicUpdate` does not protect two writers of one column.
merchant-service uses a row lock instead (Section 2, merchant-service) because
its writers do share columns.

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
Product                         -- table product
  id (PK)                       -- from product_id_seq
  merchantId                    -- the owner, from the token at creation; reference only; never changes
  name                          -- trimmed, 1-100 characters
  description                   -- trimmed, 0-2000 characters; '' when none
  category                      -- trimmed, 1-50 characters, free text (as merchant-service's category)
  price: BigDecimal             -- NUMERIC(12, 2); GBP, 0.01-99,999.99, at most 2 decimal places
  active: boolean               -- true at creation; false = deactivated by its merchant
  createdAt, updatedAt

MerchantStatus                  -- table merchant_status; the local copy of Merchant.active
  merchantId (PK)               -- reference only
  active: boolean
  version: long                 -- of the last merchant.status.changed event applied (Section 5)
  updatedAt

StockRowRequest                 -- table stock_row_request; the outbox of the stock-row creation
  productId (PK, FK product)
  merchantId                    -- the owner sent to inventory-service
  status: PENDING | CREATED | CONFLICT | REJECTED
  attempts, lastError           -- failed calls so far, and the latest reason
  createdAt, completedAt        -- completedAt set exactly when the status is not PENDING
```
- **Field rules** (a violation is a 400 once the REST API exists; every broken
  rule is named at once). Lengths count UTF-16 code units, as `@Size` does in
  merchant-service, so a valid value always fits its column. A price with more
  than 2 decimal places is refused, never rounded; trailing zeros do not count
  (`9.990` is 9.99), as merchant-service's `@Digits`. The upper price keeps
  1,000 units of one line far below payment-service's amount column.
- **Categories** are a plain string on the product, not a table: no
  requirement asks for managing them. A categories table can come later by
  migrating the distinct strings.
- **Never deleted.** Deactivation is `active = false`, and it can be undone.
  Stock rows and orders keep referring to the product.
- **Row lock.** Hibernate writes every column, so an update reads the product
  with `SELECT ... FOR UPDATE`, waiting at most 5 s for another request's lock
  (then 503 `PRODUCT_BUSY`), the same as merchant-service (Section 2,
  merchant-service). The relay never writes a product row; it has its own table.
- **Times** are stored and answered at microsecond precision, what the columns
  keep, so an answer shows exactly what a later read returns.

**The local merchant status.** `catalog-service` copies `Merchant.active`
from `merchant.status.changed` (Section 5) instead of calling merchant-service
on every browse request. **A merchant with no row has never changed status and
is active at version 0**, the state of every newly onboarded merchant, so no row
is created at onboarding or when a merchant creates a product; the listing
reads `COALESCE(ms.active, TRUE)`. An event is applied by one statement with no
read first, standard SQL `MERGE` (H2 has no `ON CONFLICT DO UPDATE`): insert
the row if the merchant has none, replace it if the event's version is newer,
otherwise change nothing. Duplicates and older events are ignored; gaps are not
detected, because an event carries the full state. Every merchant's events are
stored, also before it has products. On PostgreSQL two instances inserting the
same new merchant at once can make one fail on the primary key; that is a
database error, which the consumer retries, and the retry then updates. The
consumer is described in Section 5 (the catalog-service consumer).

**Visibility** (FR-B3, FR-B6, FR-A3). The public listing shows a product only if
it is active **and** its merchant is active. Products with zero stock stay
listed and show as out of stock. A product hidden by either rule answers 404 on
`GET /products/{id}` (Section 3), and the Buyer app shows a cart line that gets
a 404 as "no longer available". This is the reading of FR-B3 ("products from
active merchants") together with FR-B6 ("shown as unavailable"): hidden in the
catalog, unavailable in the cart. The local merchant status lags the event by
seconds, so **the real check of purchasability is order-service's re-validation
at checkout** (`GET /internal/products`, Section 3), not the listing.

**Redis cache.** `GET /products` (with or without `merchantId=` and
`category=`) goes through a cache-aside cache in Redis. Only the public listing
is cached: not a product's detail, not the management reads.
- **Keys.** A listing belongs to a scope: `all`, or `m:{merchantId}` when it is
  filtered by merchant. Each scope has a version counter,
  `catalog:v2:ver:{scope}`, with no TTL. A page is stored under
  `catalog:v2:list:{scope}:{version}:{c=<category, URL-encoded>|c*}:p={page}:s={size}`
  with a TTL of 60 s. The category is URL-encoded so that free text can never
  imitate another key. `v2` is the format of the stored page: changing it needs
  a new prefix, so a new release never reads an old release's pages. (`v1` until
  the page's conflict flag became `stockRowFailed`, covering `REJECTED` too.)
- **Invalidation is a version bump, never a delete.** A product create or
  update, an applied status event and a stock-row `CONFLICT` or `REJECTED`
  (below) bump `all`
  and the merchant's scope with `INCR`. Every page of those scopes is then
  unreachable at once; the old pages simply expire. A delete would need a
  `SCAN` over every page key, and would still have the race below.
- **The order is what keeps it correct.** A listing reads the scope's version
  **before** it queries Postgres, and stores the page under that version. A
  writer bumps **after** its commit. So a page built from data older than a
  commit is always stored under a version older than the bump that follows the
  commit, and no later listing reads it. Reading the version after the query
  would let a commit and bump fall between the two and put old data under the
  new version; bumping before the commit would let a listing read the new
  version and still see the old data. The bump refuses to run inside a
  transaction, and two tests place exactly these races.
- **The value** is JSON of the page's products and `totalElements`, without
  availability, which is read from inventory-service on every listing (below)
  and never cached. Prices are strings, so their scale survives. A page that
  cannot be read counts as a miss and is overwritten.
- **Redis is never needed.** Every call goes through one guard, copied from
  inventory-service's: any error or timeout means "unavailable", the listing is
  read from Postgres and nothing is stored, and Redis is skipped for the next
  10 s instead of timing out on every request. Timeouts are 500 ms, and commands
  fail at once while disconnected.
- **When a listing can be stale:** only when a bump fails (Redis unreachable
  just after a commit, or a crash between the commit and the bump). The old
  pages are then served until their TTL ends, so for at most 60 s. That is the
  one accepted gap, and order-service's re-validation does not depend on it.
- **No stampede protection.** At a 60 s TTL and this traffic, misses go to
  Postgres; a lock or single-flight loading would add more than it saves.
- **Memory.** Redis is shared with inventory-service's counters and runs
  `volatile-lru` (Section 7): pages have a TTL, so Redis may evict them; the
  version keys and the counters have none, so it never evicts them. A test
  checks the TTLs on a real Redis.

**Availability in listings and on the detail** (FR-B6, FR-M6). After the page
(or the one product of `GET /products/{id}`) is read, from the cache or from
Postgres, one call to inventory-service's
`GET /internal/stock?productIds=` (Section 3) gives the stock of its products;
each product gets `availability`:
- `IN_STOCK`: quantity above zero.
- `OUT_OF_STOCK`: zero, no stock row (a new product whose row the relay has not
  created yet), or a stock-row request in `CONFLICT` or `REJECTED` (below),
  whatever inventory-service holds for that id.
- `UNKNOWN`: inventory-service did not answer: no connection, a status other
  than 200, or no answer within 1 s (connect timeout 500 ms). Not "out of
  stock", because a whole site shown as sold out during an outage would be
  wrong, and checkout's reservation is the real check anyway. The Buyer app
  decides whether an `UNKNOWN` product can go in the cart. After a failure,
  listings skip inventory-service for 10 s (every product `UNKNOWN`) instead
  of waiting for a timeout each time.

The exact quantity is never shown. The extra latency is one local HTTP call and
one indexed query in inventory-service: a few milliseconds, at most about 1 s
before the skip window opens.

**Stock rows** (the open item of Section 6, decided). A product's stock row in
inventory-service is created through a **transactional outbox**: the product and
its `stock_row_request` row are inserted in one transaction, so either both
exist or neither does. A relay, every second, takes up to 100 `PENDING` requests
in product order and calls `POST /internal/stock` (Section 3) for each, with no
transaction open during a call, then records the answer in its own short
transaction:

| Answer | Status | Then |
|---|---|---|
| 201 `CREATED`, 200 `ALREADY_EXISTS` | `CREATED` | next request |
| 409 `PRODUCT_OWNED_BY_OTHER_MERCHANT` | `CONFLICT`, final, an ERROR | the merchant's listings are invalidated; next request |
| any other 4xx except 401 | `REJECTED`, final, an ERROR (a bug) | the merchant's listings are invalidated; next request |
| 401, 5xx, timeout, no connection | stays `PENDING`; `attempts` and `lastError` updated | the pass stops; waits 1, 2, 4 … up to 60 s, reset on success |

- **Until the row exists**, the product reads as out of stock, and reserving it
  fails with `UNKNOWN_PRODUCT`. inventory-service creates a row at 0, so a new
  product is out of stock until its merchant adds some anyway.
- **`CONFLICT`** means inventory-service already has a row for this product id
  that belongs to another merchant: a row created by hand (services/README.md
  shows how), or catalog-service's H2 restarted and its ids began again while
  inventory-service kept its rows (Section 7). That row's stock is not this
  product's, so the product is **always** out of stock, the bump makes that
  visible at once, and order-service gets `stockRow: CONFLICT` and must refuse
  the line. Without this state, the listing would show, and a checkout would
  reserve, another merchant's stock under this product id.
- **`REJECTED`** is treated exactly like `CONFLICT` wherever availability is
  decided: always out of stock, with the same invalidation, and order-service
  must refuse the line. Whatever inventory-service may hold for the id is not
  trusted, because catalog-service never got a row created for it.
- **A mark only moves a `PENDING` row**, so a final status never changes, and
  two instances that both handle a request (a rolling update) both get 201/200
  and mark once. Requests need no order between them; stopping the pass on a
  retryable failure only saves calls that would fail the same way.
- Without `BASKETRY_INTERNAL_API_KEY`, every call gets 401: the relay keeps
  retrying with an ERROR in the log, and listings show `UNKNOWN`.
- Rejected alternatives: a synchronous call after the commit with a retry job
  needs the same pending marker, without the atomicity; a call inside the create
  request would answer 503 while inventory-service is down. Requests in a column
  of `product` would make the relay a second writer of product rows.

### inventory-service
```
StockLevel                      -- table stock_level
  productId (PK)                -- catalog-service's product; reference only
  merchantId                    -- the owner; reference only; never changes
  quantity: BIGINT              -- stock still available: reservations in progress are already taken off
  createdAt, updatedAt

StockReservation                -- table stock_reservation; one row per (order, product), kept for good
  orderId, productId            -- composite PK; the idempotency key of reserve and release
  quantity: int                 -- as requested, capped at 2^31-1; taken only while RESERVED
  status: RESERVED | RELEASED | FAILED
  reasonCode                    -- set exactly when FAILED: INSUFFICIENT_STOCK, UNKNOWN_PRODUCT,
                                --   QUANTITY_OVER_LIMIT or RELEASED_BEFORE_RESERVE; the
                                --   reasonCode of order.stock.reservation-failed
  createdAt
  releasedAt                    -- set exactly when RELEASED

OutboxEvent                     -- one row per result to publish; the same table as payment-service's
  id (PK), eventId (unique), topic
  messageKey                    -- the orderId
  payload, status: PENDING | SENT, attempts, lastError, createdAt, sentAt
```
Postgres is the source of truth for `quantity` and **the only thing that
decides a success**. Redis holds a mirrored counter per product
(`inventory:stock:{productId}`), used as a fast atomic pre-check, so
checkouts that will obviously fail are rejected before Postgres is written.

**Concurrency design:** stock is **reserved at checkout initiation**, in
parallel with the per-merchant payment authorizations (see Section 4) —
not deferred until `OrderSucceeded`. This closes the race where two
buyers could both pass authorization against the same last unit before
either order finishes. Each reservation runs these steps in order:

1. **Stored row.** In one transaction, look up `(orderId, productId)`. If a
   row exists, the request is a repeat: answer from the row (below) and stop.
   Redis is not touched, so a redelivery never takes from the counter again.
2. **Limit.** More than 1,000 units in one line fails with
   `QUANTITY_OVER_LIMIT`, before either store is touched.
3. **Redis pre-check.** A Lua script in one call reads the counter and, if it
   holds at least the quantity, decrements it with `DECRBY`; otherwise it
   changes nothing and answers "insufficient". The check and the decrement are
   one script, so there is no race window between them. A missing key answers
   "not loaded" (not zero stock): the service reads the quantity from Postgres,
   loads the counter with `SET NX` (so it never overwrites a counter another
   request just loaded) and runs the script once more. A product with no stock
   row gets no counter.
   - **"Insufficient" is confirmed** with a plain read of Postgres (no lock).
     Only if Postgres also has too little does the reservation fail
     (`INSUFFICIENT_STOCK`) without a Postgres write. If Postgres has enough,
     the counter has drifted and the gate decides.
4. **Postgres gate.** In one transaction:
   `UPDATE stock_level SET quantity = quantity - :q WHERE product_id = :id AND quantity >= :q`,
   then the insert of the reservation row, then its outbox row. Zero rows
   updated stores a `FAILED` row instead: `INSUFFICIENT_STOCK`, or
   `UNKNOWN_PRODUCT` if there is no stock row. Two concurrent first requests
   of the same pair cannot both insert: the loser's insert fails on the
   primary key, which also rolls its decrement back, and a **new** transaction
   answers from the winner's row (as in payment-service).
5. **After the commit.** A failed gate (or an exception) gives the counter
   back what this request took from it. A success that went past a counter
   saying "insufficient" (step 3) subtracts its quantity from the counter now,
   which may take the counter below zero. So every success is subtracted from
   the counter exactly once. Without that last rule, ordinary concurrency would
   leave the counter too high: a counter often says "insufficient" only
   because another request took from it and has not committed yet, and if
   that one then loses the gate, it gives its take back.
   **The counter may be briefly negative, and that is harmless**: every take
   on it answers "insufficient", the confirming read overrules that, and
   reconciliation sets it back to Postgres's value. **There is deliberately no
   floor at zero**: in the race just described, a floored subtraction would
   leave the counter at 0, the loser's give-back would then lift it to one
   above Postgres, and the drift this rule removes would be back.

**Failed outcomes are stored too.** Otherwise a reservation that failed for
lack of stock, redelivered after a restock, would succeed for an order that
order-service has already failed, and nobody would ever release it.

**A repeat answers the question it asked**, from the stored row, and writes a
new outbox row, because the first publish may have been lost:

| Request | No row | `RESERVED` | `RELEASED` | `FAILED` |
|---|---|---|---|---|
| reserve | decide (steps 2–5) | `reserved` | `reserved` (it was reserved, as payment's `charged` after a refund) | `reservation-failed`, the stored code |
| release | store a `FAILED` **tombstone** (`RELEASED_BEFORE_RESERVE`), WARN; `released` | `RESERVED` → `RELEASED` in one conditional update, give the **stored** quantity back; `released` | `released` | `released` (nothing was taken) |

- The tombstone exists because a release can come before its reserve once
  order-service gives up on a reservation that never answered (Section 2,
  order-service: the timer).
  The late reserve then fails with `RELEASED_BEFORE_RESERVE` and takes
  nothing; without the tombstone it would take stock nobody releases.
- Two overlapping releases move the row once: the second update matches no
  row, gives nothing back, and still publishes `released`.
- A repeat with a different quantity keeps the first result and logs a WARN.
  A release always gives back the stored quantity, never the request's.
- **A request's quantity can be any positive whole number** (Section 5), and
  one above 2^31-1 is stored as 2^31-1. So the quantity of a `FAILED` row or a
  tombstone means "the request asked for at least this much", and is **never
  used in arithmetic**: a `FAILED` row took nothing, and a tombstone gives
  nothing back. Only a `RESERVED` row's quantity (at most 1,000, exact) is ever
  given back.
- A release adds to Postgres in the same transaction as the status change,
  and adds to the counter after the commit.

**Redis drift, and how it is repaired.** Redis and Postgres are two stores
with no shared transaction, so the counter can drift either way:

| Failure point | Counter ends up |
|---|---|
| Crash after the Redis decrement, before the gate commits (the redelivery then takes from the counter again before committing) | too low |
| Gate committed a failure, crash before the counter got its take back | too low |
| Release committed, crash before the counter was incremented (a redelivery sees `RELEASED` and does not add again) | too low |
| A Redis reply lost after the command ran (the take is treated as unknown and never given back) | too low |
| The commit succeeded but the client saw an error, so the take was given back | too high |
| Redis was down or skipped while Postgres changed | too high or too low |
| Reconciliation's overwrite racing a reservation or release in flight | too high or too low |

Neither direction changes an answer: a counter too high only lets more
requests reach the gate, and a counter too low is overruled by the confirming
read. Repairs, with Postgres always winning:
- **Startup reconciliation**, in the background so startup never waits for
  Redis: every counter is overwritten from Postgres, in product order, 500 rows
  per page. It repairs whatever a crash of this service left behind.
- **Periodic reconciliation**, the same, every 5 minutes. Drift costs only
  speed, so a slow interval is enough.
- **Lazy loading** (`SET NX`) of a missing counter, for example after Redis
  restarted while the service kept running.

**Every Redis increment only applies if the key exists** (a second Lua
script: `EXISTS`, then `INCRBY`). A plain `INCRBY` on a missing key would
create a counter holding only the delta; a missing counter must stay missing
until it is loaded from Postgres. Releases, the give-back after a failed gate,
the subtraction in step 5 and adjustments all use it.

**Redis unavailable.** Redis is never needed. Every Redis call goes through
one guard: any error or timeout means "unavailable", the request goes on with
the Postgres gate alone (still correct), and Redis is skipped for the next
10 seconds instead of timing out on every request. A take that failed is
treated as not taken, even if its reply was merely lost, so nothing is ever
given back that may not have been taken. The client uses 500 ms timeouts and
rejects commands at once while disconnected, so a queued command can never
run after its caller has moved on. Redis is not part of the health endpoint,
so an unreachable Redis never makes the pod unready. Releases and adjustments
also go on without it. The counters it missed are repaired by reconciliation.

**Stock rows and owners.** A product's row is created once, at zero (a new
product shows as out of stock until its merchant adds some), for the merchant
that owns it, and is never deleted. Creating it again for the same merchant
changes nothing; another merchant is refused
(`PRODUCT_OWNED_BY_OTHER_MERCHANT`). catalog-service will create the row
through `POST /internal/stock` (Section 3) when a product is created; until
it exists, rows are created by calling that endpoint by hand
(services/README.md).

**Adjustments are deltas.** A merchant adds or removes units (up to 1,000,000
either way, never 0) in one conditional statement, so an adjustment commutes
with reservations in flight and needs no compare-and-set. A decrease below zero
is refused (`STOCK_WOULD_GO_NEGATIVE`), and so is an increase that would take
the stock above 1,000,000 (`STOCK_LIMIT_EXCEEDED`). A decrease is always
allowed above that limit, because releases can lift stock above it. The
counter gets the same delta, never a `SET`, which would give back what
reservations in flight have taken from it.

**Bounds** are enforced by the application only: ids positive (up to
`Long.MAX_VALUE`), a reservation at most 1,000 units, an adjustment at most
1,000,000 either way. The database checks only what the application always
guarantees (stock never negative, status and reason code consistent). An upper
CHECK on stock would make a release onto a product at the limit fail, and a
database error caused by a valid request is retried forever. `quantity` is a
BIGINT for the same reason: a release can lift stock above the adjustment
limit, then a restock and another release can lift it further, with no bound an
INTEGER would hold. The largest valid values are tested against every column.

The cost of this design is one synchronous Postgres write per line item,
which is acceptable at this scale. Rejected alternatives: Redis AOF
persistence only shrinks the window in which a crash can lose a decrement (to
about a second), and Postgres rebuilds the counters anyway; using Postgres
alone would be correct but would remove the atomic Redis step this design is
meant to show.

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
PaymentTransaction              -- table payment_transaction (TRANSACTION is an SQL keyword)
  id (PK), transactionRef (unique)
  orderId, merchantId           -- references only; unique together: the idempotency key
  amount: BigDecimal            -- NUMERIC(12, 2); single currency, GBP, so no currency field
  cardLast4                     -- the only part of the card number ever stored
  responseCode                  -- ISO 8583-style, from the table above
  status: APPROVED | DECLINED | REFUNDED
  createdAt
  refundedAt                    -- set exactly when status is REFUNDED

OutboxEvent                     -- one row per result to publish (Section 5)
  id (PK), eventId (unique), topic
  messageKey                    -- the orderId
  payload                       -- the exact JSON to publish
  status: PENDING | SENT, attempts, lastError, createdAt, sentAt
```
An approved authorization is captured immediately (authorize + capture
in one step). A refund of an `APPROVED` transaction always succeeds and
moves it to `REFUNDED`. Because results are deterministic, every
single-outcome saga path in Section 4 can be reproduced in demos just by
choosing a card number.

- **Idempotency: the row is the record**, with no separate dedupe table. Every
  charge stores a row, `DECLINED` included. A repeated charge request finds it
  by `(orderId, merchantId)`, and a refund finds it by `transactionRef`. Two
  concurrent first requests cannot both insert: the loser's insert fails on the
  unique constraint, its transaction is rolled back, and a **new** transaction
  answers from the winner's row. It must be a new one, because on PostgreSQL a
  failed statement aborts the whole transaction.
- **A repeated request is never charged again**, and its stored result is
  published again, as a new outbox row, because the first publish may have been
  lost. It answers the question it asked:
  - charge request, row `APPROVED` **or `REFUNDED`**: `charged`, with the same
    `transactionRef`. A charge is still a charge after its refund.
  - charge request, row `DECLINED`: `declined`, with the stored code.
  - refund request, row `REFUNDED`: `refunded`.
- **Same key, different payload** (same `orderId` and `merchantId`, different
  amount or card): the first result stands and is published again, nothing is
  authorized, and a WARN names both amounts and both last-4 digits.
  **Known limitation:** only the last 4 digits are stored, so a different card
  with the same last 4 digits cannot be detected.
- **Refunds.** `APPROVED` becomes `REFUNDED` in one conditional update (only
  from `APPROVED`), so two overlapping refunds move the row once and both
  publish `refunded`. These are rejected, change nothing and publish nothing;
  the request goes to the refund dead-letter topic (Section 5):
  - an unknown `transactionRef`;
  - a `DECLINED` transaction;
  - a `transactionRef` whose `orderId` or `merchantId` is not the request's.

  A refund cannot overtake its charge. The reference is made in the charge's
  transaction and leaves only through the outbox after the commit, and
  `declined` carries no reference. So an unknown reference means data loss
  (an H2 restart, a wiped database) or a bug.
- **Validation** (a violation is rejected, as above):
  - `orderId` and `merchantId` positive.
  - `amount` > 0, at most 2 decimal places (more is refused, never rounded),
    and at most 9,999,999,999.99, the column's maximum. A larger amount would
    otherwise become a database error, which is retried forever.
  - `cardNumber` 12 to 19 ASCII digits. No Luhn check: the mock approves any
    number not in its table.
  - `transactionRef` in the format below.
- **`transactionRef`** is `TXN-` and 32 lowercase hex digits of a random UUID
  (122 bits from `SecureRandom`), so it cannot be guessed from an order. It is
  made once, when the row is inserted, for every row (a declined one too, for
  tracing, though only `charged` carries it), and a repeat reads the stored one.
- **The card number** in the contract is used only for the table lookup. It is
  never stored (only the last 4 digits), never logged and never put in an
  exception message, and the request object masks it in `toString`. A test runs
  every path with Hibernate's SQL parameter logging on and checks that no log
  line and no stored value contains it. Its copies in Kafka: Section 5.

**Test strategy for mixed outcomes:** a buyer pays a whole order with
one card, so with the live mock every merchant in an order gets the same
result. The mixed-outcome path ("2+ merchants, one declines", plus its
compensating refunds) is therefore verified by **integration tests** that
publish fake `MerchantCharged` / `MerchantDeclined` events directly to
`order-service`'s consumer, bypassing `payment-service`. It is validated
by tests, not by a live demo. There is deliberately no per-merchant
override in the mock. A live mixed outcome comes from stock instead: a cart
with one line out of stock fails while every merchant is charged, so the
charged merchants are refunded (order-service's end-to-end check will run it).

### order-service
```
CustomerOrder                   -- table customer_order (ORDER is an SQL keyword)
  id (PK)                       -- from customer_order_id_seq; the orderId of every saga event
  orderReference (unique)       -- ORD- + 20 random base32 characters; the only id the API shows
  buyerAccountId                -- the token's sub; reference only
  idempotencyKey                -- the Idempotency-Key header; unique together with buyerAccountId
  requestFingerprint            -- HMAC-SHA256 of the canonical request (below), hex
  status: PENDING | SUCCEEDED | FAILING | FAILED
  needsAttention: boolean       -- true exactly while a leg is STUCK
  couponCode: nullable          -- always null until promotion-service exists
  totalAmount: NUMERIC(12, 2)   -- the sum of the merchant charges' amounts
  deadlineAt                    -- createdAt + the saga deadline (2 min)
  createdAt
  decidedAt                     -- set when status first leaves PENDING
  completedAt                   -- set when status becomes SUCCEEDED or FAILED; reset on a reopen

MerchantCharge                  -- table merchant_charge; the charge leg, one per merchant in the order
  orderId (FK), merchantId      -- composite PK; payment-service's idempotency key
  subtotal: NUMERIC(12, 2)      -- the sum of the merchant's line amounts
  discount: NUMERIC(12, 2)      -- 0.00 until promotion-service exists
  amount: NUMERIC(12, 2)        -- subtotal - discount; what is charged
  commissionRate: NUMERIC(5, 2) -- snapshot from merchant-service at checkout
  commission: NUMERIC(12, 2)    -- amount x commissionRate / 100, HALF_UP, scale 2
  status: REQUESTED | CHARGED | DECLINED | TIMED_OUT | REFUND_REQUESTED | REFUNDED | STUCK
  transactionRef                -- set from the charged result
  reasonCode                    -- the response code of a decline, or NO_RESULT for TIMED_OUT
  resends, compensationDueAt    -- the refund's resends so far, and when it is next due

OrderLine                       -- table order_line; one per cart line, and its reserve leg
  orderId (FK), productId       -- composite PK; inventory-service's idempotency key
  merchantId                    -- snapshot from catalog-service at checkout
  productName                   -- snapshot, for the order history
  unitPrice: NUMERIC(12, 2)     -- snapshot of catalog-service's current price
  quantity: int                 -- 1-1,000
  lineAmount: NUMERIC(12, 2)    -- unitPrice x quantity (exact, no rounding)
  status: REQUESTED | RESERVED | FAILED | TIMED_OUT | RELEASE_REQUESTED | RELEASED | STUCK
  reasonCode                    -- inventory-service's code of a failed reservation, or NO_RESULT
  resends, compensationDueAt    -- the release's resends so far, and when it is next due

OutboxEvent                     -- as payment-service's, plus CANCELLED
  id (PK), eventId (unique), topic
  messageKey                    -- the orderId
  payload                       -- the exact JSON to publish; card number removed once SENT or CANCELLED
  status: PENDING | SENT | CANCELLED, attempts, lastError, createdAt, sentAt
```
There is no server-side cart. The cart lives only in the Buyer front-end
and is sent whole in the `POST /orders/checkout` body. `order-service`
re-validates every line at checkout (below) and does not trust the prices or
availability the client sends. Hiding unavailable products in the UI
(FR-B6/FR-M6) is a convenience; the real check is server-side.

**Checkout, in order.** Nothing is stored until step 6, and no transaction is
open during a call to another service.
1. **The body** (400 for any broken rule, all named at once; Section 3).
2. **The capability**: a token without `BUYER` (Admin) gets 403.
3. **The fingerprint key**: if `BASKETRY_ORDER_FINGERPRINT_KEY` is unset, 503
   `FINGERPRINT_KEY_NOT_CONFIGURED`, a fixed error, for every checkout.
4. **The Idempotency-Key**: an order with the same `(buyerAccountId, key)` and
   the same fingerprint is answered as it stands now (200, Section 3); the same
   key with another fingerprint is 409 `IDEMPOTENCY_KEY_REUSED`. Nothing below
   runs again for a replay.
5. **Three synchronous calls**, then the cart rules. First, the internal key
   must be configured, else 503 `INTERNAL_API_KEY_NOT_CONFIGURED` before any
   call. It is checked here, after the idempotency lookup, because a replay
   calls nothing and is still answered without the key.
   - identity-service `GET /accounts/{buyerAccountId}`, with the buyer's own
     token forwarded (the endpoint allows "self"). `buyerActive = false` is 403
     `BUYER_DEACTIVATED`; a 404 (data loss) is 403 `BUYER_NOT_FOUND`. The
     answer's `merchantId` (the account's live merchant link) is kept for the
     `OWN_PRODUCT` rule; its email is never stored or logged.
   - catalog-service `GET /internal/products?ids=` with every line's product,
     in one call (a cart has at most 100 lines, catalog's limit).
   - merchant-service `GET /internal/merchants?ids=` with every merchant
     catalog named, in one call.
   - **Cart rules.** Every refused line is listed in one 409
     `CART_NOT_PURCHASABLE`, with the first reason that applies, in this order:
     `NOT_FOUND` (catalog does not know the product), `OWN_PRODUCT` (the product
     belongs to the merchant linked to the buyer's account: a merchant cannot
     buy from itself), `INACTIVE` (the product is deactivated),
     `MERCHANT_INACTIVE` (catalog's `merchantActive` **or** merchant-service's
     live `active` is false, or merchant-service does not know the merchant),
     `STOCK_ROW_UNAVAILABLE` (`stockRow` is `CONFLICT` or `REJECTED`). A
     `PENDING` stock row is not refused: its reservation fails with
     `UNKNOWN_PRODUCT`. Stock itself is not checked here; the reservation
     decides it.
   - **The price.** The server's total (below) must equal the body's
     `expectedTotal` (numerically, so `13.5` equals `13.50`), else 409
     `PRICE_CHANGED` with every line's current price and the current total.
     FR-B12: the buyer is never charged a total that was not shown.
6. **One transaction**: the order (`PENDING`), its charge legs and lines (all
   `REQUESTED`), and one outbox row per request: a charge per merchant, a
   reserve per line. Then 202 (Section 3); the relay publishes the requests.

A checkout refused at step 5 (400, 403, 409 or 503) stores nothing, so a retry
with the same Idempotency-Key runs again from step 1. Two concurrent first
requests with one key both run step 5; the second insert fails on the unique
`(buyerAccountId, idempotencyKey)`, and a **new** transaction answers from the
winner's order (200, or 409 if the fingerprint differs), as in payment-service.

**The fingerprint** is HMAC-SHA256 under `BASKETRY_ORDER_FINGERPRINT_KEY` of a
canonical form of the body: the lines sorted by `productId`, the coupon code,
the card number and `expectedTotal` at scale 2. It is keyed because a plain
hash of a card number could be reversed by trying every number. The key has
the internal key's rules: whitespace stripped, fewer than 32 characters fails
startup, unset means a WARN at startup and every checkout refused (step 3).
Changing the key makes the replay of an earlier request a 409.

**Amounts.** `lineAmount = unitPrice × quantity`, exact. A merchant's
`subtotal` is the sum of its lines, `amount = subtotal − discount` (the
discount is 0.00 until promotion-service exists), and `totalAmount` is the sum
of the merchant amounts, so **the merchant charges always sum to the order
total** (tested). The worst case fits every column: 100 lines × 1,000 units ×
99,999.99 = 9,999,999,000.00, below payment-service's maximum amount and
`NUMERIC(12, 2)`'s 9,999,999,999.99.

**Commission** (REQUIREMENTS Section 8): per merchant,
`commission = amount × commissionRate / 100`, rounded HALF_UP to scale 2, on
the amount actually charged (the subtotal after the discount; equal to the
subtotal while coupons are refused). `commissionRate` is merchant-service's
rate at checkout, snapshotted on the leg, so a later change of the rate never
alters an earlier order. It is recognised only on a `SUCCEEDED` order: it is
published in `order.succeeded` (Section 5) and nowhere else.

**Coupons.** promotion-service does not exist yet, so a `couponCode` with a
value is a 400 `COUPONS_NOT_SUPPORTED`. The check sits behind a
`CouponValidator` interface that promotion-service's client will implement. A
merchant whose net comes to zero will then get no charge leg (Section 2,
promotion-service), so the coordinator never assumes every merchant has one.

**Order status:**

| Status | Meaning | Final |
|---|---|---|
| `PENDING` | Results outstanding, none negative | no |
| `SUCCEEDED` | Every charge leg `CHARGED` and every line `RESERVED` | yes |
| `FAILING` | Failure decided; compensations or results still outstanding | no |
| `FAILED` | Failure decided and every leg settled (below) | yes, unless reopened |

`FAILING` is not a partial success: the all-or-nothing decision is already
made, and the buyer sees that the order failed and is being reversed (there
is still no `PARTIALLY_PAID`, REQUIREMENTS Section 10). A leg is **settled**
when it can never need a compensation again: a charge leg `DECLINED`,
`REFUNDED` or `TIMED_OUT`, a line `FAILED` or `RELEASED`. `needsAttention` is
true exactly while a leg is `STUCK`, which keeps the order `FAILING`.

**Who decides.** Results of one order arrive on six listener threads (one per
result topic), and the timer runs on another. Every one of them, in its own
transaction, first **locks the order row** (`SELECT ... FOR UPDATE`, waiting at
most 5 s, then a database error that the listener retries), then changes the
leg, then decides. So the results of one order are handled one at a time, and
different orders in parallel. Without the lock, two results committing
together would each see the other's leg as still open under READ COMMITTED,
neither would decide, and a healthy order would wait for the deadline and
fail. A test places exactly this race on PostgreSQL (postgresTest); H2 alone
does not prove it.

**The decision**, made in the transaction that changed a leg:
- `PENDING`, and every leg positive (`CHARGED`, `RESERVED`): `SUCCEEDED`, with
  an `order.succeeded` outbox row.
- `PENDING`, and a leg negative (`DECLINED`, a line `FAILED`, or `TIMED_OUT`):
  `FAILING`. In the same transaction every `CHARGED` leg becomes
  `REFUND_REQUESTED` and every `RESERVED` line `RELEASE_REQUESTED`, each with a
  request in the outbox. Legs still `REQUESTED` keep waiting for their result
  or the deadline.
- `FAILING`, and every leg settled: `FAILED`, with an `order.failed` outbox row.

**Results, by leg state.** Every result is matched to its leg by
`(orderId, merchantId)` or `(orderId, productId)`. A result for an order or a
leg order-service does not have (data loss, such as an H2 restart) is logged
as a WARN and skipped. "Drop" means: nothing changes, the offset is committed.

| Result | Leg is `REQUESTED` | Leg is `TIMED_OUT` | Any other state |
|---|---|---|---|
| `charged` | `CHARGED`, ref stored; if the order is `FAILING`, `REFUND_REQUESTED` at once | `REFUND_REQUESTED`, ref stored; a `FAILED` order is reopened (below) | drop (a repeat, or payment's re-publish after a refund) |
| `declined` | `DECLINED`, code stored | drop | drop |
| `refunded` | — | — | `REFUND_REQUESTED` or `STUCK`: `REFUNDED`; otherwise drop |
| `reserved` | `RESERVED`; if the order is `FAILING`, `RELEASE_REQUESTED` at once | drop (its release is already requested) | drop (a repeat, or inventory's re-publish after a release) |
| `reservation-failed` | `FAILED`, code stored | drop (the release is still answered) | drop |
| `released` | — | `RELEASED` | `RELEASE_REQUESTED` or `STUCK`: `RELEASED`; otherwise drop |

"—" cannot happen (no request was ever sent that it answers); it is logged as
a WARN and dropped.

This **replaces the earlier rule** that any result for an order in a final
state is a no-op: since the timer can give up on a leg, a final state no longer
means every result has arrived. A late `charged` is money taken, so it is
always refunded (refunds are idempotent by `transactionRef`). A late
`reserved` takes nothing: the release sent at the deadline either gave it back
or left a tombstone that made it fail (Section 2, inventory-service). After
each change the decision above runs again; a `STUCK` leg that settles clears
`needsAttention` if no other leg is `STUCK`.

**Reopen.** A late `charged` on a `FAILED` order moves it back to `FAILING`
(`completedAt` reset) until that refund confirms. Then it is `FAILED` again and
**publishes `order.failed` again**, a new outbox row with a new `eventId` and
the order's reasons as they stand. A consumer of `order.failed` must expect
more than one per order. An order can never move out of `SUCCEEDED`: every
charge leg is then `CHARGED`, and no result changes a `CHARGED` leg.

**The timer** runs every 5 s (fixed delay). Each pass takes up to 100 orders
with something due, and handles each in its own transaction under the order's
row lock:
- **The deadline.** An order still `PENDING` or `FAILING` with a leg
  `REQUESTED` after `deadlineAt` (2 min after checkout): every `REQUESTED` leg
  becomes `TIMED_OUT` (`reasonCode` `NO_RESULT`). A `PENDING` order becomes
  `FAILING`, with the compensations of the decision above. A timed-out charge
  leg has no `transactionRef`, so nothing can be refunded; if its charge
  arrives later, it is refunded then. **A timed-out line always gets a
  release**, whether its reserve was sent or not, so a reserve arriving later
  takes nothing.
- **Unsent requests.** In the same transaction, every outbox row of the order
  still `PENDING` on a request topic (a charge or a reserve) becomes
  `CANCELLED`, and its card number is removed. Otherwise a Kafka outage longer
  than the deadline would charge buyers for orders that already failed. A row
  the relay is sending at that moment can still reach the broker; its result
  is a late one, handled above.
- **Compensations.** A refund or release not confirmed 1 min after it was
  requested is sent again (a new outbox row, the same request: the refund by
  its `transactionRef`, the release with the line's quantity), at most 3
  times, 1 min apart. This covers every leg waiting for a compensation: a
  charge leg `REFUND_REQUESTED`, and a line `RELEASE_REQUESTED` **or
  `TIMED_OUT`** (whose release was requested at the deadline), with exactly the
  same resends. 1 min after the third resend the leg becomes `STUCK` and
  the order `needsAttention`. A `STUCK` leg is not resent; its late
  confirmation still settles it. Admin finds these orders through
  `GET /orders?needsAttention=true` (Section 3) and repairs the cause, usually
  a request in payment-service's or inventory-service's dead-letter topic that
  can be replayed by hand (Section 5).
- **Startup grace.** For 60 s after the service starts, the timer does nothing,
  so an order whose results are still queued in Kafka after a restart is not
  failed before the listeners have read them. The listeners run from the start.
- Settings: `basketry.order.saga-deadline` (2 min), `basketry.order.timer-interval`
  (5 s), `basketry.order.startup-grace` (60 s),
  `basketry.order.compensation.resend-interval` (1 min),
  `basketry.order.compensation.max-resends` (3) and
  `basketry.order.timer.batch-size` (100). A pass reads the orders past their
  deadline, those with a refund due and those with a release due, each up to the
  batch, and handles at most one batch of them, lowest id first. A failure on
  one order (a lock timeout) is logged and left for the next pass. The relay
  and the timer get a scheduler thread each (`spring.task.scheduling.pool.size`
  2), so a long pass of one never holds up the other.
- A charged result for a leg still `REQUESTED` on a `FAILING` order goes
  straight to `REFUND_REQUESTED` (with its `transactionRef`); a late charge for a
  `TIMED_OUT` leg keeps its `reasonCode` `NO_RESULT`, because that leg is what
  failed the order.
- **Two instances** are safe: the order's row lock decides who acts, and every
  change is conditional on the leg's state.

**The card number** is used only to build the charge requests. Its copies:
- the checkout request, in memory only. The request object masks it in
  `toString`, and **no access log or request logging ever records the checkout
  body** (no payload logging filter, Tomcat's access log never configured with
  the body). A 400 never quotes a value, and the error handler never passes a
  Jackson message through (it can quote the number);
- the charge request's outbox row, until it is `SENT` or `CANCELLED`. The same
  update that marks it replaces `cardNumber` in the payload with `null`, so a
  replay of the stored payload by hand is dead-lettered by payment-service,
  never charged. Every other field keeps its exact text (`2.50` stays `2.50`:
  the payload is re-read with decimals as BigDecimal). A row the relay could not
  publish keeps the number for the retry;
- the request topic and payment-service's dead-letter topic (Section 5), for
  the topic's retention of 7 days;
- PostgreSQL's write-ahead log and the old row version, until they are
  recycled and vacuumed, and any backup taken meanwhile. Accepted for this
  mock; a real system sends a token (Section 3).

The fingerprint holds it only inside a keyed HMAC. A test sends a sentinel
number down every path (checkout, replay, 400s, relay, cancel, every log at
DEBUG, Spring Kafka at TRACE) and checks every log line and every stored value.

**Logging that would print it**, so never run these at TRACE in order-service:
`org.hibernate.orm.jdbc.bind` (Hibernate's parameter logging prints the
outbox payload while it is PENDING), `org.apache.kafka.clients` and
`org.springframework.kafka.core.DefaultKafkaProducerFactory` (both print whole
records; Section 5). Everything Spring Kafka logs of a record otherwise is its
topic and partition only (the record formatter), and a failed send is logged
by order-service's own producer listener with the topic and the key: Spring
Boot's default one logs up to 100 characters of the value at ERROR.

**orderId and H2.** `orderId` is payment-service's and inventory-service's
idempotency key. If order-service restarts on H2 while they keep their data,
its ids begin at 1 again, and a new order 7 gets the stored answer of the old
order 7. Restart everything together on H2 (Section 7).

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

Every endpoint except `/internal/**` (below) needs a bearer JWT from identity-service. Tokens are verified
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
     merchant is saved, whatever the link result, with one exception: if the
     merchant's row stays locked by another request for more than 5 s while
     the link result is recorded, the answer is 503 `MERCHANT_BUSY` with the
     `merchantCode`. The merchant stays `PENDING`, and a retry of the link
     completes it (`ALREADY_LINKED` if identity-service had linked). The body has the
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
  - 503 `MERCHANT_BUSY` (with the `merchantCode`) if the row stays locked for
    more than 5 s while the result is recorded; the merchant stays `PENDING`.
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
  - The `PATCH` reads the merchant with a row lock (Section 2) and waits at
    most 5 s for another request's lock. Then it answers 503 `MERCHANT_BUSY`
    and applies nothing; the client can retry.
- `GET /internal/merchants?ids=1,2,3` (internal key; for order-service's
  checkout, Section 2, order-service). 1 to 100 positive ids, else 400; the
  limit counts repeated ids, and `1,,2` is a 400 (as catalog-service's
  `/internal/products`). Response `{content: [...]}` in id order, a repeated id
  answered once, unknown ids left out. Each item `id`, `active` and
  `commissionRate`, the rate as a JSON number keeping its scale (`5.00`, never
  `5.0`). A plain read, no row lock: the answer is the state at that moment,
  and order-service snapshots the rate. A `PENDING` merchant is answered like
  any other.
- **`/internal/**`** follows identity-service's rules exactly (Section 3,
  identity-service): its own security chain, the shared key in
  `X-Internal-Api-Key` only (never a JWT), compared in constant time, no CORS
  headers, and the same bare 401 for a missing key, a wrong key or a user JWT.
  It uses the key merchant-service already reads to call identity-service
  (`BASKETRY_INTERNAL_API_KEY`, same rules); unset means a WARN at startup, as
  now, and every internal call refused as well as onboarding. The Ingress must
  never route it externally.

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

Three security chains:
- **Public reads**, GET and HEAD on `/products` and `/products/{id}` only
  (matched by method: POST and PATCH on the same paths are not public; HEAD
  answers as GET without a body). No token is needed,
  and **none is ever read**: an expired, broken or foreign token the Buyer app
  still sends is ignored, so browsing never becomes a 401 (which would log the
  buyer out), and the JWKS is never fetched, so identity-service being down
  never makes browsing a 503.
- **Everything else**: a bearer JWT, verified exactly as in merchant-service and
  inventory-service (identity-service's JWKS at `BASKETRY_IDENTITY_URL`, fetched
  on first use, cached, 2 s / 5 s timeouts, 503 when a token's key is not cached
  and the JWKS cannot be fetched, 401 for a malformed, expired, tampered or
  unpublished-key token or none). The capability is checked at the endpoint.
  `GET /actuator/health` (and its probe groups) needs no token, shows no details
  and includes neither Redis nor inventory-service.
- **`/internal/**`**: identity-service's rules exactly (the shared key in
  `X-Internal-Api-Key` only, never a JWT, no CORS, the same bare 401 for a
  missing key, a wrong key or a user JWT; an unset key refuses every call).

As everywhere: request validation runs before the capability check, so an
invalid request is a 400 for anyone, never a 403. Every error is a problem
detail. A field takes only its own JSON type: `"2.50"` for a price, `0` or
`"false"` for a boolean, `5` for a string, and `1.5` for a whole number are
400s, never converted. CORS allows `basketry.cors.allowed-origins` (GET, HEAD,
POST and PATCH, no credentials). Product fields and their rules: Section 2.

- `GET /products` (public; FR-B3). Paging as `GET /accounts`: `page` from 0
  (default 0), `size` 1..100 (default 20), anything out of range a 400, id
  order, a page past the end 200 with empty `content`. Optional `merchantId`
  (positive) and `category` (1 to 50 characters, exact and case-sensitive).
  Filters by `merchantId`, not by merchant code: `merchant.status.changed`
  carries the code only after a merchant's first status change, and no token or
  endpoint gives a buyer the code of a merchant that never changed status. Only
  visible products (Section 2: active, of an active merchant). Response
  `{content, page, size, totalElements, totalPages}`; each item `id`,
  `merchantId`, `name`, `description`, `category`, `price`, `availability`
  (`IN_STOCK`, `OUT_OF_STOCK` or `UNKNOWN`, Section 2). Cached in Redis
  (Section 2); availability never is.
- `GET /products/{id}` (public). The listing's fields, with its own
  availability. **404 for a product the listing hides** (inactive, or of an
  inactive merchant), as for one that does not exist; the Buyer app shows a cart
  line that gets a 404 as "no longer available". A non-numeric or non-positive
  id is a 400. Not cached.
- `POST /products` (FR-M2; a token with `MERCHANT`). Body `name`, `category`,
  `price` required, `description` optional. The owner is the token's
  `merchantId`, never a field: any other field (`merchantId`, `active` …) is a
  400 naming it, and a new product is always active. **201** with
  `Location: /manage/products/{id}` (the public URL hides the product while its
  merchant is inactive) and the management fields below, `stockRow: PENDING`.
  A buyer or an admin gets 403 (admin can read every product, change none).
- `PATCH /products/{id}` (FR-M2, FR-M3; the owning merchant only). Accepts
  `name`, `description`, `category`, `price` and `active`, at least one.
  Omitted means unchanged; an explicit `null` is a 400 naming it, never "no
  change" or `false`; `""` clears the description; any other field is a 400
  naming it. `active: false` deactivates and `true` reactivates; there is no
  DELETE. 200 with the product after the change; values that do not change are a
  200 no-op. Another merchant's product and one that does not exist are both
  403, decided after the body is checked. The update reads the product with a
  row lock and waits at most 5 s for another request's lock; then **503
  `PRODUCT_BUSY`**, nothing applied, and the client can retry.
- `GET /manage/products` (the Merchant portal and Admin). Paging as
  `GET /products`. A merchant sees only its own products, **inactive ones
  included**, also while it is itself inactive; a `merchantId` other than its
  own is a 403. Admin sees every product, or one merchant's with `merchantId=`
  (positive, else 400). A buyer-only token gets 403. Each item: `id`,
  `merchantId`, `name`, `description`, `category`, `price`, `active`,
  `stockRow` (`PENDING`, `CREATED`, `CONFLICT` or `REJECTED`, Section 2),
  `createdAt`, `updatedAt`. Never cached.
- `GET /manage/products/{id}` (Admin, or the merchant that owns it). As
  inventory-service's `GET /stock/{productId}`: a non-admin gets 403 both for
  another merchant's product and for one that does not exist; only an admin gets
  a 404.
- `GET /internal/products?ids=1,2,3` (internal key; for order-service's
  re-validation of every checkout line, **the real check of purchasability**).
  1 to 100 positive ids, else 400. The limit counts repeated ids; `1,,2` is a
  400. Response `{content: [...]}` in id order, a
  repeated id answered once, unknown ids left out. Each item `id`, `merchantId`,
  `name`, `price` (the current one), `active`, `merchantActive`, `stockRow`
  (all four statuses). Hidden products are included, so order-service can tell
  why a line is refused: a line is purchasable only if `active`,
  `merchantActive`, and `stockRow` is neither `CONFLICT` nor `REJECTED` (a
  `PENDING` one fails at reservation with `UNKNOWN_PRODUCT`). `merchantActive`
  is catalog-service's local copy: true for a merchant that never changed
  status, and it lags `merchant.status.changed` by seconds, so order-service
  also asks merchant-service for the live status. No merchant code and no
  commission rate: order-service reads the rate from merchant-service's
  `GET /internal/merchants` (Section 3, merchant-service).

**inventory-service**

Reservations and releases arrive over Kafka (Section 5). The REST API serves
the Merchant portal and Admin (FR-M4) and, internally, catalog-service
(FR-M6). Every stock body has `productId`, `merchantId` and `quantity`, all
JSON whole numbers read as Java longs; `quantity` is the stock still
available, with reservations in progress already taken off (FR-M5).

Bearer tokens are verified exactly as in merchant-service: identity-service's
JWKS at `BASKETRY_IDENTITY_URL`, fetched on first use, cached, 2 s / 5 s
timeouts, 503 (problem detail) when a token's key is not cached and the JWKS
cannot be fetched, 401 for a malformed, expired, tampered or unpublished-key
token or none at all. Request validation runs before the capability check: an
invalid request is a 400 for anyone, never a 403. Every error is a problem
detail; a 409 carries a `code`. A number with a decimal (`1.5`, also `2.0`)
for a whole-number field is a 400, and so is `"5"` or `true` for a number:
one global Jackson setting, as in identity-service. CORS allows
`basketry.cors.allowed-origins` (GET and POST, no credentials).

- `GET /stock` (Admin, or a merchant). Same paging as `GET /accounts`: `page`
  from 0 (default 0), `size` 1..100 (default 20), anything out of range a 400,
  product order, a page past the end 200 with empty `content`. Response:
  `{content, page, size, totalElements, totalPages}`. A merchant sees only its
  own products (the token's `merchantId` claim, which counts only with the
  `MERCHANT` capability). Admin sees every product, or one merchant's with
  `merchantId=` (positive, else 400); a merchant passing any `merchantId` but
  its own gets 403. A buyer-only token gets 403.
- `GET /stock/{productId}` (Admin, or the merchant that owns it). As
  `GET /merchants/{code}`: ownership is known only after the lookup, so a
  non-admin gets 403 both for another merchant's product and for one that does
  not exist; only an admin gets a 404.
- `POST /stock/{productId}/adjustments` (the owning merchant only; Admin can
  view stock but not adjust it). Body `{"delta": n}`: a whole JSON number, not
  null, not 0, at most 1,000,000 either way; any other field is a 400 naming
  it. A non-owner (or a missing product) gets 403. Applied as one conditional
  statement (Section 2), so it commutes with reservations in flight. 200 with
  the stock after the change; 409 `STOCK_WOULD_GO_NEGATIVE` (fewer units
  available than the decrease) or `STOCK_LIMIT_EXCEEDED` (an increase above
  1,000,000). A retry after a lost response applies twice (Section 6).
- `POST /internal/stock` (internal key only). Body
  `{"productId": p, "merchantId": m}`, both positive whole numbers; any other
  field is a 400 naming it (a new product always starts at 0). **201**,
  `outcome: CREATED`: the row is created at 0. **200**, `outcome:
  ALREADY_EXISTS`: the same merchant already has it, nothing changed. **409**
  `PRODUCT_OWNED_BY_OTHER_MERCHANT`: the owner never changes. catalog-service
  will call it when it creates a product (Section 6).
- `GET /internal/stock?productIds=1,2,3` (internal key only). 1 to 100
  positive ids, else 400. Response `{content: [{productId, quantity}]}` in
  product order, a repeated id answered once, and products with no stock row
  left out (catalog-service shows them as out of stock). Read from Postgres,
  never from the Redis counters.
- The internal endpoints follow identity-service's `/internal/**` rules
  exactly (Section 3, identity-service): `X-Internal-Api-Key` only, from
  `BASKETRY_INTERNAL_API_KEY` (whitespace stripped, at least 32 characters or
  startup fails, unset = a WARN and every internal call refused), compared in
  constant time, their own security chain that never reads `Authorization` and
  sends no CORS headers, and the same bare 401 for a missing key, a wrong key
  or a user JWT. The Ingress must never route them externally.
- `GET /actuator/health` (and its `liveness` and `readiness` groups) needs no
  token, shows no details and does not include Redis (Section 2). No other
  actuator endpoint is exposed.

**order-service**

Bearer tokens are verified exactly as in merchant-service (identity-service's
JWKS at `BASKETRY_IDENTITY_URL`, fetched on first use, cached, 2 s / 5 s
timeouts, 503 when a token's key is not cached and the JWKS cannot be fetched,
401 for a malformed, expired, tampered or unpublished-key token or none).
Request validation runs before the capability check: an invalid request is a
400 for anyone, never a 403. Every error is a problem detail, and a 403, 409
or 503 a client may need to tell apart carries a `code`. A field takes only
its own JSON type, as everywhere (`"3"` for a number, `1.5` for a whole number,
`5` for a string are 400s, never converted); any field not listed is a 400
naming it. CORS allows `basketry.cors.allowed-origins` (GET and POST, the
headers `Authorization`, `Content-Type` and `Idempotency-Key`, `Location`
exposed, no credentials). There is no `/internal/**` endpoint yet.
`GET /actuator/health` (and its probe groups) needs no token, shows no details
and includes no other service.

- `POST /orders/checkout` (a token with `BUYER`; FR-B7 to FR-B12). Starts the
  saga; the steps and their order are in Section 2, order-service.
  - **Header `Idempotency-Key`**, required: 8 to 64 characters of
    `[A-Za-z0-9_-]`, else 400.
  - **Body:**
    ```json
    {"lines":[{"productId":42,"quantity":3}], "couponCode":null,
     "cardNumber":"4000000000000002", "expectedTotal":13.50}
    ```
    `lines`: 1 to 100, each `productId` a positive whole number and `quantity`
    a whole number from 1 to 1,000 (inventory-service's limit); a repeated
    `productId` is a 400. `couponCode`: absent or `null`; a value is a 400
    `COUPONS_NOT_SUPPORTED` until promotion-service exists. `cardNumber`: a
    string of 12 to 19 ASCII digits (payment-service's rule, so a valid
    checkout never sends a request it would dead-letter). `expectedTotal`: the
    total the buyer was shown, a JSON number above 0 with at most 2 decimal
    places, at most 9,999,999,999.99. No 400 ever quotes the card number.
    *(A real system would send a tokenized payment-method reference, never a
    raw card number.)*
  - **202** with `Location: /orders/{reference}` and the order (below), in
    `PENDING`, for the first request. The saga runs after the answer; the Buyer
    app polls `GET /orders/{reference}`.
  - **200** with the same `Location` and the order **as it stands now** for a
    replay: the same key and the same body.
  - 403: no `BUYER` capability; `BUYER_DEACTIVATED`; `BUYER_NOT_FOUND`.
  - 409: `IDEMPOTENCY_KEY_REUSED` (same key, another body);
    `CART_NOT_PURCHASABLE` with `lines: [{productId, reason}]` for every refused
    line (`NOT_FOUND`, `OWN_PRODUCT`, `INACTIVE`, `MERCHANT_INACTIVE`,
    `STOCK_ROW_UNAVAILABLE`); `PRICE_CHANGED` with `lines: [{productId,
    unitPrice}]` and `total`, the current ones.
  - 503, nothing created: `IDENTITY_UNAVAILABLE`, `CATALOG_UNAVAILABLE`,
    `MERCHANT_UNAVAILABLE` (a timeout, connection failure, 5xx, or a 401
    meaning the API keys differ, logged as an ERROR; also any other status the
    service never sends for a valid call, and an answer that cannot be read, such
    as a missing or wrongly typed field, an unknown stock-row status, a price
    with more than 2 decimal places or a rate outside 0.00 to 100.00, each logged
    as an ERROR and never guessed); connect 2 s, read 5 s, no retry inside the
    request: the client retries with the same key.
    `INTERNAL_API_KEY_NOT_CONFIGURED`, `FINGERPRINT_KEY_NOT_CONFIGURED`.
- `GET /orders/{reference}`. The buyer who placed it, a merchant with a line in
  it (a token with `MERCHANT`; its `merchantId` claim), or Admin. A reference
  not of the form `ORD-` + 20 base32 characters is a 400. **A non-admin gets
  404 both for an order that does not exist and for one that is not theirs**,
  so it learns nothing about other orders; Admin gets 404 only for one that
  does not exist.
- `GET /orders`. The same readers; paging as `GET /accounts` (`page` from 0,
  `size` 1..100, anything out of range a 400, a page past the end 200 with empty
  `content`), **newest first** (id descending). Every merchant token also has
  `BUYER`, so a non-admin chooses the view with `view`: `buyer` (the default)
  lists its own purchases, `merchant` (needs `MERCHANT`, else 403) the orders
  with at least one of its lines. Admin gets every order, optionally only
  `needsAttention=true`; `needsAttention` from anyone else, and `view` from
  Admin, is a 403. Which orders a reader gets always comes from the token,
  never from an id in a parameter. A deactivated merchant's new tokens have no
  `MERCHANT` and no `merchantId`, so `view=merchant` is a 403 for it (and
  `GET /orders/{reference}` a 404 for its orders) until it is reactivated; a
  token issued before the deactivation keeps working until it expires
  (Section 2, Tokens).
- For `GET /orders/{reference}` the view follows from the order: its buyer gets
  the buyer view, a merchant with a line in it the merchant view. One account
  is never both for one order: a merchant cannot buy its own products
  (`OWN_PRODUCT`), and an account is linked only once, to a merchant that is
  then new and has no products, so an order placed before the link has no line
  of that merchant.
- **What each reader sees.** Every money value as a JSON number keeping scale
  2.
  - Buyer: `reference`, `status`, `totalAmount`, `couponCode`, `createdAt`,
    `merchants: [{merchantId, amount, status, reasonCode}]` and
    `lines: [{productId, merchantId, productName, unitPrice, quantity,
    lineAmount, status, reasonCode}]`. No commission, no `transactionRef`, no
    `needsAttention`.
  - Merchant: `reference`, `status`, `buyerAccountId` (**never an email**),
    `createdAt`, its own charge leg only (`subtotal`, `discount`, `amount`,
    `commissionRate`, `commission`, `status`, `reasonCode`, `transactionRef`)
    and its own lines only. Not the order total, not another merchant's lines
    or leg (FR-M3, FR-M8, FR-M9).
  - Admin: everything the buyer sees, plus `buyerAccountId`, `needsAttention`
    and every leg's commission fields and `transactionRef`.

**payment-service**
- No REST endpoints for the front-ends. Its interface is the Kafka topics
  in Section 5 (`order.merchant-charge.requested`,
  `order.merchant-refund.requested` in; charged / declined / refunded
  out). Response codes follow the lookup table in Section 2.
- The only HTTP endpoint is actuator's `GET /actuator/health`, for probes. It
  shows the status and the probe groups, never details (database, disk), and no
  other actuator endpoint is exposed.

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
1. Buyer → `order-service`: `POST /orders/checkout`, with an
   `Idempotency-Key`
2. `order-service` → `identity-service`: confirm the buyer is still
   active (sync, live check — FR-A6; a deactivated buyer is rejected
   before anything else happens), then `catalog-service` and
   `merchant-service`: re-validate every line and read each merchant's live
   status and commission rate (sync), then `order-service` →
   `promotion-service`: validate coupon (sync, if present; until
   promotion-service exists a coupon is refused with a 400). Any refusal or
   unavailable service answers at once and creates nothing.
3. `order-service` creates `Order` in `PENDING`, with its legs and one outbox
   row per request, in one transaction, and answers **202** with the order's
   `Location`; the Buyer app polls it. The relay then publishes, in
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
   `StockReservationFailed` with a reason code, such as insufficient quantity)
6. `order-service` waits for a result for **every** merchant charge it
   requested and **every** stock reservation in the order — not just one
   or the other.
   Each result is applied under the order's row lock, so only one result
   of an order decides (Section 2, order-service).
   If **all** are positive (`MerchantCharged` + `StockReserved`): sets
   `Order.status = SUCCEEDED`, publishes `OrderSucceeded`
7. `promotion-service` and `platform-service` consume `OrderSucceeded`
   independently and update their own state. `inventory-service` does
   **not** need to react further — the stock was already decremented at
   reservation time in step 5, which is the point of reserving eagerly

**Decline path (compensating refund + stock release):**
1. Same as above through step 6
2. `order-service` sees at least one `MerchantDeclined` or
   `StockReservationFailed`, and sets `Order.status = FAILING`: the failure is
   decided, the compensations are outstanding
3. For every merchant that already returned `MerchantCharged`,
   `order-service` publishes `MerchantRefundRequested`. For every product
   that already returned `StockReserved`, it publishes
   `StockReleaseRequested` — both compensating actions fire together,
   not sequentially, since they're independent of each other. A leg that
   answers positively after this point is compensated as soon as it answers
4. `payment-service` consumes refund requests and confirms
   `MerchantRefunded`; `inventory-service` consumes release requests,
   marks the `StockReservation` row `RELEASED` and increments `stock_level`
   in one Postgres transaction (the row is kept, so a repeated release, or a
   reserve replayed after it, changes nothing; Section 2), then increments the
   Redis counter back and confirms `StockReleased`
5. Once **every** leg is settled (every result in, every refund and release
   confirmed), `order-service` sets `Order.status = FAILED`, publishes
   `OrderFailed`
6. No consumer of `OrderFailed` needs to do anything further (no coupon
   consumption, no platform projection) — the compensations in steps 3–4
   already returned the system to its pre-checkout state, which is
   precisely why the all-or-nothing decision keeps the failure path
   conceptually simple even though it now has two kinds of compensation
   (refund and release) instead of one

**Stuck path (a result never arrives).** Every order has a deadline, 2 min
after checkout. A timer (Section 2, order-service) then counts every leg
still without a result as failed (`TIMED_OUT`), fails a `PENDING` order
(`FAILING`), sends a release for every line without a result (sent or not,
so a late reserve takes nothing), a refund for every charged merchant, and
cancels the order's requests still unsent in the outbox. A refund or release
that is not confirmed is sent again 3 times, 1 min apart; then its leg is
`STUCK`, the order stays `FAILING` with `needsAttention`, and Admin sees it.
The usual cause is a request in payment-service's or inventory-service's
dead-letter topic, which neither service answers; it is fixed and replayed by
hand. payment-service and inventory-service are unchanged. After a restart,
the timer waits 60 s before acting, so results still queued in Kafka are read
first.

**Late path (a result after the decision).** A late `MerchantCharged` is money
taken, so it is always refunded, even on a `FAILED` order, which goes back to
`FAILING` until the refund confirms and then publishes `OrderFailed` again. A
late `StockReserved` needs nothing: the release sent at the deadline gave it
back or made it fail. Every other late or repeated result is dropped. The full
table is in Section 2, order-service.

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
| `order.stock-reserve.requested` | order-service | inventory-service | schemaVersion, orderId, productId, quantity |
| `order.stock.reserved` | inventory-service | order-service | schemaVersion, eventId, orderId, productId, occurredAt |
| `order.stock.reservation-failed` | inventory-service | order-service | schemaVersion, eventId, orderId, productId, reasonCode, occurredAt |
| `order.stock-release.requested` | order-service | inventory-service | schemaVersion, orderId, productId, quantity |
| `order.stock.released` | inventory-service | order-service | schemaVersion, eventId, orderId, productId, occurredAt |
| `order.succeeded` | order-service | promotion-service, platform-service | schemaVersion, eventId, orderId, orderReference, buyerAccountId, couponCode, totalAmount, merchants[] (with commission), items[], occurredAt |
| `order.failed` | order-service | *(none currently — reserved for future audit/notification use)* | schemaVersion, eventId, orderId, orderReference, reasons[], occurredAt; can repeat for one order (a reopen) |

Every `order.*` topic has 3 partitions, replication factor 1 and default
retention (7 days), and is keyed by the `orderId` as a string, so one order's
events stay in order within a topic (never across topics). The topics each
service publishes are created by that service on its first publish; the
broker does not auto-create topics. So order-service creates the four request
topics and `order.succeeded` and `order.failed`; payment-service and
inventory-service create their result topics.

Note `inventory-service` is no longer a consumer of `order.succeeded` —
stock was already committed at reservation time (Section 2), so there's
nothing left for it to do on success. It only acts on the
reserve/release request topics, which keeps its responsibility narrow:
react to explicit requests, not infer action from a general-purpose
success event meant for other services.

**`merchant.status.changed`** (merchant-service publishes it; identity-service
and catalog-service consume it, see below):
- **`version`** increases by one with every status change of that merchant,
  and the Kafka key is `merchantId`, as a string. A consumer ignores an event
  whose `version` is not newer than the one it has already applied. That
  covers duplicates and out-of-order delivery.
- **No event is published at onboarding.** A consumer that has not seen a
  merchant yet assumes it is active at version 0, which is how every merchant
  starts, until the first event arrives. identity-service has its own rule for
  an event with no linked account, below.
- **`merchantCode`** is carried for tracing; no consumer needs it.
  catalog-service filters products by `merchantId` (Section 3).
- **Topic:** 3 partitions, replication factor 1, default retention (7 days; see
  Section 6). The partition count is fixed, because changing it moves keys between partitions
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
    lose the event. Nothing is lost while the database keeps the row: on
    Postgres it survives restarts, on H2 it does not (Section 7, Local
    development).
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

**The identity-service consumer** keeps `Account.merchantActive` in step
(Section 2):
- **Applying an event** is one atomic statement with no read first:
  `UPDATE account SET merchant_active = ?, merchant_status_version = ? WHERE
  merchant_id = ? AND merchant_status_version < ?`. Duplicates and older events
  match no row. Gaps are not detected: an event carries the full state, so
  version 3 applies after version 1, and a late version 2 is then ignored. An
  event changes only those two columns, whatever `buyerActive` is.
- **No linked account:** log a WARN and skip the event, committing its offset.
  merchant-service refuses to change `active` before the link, so this happens
  only after data loss, such as an H2 restart or wiping Postgres but not Kafka
  (Section 7, Local development). Retrying would block the
  partition forever, and there is nothing to repair from a dead letter.
- **Login** follows `merchantActive` from the next login on. Tokens already
  issued stay valid until they expire (Section 2, Tokens).
- **Settings:**
  - Group id `identity-service`, one listener thread: events are rare, and the
    version check would keep more threads correct anyway.
  - `auto.offset.reset=earliest`, so events published before the first start are
    read. It also recovers if the topic is recreated.
  - Kafka's auto-commit is off. The container commits after each record is
    applied, so a crash between the two only redelivers it, and the version
    check ignores that.
- **Kafka is not needed to start.** The listener connects in the background and
  keeps trying, and identity-service serves requests meanwhile. The topic belongs
  to merchant-service and appears on the first status change, so a missing topic
  is normal. `metadata.max.age.ms` of 10 s is how soon the consumer notices it
  (the client's default is 5 minutes), and the consumer never creates it.
- **A record that cannot be applied:**

  | Case | Action |
  |---|---|
  | Malformed JSON, no value, a missing or invalid field | Dead-letter topic, then the next record |
  | Unknown `schemaVersion` (checked first) | Dead-letter topic, then the next record |
  | Any unexpected exception | Dead-letter topic, then the next record |
  | Database error | Retry with no limit, waiting 1, 2, 4 … up to 60 s; the offset is not committed |

  So one bad record never blocks a partition, and a database outage never drops
  an event; it holds up the records behind it until the database is back, which
  is right, because they would fail too. Bad records are kept rather than
  skipped, because skipping one could lose a deactivation for good.
- **Parsing is strict.** The value is read with the service's own Jackson mapper
  (Section 3), so `"active":"false"` or `1` is a bad record, never converted.
  Only `schemaVersion`, `merchantId`, `active` and `version` are required, and
  unknown fields are ignored.
- **Dead-letter topic:** `merchant.status.changed.DLT`, 3 partitions, keyed like
  the original, with the failure in Spring Kafka's standard headers. identity-service
  owns it and creates it before its first publish, not at startup, because the
  broker does not auto-create topics. If the publish fails, the record is tried
  again, not dropped. Nothing consumes the topic: replaying it by hand after a
  fix is safe, because of the version check.
- **Switch:** `basketry.merchant-status.consumer.enabled`, off in tests, which
  call the handler directly unless a test class turns the listener on.

**The catalog-service consumer** keeps the local `merchant_status` copy in step
(Section 2, catalog-service). The same as identity-service's consumer: one
listener thread, `auto.offset.reset=earliest`, the container committing after
each record, `metadata.max.age.ms` 10 s, Kafka not needed to start, a missing
topic normal and never created here, database errors retried with no limit
(seeking back, 1, 2, 4 … up to 60 s), everything else dead-lettered, and the
switch `basketry.merchant-status.consumer.enabled`, off in tests. Only the
differences:
- **Group id `catalog-service`.** It reads the topic from its beginning, but
  only as far back as the topic's retention reaches (Section 6).
- **Applying an event** is the conditional `MERGE` of Section 2: it inserts the
  row of a merchant it has not seen, so there is no "no linked account" case.
  A merchant's status is stored also before it has products.
- **The listener only parses the record and calls `MerchantStatusService`,
  and never runs inside a transaction.** The `MERGE` commits on its own, and
  only an event that changed the row then invalidates the listings that can
  show the merchant's products (`all` and `m:<merchantId>`, Section 2),
  **after the commit**. The invalidator refuses to run inside a transaction,
  so a transactional listener would roll every status change back and send it
  to the dead-letter topic; a test checks that no transaction is open at
  either point. A duplicate, equal or older event invalidates nothing.
- **Redis never fails a record.** Every Redis call goes through the cache guard
  (Section 2), which turns any error into "unavailable"; the event is still
  applied, its offset committed, and a WARN says the listings may be stale. They
  are, for at most the 60 s page TTL. After a failure the guard skips Redis for
  10 s (`skip-after-failure`), so a bump in that window is skipped too; the TTL
  still bounds the staleness.
- **Two instances inserting the same new merchant at once** (a rolling update):
  on PostgreSQL the second `MERGE` fails on the primary key. That is a
  non-transient database error, retried like any other, and the retry takes
  the `MATCHED` branch (tested on PostgreSQL).
- **`max.poll.records` is 50**, as in inventory-service: applying an event makes
  two Redis calls of up to 500 ms each while Redis is slow but still answering,
  and 500 records a poll would exceed `max.poll.interval.ms`.
- **Parsing** follows identity-service's rules (`schemaVersion` checked first and
  only `1`, `merchantId` and `version` positive whole JSON numbers that fit 64
  bits, `active` a JSON boolean, unknown fields ignored), checked by hand on the
  JSON tree as inventory-service does. Two deliberate differences from
  identity-service: **a repeated field is refused** (otherwise the last one
  would win, and `"active":false,"active":true` would undo a deactivation), and
  so is **anything after the object**. The Kafka key is not checked against
  `merchantId`.
- **Dead-letter topic: `merchant.status.changed.catalog-service.DLT`**, its own,
  never identity-service's: one owner per dead-letter topic, so a dead letter
  always says whose failure it was. 3 partitions, keyed like the original (the
  producer picks the partition by key), created before its first publish, not
  at startup. **Exception headers follow inventory-service's rule**: only our
  two, the exception class and a message that never quotes a value (for an
  unexpected exception, a fixed text pointing to the log). Nothing in the
  record is secret, but a dead letter then never carries text from the record
  into a header or a log line. Replaying a dead letter by hand to the original
  topic is safe (the version check), and also reaches identity-service, which
  ignores what it already applied.

**payment-service results** (`order.merchant.charged`, `order.merchant.declined`,
`order.merchant.refunded`; the rules for repeats are in Section 2,
payment-service; the requests that cause them are below):

- **Transactional outbox**, the same pattern as merchant-service, in
  payment-service's own `outbox_event` table: each result row is written in the
  transaction that decided the result, so a result row exists if and only if the
  result was stored. Each row holds its topic, its key and the exact payload.
- **Key: `orderId`**, as a string, stored in the row's `messageKey`, so once
  published, one order's results stay in order within each topic. **There is no
  order across topics**: `charged` and `refunded` are different topics, so
  order-service can see a re-published `charged` after the `refunded` of the same
  charge.
- **order-service handles each result by the state of its leg**, matched by
  `(orderId, merchantId)`, not by the order's status (the table in Section 2,
  order-service). A repeat, and a re-published `charged` after the `refunded`
  of the same charge, is dropped. **A `charged` for a leg that timed out is
  refunded, even on a `FAILED` order**, which is reopened: the timer can give
  up on a leg (Section 4, stuck path), so a final state no longer means every
  result has arrived. This replaces the earlier rule that any result for an
  order in a final state is a no-op.
- **Value:** JSON, like `merchant.status.changed`, built once and stored in the
  outbox row:
  ```json
  {"schemaVersion":1, "eventId":"<uuid>", "orderId":7, "merchantId":3,
   "transactionRef":"TXN-<32 hex>", "occurredAt":"2026-11-08T10:00:00Z"}
  ```
  `declined` has `reasonCode` (the response code) instead of `transactionRef`, so
  order-service never gets a reference it could refund. `refunded` has neither.
  No result carries an amount or any part of the card number. A repeat is a new
  event with a new `eventId`; `eventId` is for tracing only.
- **A rejected request** (an invalid field, or a refund rejected in Section 2)
  changes nothing and writes no outbox row, so nothing is published back.
- The request topic carries the raw card number, because the contract does
  (default retention). payment-service itself never stores or logs it
  (Section 2).
- **The relay** works as merchant-service's (every second, up to 100 `PENDING`
  rows in `id` order, a failure stops the pass, waits of 1, 2, 4 … up to 60 s,
  `SENT` rows kept, no transaction open during a send, same producer settings).
  Differences: each row is sent to **the topic it names**, with its `messageKey`
  as the key, and the publisher declares five topics, all created together on
  its first publish and checked again after any failure: the three result topics
  (3 partitions, replication factor 1) and, with the listeners on, the two
  dead-letter topics below. payment-service never declares or creates the request
  topics.
- **Ids follow insert order, not commit order.** merchant-service gets its order
  from the merchant row lock; payment-service has no such lock. A transaction that
  took a lower id can commit after one with a higher id, and its row is then sent
  in a later pass, after the higher one. Stopping on a failure only guarantees
  that no row goes before a row that failed. **This is harmless here**, because
  every result one topic carries for one `(orderId, merchantId)` has the same
  outcome: a charge's stored row decides `charged` (with its one
  `transactionRef`) or `declined` (with its one code) for good, and `refunded`
  is always `refunded`. Order between two of them changes nothing for
  order-service. **Revisit this if a result can ever change** for the same
  `(orderId, merchantId)` on one topic, for example with partial refunds.

**payment-service requests** (`order.merchant-charge.requested`,
`order.merchant-refund.requested`; order-service will produce them):
- **Values.** Exactly these fields:
  ```json
  {"schemaVersion":1, "eventId":"<uuid>", "orderId":7, "merchantId":3,
   "amount":25.50, "cardNumber":"4000000000000002", "occurredAt":"2026-11-08T10:00:00Z"}
  {"schemaVersion":1, "eventId":"<uuid>", "orderId":7, "merchantId":3,
   "transactionRef":"TXN-<32 hex>", "occurredAt":"2026-11-08T10:05:00Z"}
  ```
  Required: `schemaVersion` (checked first, only `1`), `orderId` and `merchantId`
  (whole JSON numbers that fit 64 bits, so `7.0` and `"7"` are refused), `amount`
  (a JSON number, read as a decimal and never as a double; `"25.50"` is refused),
  `cardNumber` and `transactionRef` (JSON strings). `eventId` and `occurredAt`
  are optional, strings when present, and not used. The values must then pass
  Section 2's validation. The Kafka key should be the `orderId`; it is not
  checked.
- **Parsing is strict**, and every rule is checked by hand on the JSON tree, not
  left to Jackson's binding (which would turn `"cardNumber":4000…` into a
  string): no conversion between types, a repeated field is refused (otherwise
  the last one would win), and so is anything after the object.
- **Unknown fields are refused, a deliberate difference from identity-service's
  consumer**, which ignores them: a payment must not silently ignore a field it
  does not understand, such as a currency. **Any new field means a new
  `schemaVersion`**, and payment-service must be deployed with it before
  order-service sends the new version; until then every such request would go
  to the dead-letter topic.
- **Settings:** group id `payment-service` for both listeners, one thread each,
  `auto.offset.reset=earliest`, auto-commit off with the container committing
  after each record, `metadata.max.age.ms` 10 s, and the consumer never creates a
  topic. **Kafka is not needed to start**, and the request topics may not exist
  yet: the listeners find them when order-service creates them.
- **The listeners only turn a record into a command and call `PaymentService`,
  and never run inside a transaction**, nor does the relay. `PaymentService`
  needs a new transaction after a lost race (Section 2), and would join an outer
  one instead. A test checks that no transaction is open at either point, because
  the race tests alone would not notice: the abort is a database error, which is
  retried, and the retry succeeds.
- **A request that cannot be handled:**

  | Case | Action |
  |---|---|
  | Malformed JSON, no value, a missing, unknown, repeated or invalid field | Dead-letter topic, then the next record |
  | Unknown `schemaVersion` | Dead-letter topic, then the next record |
  | A refund rejected in Section 2 (unknown reference, `DECLINED`, another order's or merchant's) | Dead-letter topic, then the next record |
  | Any unexpected exception | Dead-letter topic, then the next record |
  | Database error (anywhere in the cause chain) | Retry with no limit, seeking back, waiting 1, 2, 4 … up to 60 s; the offset is not committed |

  A database error says nothing about the request, so it is never dead-lettered.
  Validation keeps a valid request from causing one: the largest valid ids and
  amount fit every column (tested). If the dead-letter publish fails, the record
  is tried again, not dropped.
- **Dead-letter topics:** `order.merchant-charge.requested.DLT` and
  `order.merchant-refund.requested.DLT`, 3 partitions, keyed like the original,
  owned by payment-service and created on first use. No retry topics, which would
  copy the record again. Nothing consumes them: replaying one by hand after a fix
  is safe, because requests are idempotent (Section 2). **A refund sent to a
  dead-letter topic gets no reply**: order-service sends it again 3 times, then
  marks the leg `STUCK` and the order `needsAttention` (Section 2,
  order-service); replaying the fixed record confirms it. A charge sent to a
  dead-letter topic is timed out by order-service's deadline.
- **The card number in the dead-letter topic.** The dead letter keeps the
  original value byte for byte: a masked number would replay as "any other
  number" and be approved, so the dead-letter topic is exposed exactly as much as
  the request topic. Its exception headers are only two of ours: the exception
  class, and a message that never quotes a value (for an unexpected exception, a
  fixed text pointing to the log). Spring's message, cause and stack-trace headers
  are not written, because a Jackson message in a cause can quote the number;
  the parser's exceptions carry only a line and column, and no Jackson cause.
  Spring Kafka logs records by their topic, partition and offset only, and the
  error handler logs the same safe message. A test sends a sentinel number down
  every listener path and checks every dead-letter header and key, every log line
  and every stored value.
- **Never run these loggers at TRACE**, here or in any service that handles the
  request topics: `org.apache.kafka.clients` and
  `org.springframework.kafka.core.DefaultKafkaProducerFactory`. Both print whole
  records, card number included, and no setting of ours can stop them.
- **Switches:** `basketry.payment-requests.consumer.enabled` and
  `basketry.outbox.relay.enabled`, both off in tests, which call `PaymentService`
  and the relay directly unless a test class turns them on.

**inventory-service results** (`order.stock.reserved`,
`order.stock.reservation-failed`, `order.stock.released`; the rules for repeats
are in Section 2, inventory-service). The same as payment-service's results:
transactional outbox in inventory-service's own `outbox_event` table, key
`orderId`, the same relay and producer settings, topics of 3 partitions created
by the service on its first publish together with its two dead-letter topics,
no transaction open during a send. Only the differences:
- **Value:**
  ```json
  {"schemaVersion":1, "eventId":"<uuid>", "orderId":7, "productId":42,
   "occurredAt":"2026-11-09T10:00:00Z"}
  ```
  `reservation-failed` adds `reasonCode` (`INSUFFICIENT_STOCK`,
  `UNKNOWN_PRODUCT`, `QUANTITY_OVER_LIMIT` or `RELEASED_BEFORE_RESERVE`). No
  result carries a quantity.
- **Ids follow insert order, not commit order, and that is harmless here** for
  payment's reason: every result one topic carries for one
  `(orderId, productId)` has the same outcome. The stored row decides
  `reserved` or `reservation-failed` (with its one code) for good, a `RELEASED`
  row still answers `reserved`, and a release is always `released`. There is no
  order across topics, so order-service can see a re-published `reserved` after
  the `released` of the same line, and drops it, as payment's `charged` after a
  refund. order-service handles each result by the state of its leg, matched by
  `(orderId, productId)` (Section 2, order-service); a late `reserved` for a
  line that timed out needs nothing, because the release sent at the deadline
  gave it back or left a tombstone.
- **Two timers, two threads.** The relay and the counter reconciliation
  (Section 2) share the service's scheduler, which gets 2 threads
  (`spring.task.scheduling.pool.size`): with Spring's default of 1, a long
  reconciliation pass would hold up every result.

**inventory-service requests** (`order.stock-reserve.requested`,
`order.stock-release.requested`; order-service will produce them). The same as
payment-service's requests: strict parsing on the JSON tree, `schemaVersion`
checked first, unknown and repeated fields refused, the same settings (group id
`inventory-service` for both listeners, one thread each, earliest, the container
committing after each record), Kafka not needed to start, listeners that never
run inside a transaction, and the same dead-letter rules and headers. Only the
differences:
- **Values.** Both topics have exactly these fields:
  ```json
  {"schemaVersion":1, "eventId":"<uuid>", "orderId":7, "productId":42,
   "quantity":3, "occurredAt":"2026-11-09T10:00:00Z"}
  ```
  Required: `schemaVersion` (only `1`), `orderId` and `productId` (positive
  whole JSON numbers that fit 64 bits), `quantity`. `eventId` and `occurredAt`
  are optional strings, not used.
- **`quantity` is a positive whole JSON number of any size**, read as a big
  integer so it can never overflow. `0`, negative numbers, `1.0`, `1e3` and
  `"3"` are malformed (dead-letter topic). A reserve above 1,000, however large,
  is answered `QUANTITY_OVER_LIMIT`; a quantity above 2^31-1 is stored as
  2^31-1 (Section 2). A release's quantity is only compared with the stored one
  (a WARN when they differ), so a release is **never dead-lettered for its
  quantity's size**: a release with no reply would leave the stock taken.
- **Redis never fails a record.** Every Redis call goes through the counter
  guard (Section 2), which turns any error into "unavailable"; the record is
  decided by PostgreSQL alone. Redis is called only outside a database
  transaction, and a test checks that for every Redis call a reserve or release
  makes through the listener.
- **`max.poll.records` is 50.** A reserve can make up to four Redis calls (take,
  load, take again, subtract), each with a 500 ms timeout, so about 2 s a record
  while Redis is slow but still answering. 500 records a poll (the default)
  would exceed `max.poll.interval.ms` (5 minutes), and the group would rebalance
  over and over.
- **A request that cannot be handled:**

  | Case | Action |
  |---|---|
  | No value, malformed JSON, a missing, unknown, repeated or wrongly typed field | Dead-letter topic, then the next record |
  | Unknown `schemaVersion` | Dead-letter topic, then the next record |
  | An id of 0 or less, or one that does not fit 64 bits | Dead-letter topic, then the next record |
  | `quantity` 0 or less, or not a whole number | Dead-letter topic, then the next record |
  | Any unexpected exception (for example a release finding no stock row for a `RESERVED` reservation, which only data loss can cause) | Dead-letter topic, then the next record |
  | Database error (anywhere in the cause chain) | Retry with no limit, seeking back, waiting 1, 2, 4 … up to 60 s; the offset is not committed |
  | Redis error | Never reaches the listener (above) |

  Everything else is answered, also every rejection that is a business
  outcome: a reserve over the limit, of an unknown product, without enough
  stock, or after its release (`reservation-failed`), and a release with no
  reservation (a tombstone, then `released`). So the dead-letter topics are the
  only requests that get no reply. **order-service times out a dead-lettered
  reserve at its deadline** (and sends its release), **and sends a
  dead-lettered release again 3 times, then marks the line `STUCK`** and the
  order `needsAttention` (Section 2, order-service); until the record is fixed
  and replayed, the stock it should give back stays taken.
- **Dead-letter topics:** `order.stock-reserve.requested.DLT` and
  `order.stock-release.requested.DLT`, 3 partitions, keyed like the original,
  owned by inventory-service and created on first use. Replaying one by hand
  after a fix is safe, because requests are idempotent (Section 2). Nothing in
  these records is secret, but the exception headers follow payment-service's
  rule anyway (our two only, never quoting a value), so a dead letter never
  carries text from the request into a header or a log line.
- **Switches:** `basketry.stock-requests.consumer.enabled` and
  `basketry.outbox.relay.enabled`, both off in tests, which call `StockService`
  and the relay directly unless a test class turns them on.

**order-service requests** (`order.merchant-charge.requested`,
`order.merchant-refund.requested`, `order.stock-reserve.requested`,
`order.stock-release.requested`):
- **Transactional outbox** in order-service's own `outbox_event` table (Section
  2, order-service). The checkout transaction writes the charges and reserves;
  the transaction that changes a leg, a result's or the timer's, writes the
  refunds, releases, `order.succeeded` and `order.failed` it decides.
- **The relay** works as payment-service's (every second, up to 100 `PENDING`
  rows in `id` order, a failure stops the pass, waits of 1, 2, 4 … up to 60 s,
  `SENT` rows kept, no transaction open during a send, the same producer
  settings, each row to the topic it names with its `messageKey`). It declares
  six topics, all created together on its first publish and checked again
  after any failure: the four request topics, `order.succeeded` and
  `order.failed`. Only the differences:
  - **`CANCELLED`.** The timer cancels an order's unsent requests (Section 2,
    order-service). The relay's `SENT` mark and the cancel each move only a
    `PENDING` row, so exactly one of them wins, and whichever wins removes the
    card number in the same update. The relay never sends a `CANCELLED` row;
    a row it was already sending can still arrive, and its result is a late one.
  - **Ids follow insert order, not commit order, and that is harmless here**:
    within one order every compensation is written only after the request it
    compensates was committed (a refund after its `charged`, a release after
    its `reserved` or after the deadline), and a release that overtakes its
    reserve across topics leaves a tombstone (Section 2, inventory-service).
- **Values:** exactly the fields payment-service's and inventory-service's
  parsers accept (above), with `schemaVersion` 1, a new `eventId` per row and
  `occurredAt`. A charge's `amount` is the leg's amount, a JSON number at scale
  2; a refund carries the leg's `transactionRef`; a reserve and a release carry
  the line's `quantity`. A resend of a refund or release is a new row with a new
  `eventId` and the same content. **A charge is never resent**: the outbox
  already delivers it at least once, and a charge with no answer is timed out.
  Golden JSON files of every request are checked in order-service's tests, and
  the end-to-end check proves the real parsers accept them.
- **The card number** is on the charge topic and, for a malformed request,
  payment-service's dead-letter topic, for 7 days (the topic's retention); in
  order-service only until the row is `SENT` or `CANCELLED` (Section 2,
  order-service). Never run `org.apache.kafka.clients` or
  `org.springframework.kafka.core.DefaultKafkaProducerFactory` at TRACE in
  order-service either.

**order-service results** (the six result topics of payment-service and
inventory-service):
- **Settings** as payment-service's listeners: group id `order-service`, one
  listener and one thread per topic, `auto.offset.reset=earliest`, the
  container committing after each record, `metadata.max.age.ms` 10 s,
  `max.poll.records` 50 (a record may wait 5 s for an order's row lock), Kafka
  not needed to start, and the topics, which belong to their producers, never
  created here.
- **The listeners only parse the record and call the saga service, and never
  run inside a transaction**; the saga service opens its own, with the order's
  row lock (Section 2, order-service).
- **Parsing** follows catalog-service's consumer: on the JSON tree, by hand,
  `schemaVersion` checked first and only `1`, ids positive whole JSON numbers
  that fit 64 bits, `transactionRef` a string in payment-service's format,
  `reasonCode` a string of 1 to 40 characters, a repeated field or anything
  after the object refused, unknown fields ignored. The Kafka key is not
  checked.
- **A result that cannot be applied:**

  | Case | Action |
  |---|---|
  | No value, malformed JSON, a missing, repeated or wrongly typed field | Dead-letter topic, then the next record |
  | Unknown `schemaVersion` | Dead-letter topic, then the next record |
  | No such order or leg (data loss) | WARN, skipped, offset committed |
  | Any unexpected exception | Dead-letter topic, then the next record |
  | Database error, a lock timeout included (anywhere in the cause chain) | Retry with no limit, seeking back, waiting 1, 2, 4 … up to 60 s; the offset is not committed |

  A dead-lettered result leaves its leg as if it never arrived, so the
  deadline or the compensation resends deal with it: nothing waits for ever.
- **Dead-letter topics:** `<result topic>.order-service.DLT` (six), 3
  partitions, keyed like the original, owned by order-service and created on
  first use, with only our two exception headers, never quoting a value (as
  inventory-service's). Replaying one by hand is safe: results are handled by
  leg state.
- **Switches:** `basketry.order-results.consumer.enabled`,
  `basketry.outbox.relay.enabled` and `basketry.order.timer.enabled`, all off
  in tests, which call the services directly unless a test class turns them on.
  The retry waits are `basketry.order-results.retry-initial` (1 s) and
  `retry-max` (60 s); the dead-letter topics' partitions
  `basketry.order-results.dead-letter-partitions` (3).

**`order.succeeded`** (one per order, written in the transaction that decides
`SUCCEEDED`; platform-service and promotion-service will need nothing else):
```json
{"schemaVersion":1, "eventId":"<uuid>", "orderId":7, "orderReference":"ORD-<20 base32>",
 "buyerAccountId":12, "couponCode":null, "totalAmount":25.50,
 "merchants":[{"merchantId":3, "subtotal":25.50, "discount":0.00, "amount":25.50,
               "commissionRate":5.00, "commission":1.28, "transactionRef":"TXN-<32 hex>"}],
 "items":[{"productId":42, "merchantId":3, "quantity":3, "unitPrice":8.50, "lineAmount":25.50}],
 "occurredAt":"2026-11-13T10:00:00Z"}
```
`merchants` lists one entry per charge leg; every money value is a JSON number
at scale 2.

**`order.failed`** (written in the transaction that makes the order `FAILED`;
again after a reopen, with a new `eventId`):
```json
{"schemaVersion":1, "eventId":"<uuid>", "orderId":7, "orderReference":"ORD-<20 base32>",
 "reasons":[{"leg":"CHARGE", "merchantId":3, "reasonCode":"51"},
            {"leg":"RESERVE", "productId":42, "reasonCode":"INSUFFICIENT_STOCK"}],
 "occurredAt":"2026-11-13T10:00:00Z"}
```
`reasons` lists every leg that failed: a declined charge (its response code),
a failed reservation (its reason code) and a leg that timed out (`NO_RESULT`),
the charge legs first by `merchantId`, then the lines by `productId`. A reason
has `merchantId` for a `CHARGE` and `productId` for a `RESERVE`, never both
and never a `null`. Neither event carries any part of the card number. Golden
JSON files of both, with the values above, are checked in order-service's tests.

The `order.*` events carry the `orderId` (and `merchantId`/`productId` where
relevant) as a natural idempotency key — consumers must treat redelivery
as a no-op if they've already processed that combination, since Kafka's
at-least-once delivery means duplicates are expected, not exceptional.
`merchant.status.changed` uses `version` for the same purpose.

## 6. Open questions for implementation

- **Decided** (was open): payment-service's idempotency key is a unique
  `(orderId, merchantId)` on its transaction row, with no dedupe table; a refund
  is keyed by `transactionRef` (Section 2, payment-service).
- **Decided** (was open): stuck sagas. Every order has a deadline (2 min); the
  timer then counts every unanswered leg as failed, sends a release for every
  line without a result and a refund for every charged merchant, resends an
  unconfirmed compensation 3 times and then marks its leg `STUCK` for Admin.
  A late `charged` is always refunded, reopening a `FAILED` order.
  payment-service and inventory-service are unchanged; their dead-letter
  topics are handled by the deadline and the resends (Section 2,
  order-service; Section 4).
- **Decided** (was open): order-service has its own transactional outbox, for
  every request and for `order.succeeded` and `order.failed` (Section 5).
- **Decided** (was open): the commission rate in force at order time is read
  from merchant-service's `GET /internal/merchants` at checkout and
  snapshotted on the order's charge leg (Section 2, order-service).
- Known limitation, accepted for now: order-service's results can lag in Kafka
  for longer than the deadline without a restart (a slow or stopped listener).
  The order is then failed and compensated although every leg may have
  succeeded. That is correct, only unnecessary; the startup grace covers only
  the backlog after a restart.
- Known limitation, accepted for now: changing `BASKETRY_ORDER_FINGERPRINT_KEY`
  makes a replay of a checkout sent before the change a 409
  `IDEMPOTENCY_KEY_REUSED` (Section 2, order-service).
- Known limitation, accepted for now: the card number stays on the charge
  request topic, and in payment-service's dead-letter topic, for the topics'
  retention of 7 days, and in PostgreSQL's write-ahead log until it is
  recycled (Section 2, order-service). A real system sends a token.
- Tokens carry no `iss` or `aud` claim, so a service accepts any token
  signed by identity-service's key. Accepted for now.
- Known limitation, accepted for now: one admin retries a merchant's link
  with a new email while another admin onboards a different merchant with
  that same email at the same moment. identity-service can end up linked to
  the first merchant while merchant-service keeps it `PENDING` with its old
  email (the unique `loginEmail` refuses the update). There is no delete
  endpoint to clean up.
- Known limitation, accepted for now: `merchant.status.changed` keeps the
  default retention of 7 days. A consumer that is down for longer loses the
  events it missed, and its cached `merchantActive` stays wrong. **catalog-service
  depends on it more**: its group is new, and reads the topic from its
  beginning only back to the retention. A merchant deactivated more than 7 days
  before catalog-service's group first starts is never seen, reads as active
  at version 0, and its products stay listed. **Fix before deployment:**
  `cleanup.policy=compact` on `merchant.status.changed`. The key is the
  `merchantId` and every event carries the full state, so compaction keeps
  exactly the latest event of every merchant for good, which is all a consumer
  needs. merchant-service declares the topic, so the setting goes there; a
  topic already created (in local development, by an earlier run) keeps its
  old config until it is altered (`kafka-configs.sh --alter --entity-type
  topics --entity-name merchant.status.changed --add-config
  cleanup.policy=compact`). Not a bootstrap call from catalog-service to
  merchant-service. Listed with the other items to do before deployment
  (Section 7).
- Known limitation, accepted for now: the merchant link can read
  `buyerActive = true` just before a deactivation commits, which leaves a
  deactivated buyer linked. Both changes survive and login is still refused
  (Section 2), so Admin can reactivate the buyer when wanted.
- Known limitation, accepted for now: a stock adjustment is a delta, so a client
  that retries one after a lost response applies it twice (Section 2,
  inventory-service). The merchant sees the new quantity in the answer and can
  correct it. The later fix is an `Idempotency-Key` header, stored with the
  result of the first request.
- **Decided** (was open): when order-service gives up on a reservation that
  never answered, it still sends the release, so inventory-service stores a
  tombstone and a late reservation takes nothing (Section 2, order-service and
  inventory-service).
- **Decided** (was open): catalog-service creates a product's stock row in
  inventory-service from a transactional outbox, written with the product and
  relayed to `POST /internal/stock` with retries; a row of another merchant
  becomes a final `CONFLICT` and the product stays out of stock (Section 2,
  catalog-service).
- Redis has no password locally (it listens on 127.0.0.1 only).
  inventory-service and catalog-service read `BASKETRY_REDIS_PASSWORD`; a deployment must set it
  and configure Redis to require it. Decide at deployment, with the Redis pod.
- Postgres on the cluster: one Postgres pod holding one database per service
  (as in local development), or one pod per service (as Section 7 says now).
  Either way each service keeps its own database and user. Decide at
  deployment, with the memory of the small AWS instance in mind.

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
| Containerization | **Docker** | Every service ships a Dockerfile; Kafka, Redis, and Postgres (one database per service) run via Docker Compose for local dev |
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
  identity-service for the JWKS and for merchant-link. identity-service,
  merchant-service, payment-service, inventory-service and catalog-service need
  `BASKETRY_KAFKA_BOOTSTRAP_SERVERS` (also a ConfigMap) to reach Kafka. inventory-service needs
  `BASKETRY_REDIS_HOST` and `BASKETRY_REDIS_PORT` (ConfigMap) and
  `BASKETRY_REDIS_PASSWORD` (Secret; Section 6) to reach Redis, which runs as
  its own pod with no persistent volume, `BASKETRY_IDENTITY_URL` (ConfigMap)
  for the JWKS, and `BASKETRY_INTERNAL_API_KEY` in its Secret for the calls
  catalog-service makes to `/internal/stock`; without the key it starts,
  but refuses every internal call. catalog-service needs the same Redis
  settings, `BASKETRY_IDENTITY_URL` (ConfigMap) for the JWKS,
  `BASKETRY_INVENTORY_URL` (ConfigMap) for `/internal/stock`, and
  `BASKETRY_INTERNAL_API_KEY` in its Secret; without the key it starts with a
  WARN, but inventory-service refuses its stock-row creation and stock reads
  (401), so new products stay out of stock and listings show availability
  `UNKNOWN`. Its Redis is the same pod as inventory-service's, which runs
  `volatile-lru` there too (Section 7, Local development).
  merchant-service now also serves `GET /internal/merchants` with the same
  `BASKETRY_INTERNAL_API_KEY`. order-service needs `BASKETRY_KAFKA_BOOTSTRAP_SERVERS`,
  `BASKETRY_IDENTITY_URL`, `BASKETRY_CATALOG_URL` and `BASKETRY_MERCHANT_URL`
  (ConfigMap), and in its Secret `BASKETRY_INTERNAL_API_KEY` (for catalog-service
  and merchant-service; without it every checkout is a 503
  `INTERNAL_API_KEY_NOT_CONFIGURED`) and `BASKETRY_ORDER_FINGERPRINT_KEY` (at
  least 32 characters; without it every checkout is a 503
  `FINGERPRINT_KEY_NOT_CONFIGURED`). Its database is `orders` (`order` is an
  SQL keyword), with the user `orders`.
- The deployed profile has **no default database passwords**: each service
  gets `BASKETRY_DB_PASSWORD` from its own Secret. The `local-dev-only-…`
  defaults in `application-postgres.yml` are for local development only, and a
  service logs a WARN at startup when it uses one.
- Replace the placeholder admin that `V2__seed_initial_admin.sql` seeds (its
  email and password are public in this repository) before deploying anywhere
  real (Section 2, Admin).
- Set `cleanup.policy=compact` on `merchant.status.changed` before
  catalog-service's consumer group first starts anywhere real, and alter the
  config of a topic that already exists (Section 6). Without it,
  catalog-service lists the products of a merchant deactivated more than 7 days
  earlier.
- Each service owns its database and its own database user, and no service
  reads another's. Locally, one Postgres container holds one database per
  service, to save memory: the boundary is the database, not the server. The
  deployed version runs each service's Postgres as its own pod with a
  persistent volume (Section 6 keeps open whether one shared pod is enough) —
  acceptable for a single-node demo cluster; a real multi-node setup
  would use managed RDS instances instead, which is worth naming as the
  production-grade alternative even though it's not what's built here.

### Local development

- The root `docker-compose.yml` runs one Kafka broker in KRaft mode
  (`apache/kafka:4.2.2`, the same 4.2 line as the client), one Redis
  (`redis:8.10.3-alpine`) for inventory-service's stock counters and
  catalog-service's listing cache, and, with the
  Compose profile `postgres`, one Postgres (`postgres:17.11-alpine`) holding a
  database and a user per service (`identity`, `merchant`, `payment`,
  `inventory`, `catalog`, `orders`). `docker compose up -d --wait` starts Kafka and Redis;
  `docker compose --profile postgres up -d --wait` starts all three. Services
  run from IntelliJ reach Kafka at `localhost:9092` (other containers on the
  Compose network at `kafka:29092`), Redis at `localhost:6379`
  (`BASKETRY_REDIS_PORT` if that port is taken) and Postgres at
  `localhost:5432` (`BASKETRY_POSTGRES_PORT`). All three bind to 127.0.0.1 only.
- **Redis keeps nothing**: no RDB snapshots, no AOF, no volume, so every start
  is empty, inventory-service reloads the counters from its database and
  catalog-service refills its cache (Section 2). It holds no data of record, so
  the volume rules below do not concern it, and a stale counter or page can
  never outlive a wiped database. It has no password locally.
- **Redis memory: 64 MB with `volatile-lru`** (was `noeviction` until
  catalog-service shared it). `volatile-lru` evicts only keys with a TTL, which
  are catalog-service's cached pages (60 s each, about 50 KB for a full page, so
  room for about a thousand). inventory-service's counters and catalog-service's
  version keys have no TTL, so they are never evicted; if memory ever fills with
  them alone, writes fail exactly as with `noeviction` (inventory-service then
  uses Postgres alone) instead of silently dropping a counter. postgresTest
  checks on a real Redis, started with the same flags, that a counter and a
  version key have TTL -1. **Never give an inventory counter a TTL.** Separate
  Redis databases would not help (they share `maxmemory` and the policy); a
  second Redis would isolate them at the cost of another pod's memory.
- Each service runs on in-memory H2 by default, and on Postgres with its Spring
  profile `postgres` (`application-postgres.yml`). Flyway runs the same
  migrations on both, and Hibernate only validates the schema.
- **Kafka and Postgres keep their data in named volumes, and are kept or wiped
  together**, because each depends on the other: Postgres holds the merchant
  versions, the payment transactions and both outboxes, Kafka the events already
  sent and the consumers' positions. inventory-service's stock rows,
  reservations and outbox are in Postgres too, and so are catalog-service's
  products and stock-row requests. Two rules:
  - `docker compose down` stops everything and keeps all data.
  - `docker compose down -v` wipes all data, Kafka's and Postgres's, also when
    the `postgres` profile is not given.

  Wiping only Kafka would lose events that the relay already marked `SENT`
  but identity-service had not read: its `merchantActive` stays wrong until that
  merchant's next change. Wiping only Postgres would bring back the version
  problem below. It also empties catalog-service's `merchant_status` while
  Kafka keeps its group's committed offset, so the old events are never read
  again: **every merchant then reads as active, and the products of inactive
  merchants are listed again**, until each merchant's next status change.
  identity-service's consumer has the same blind spot, but there it only
  causes the WARN-and-skip of an event with no linked account (Section 5).
- **Restarting with H2.** H2 loses everything on every restart, so restart
  **all** services and run `docker compose down -v` together, whichever one you
  meant to restart. Restarting only merchant-service resets its merchant ids and
  versions, and loses any `PENDING` outbox rows, while Kafka and a running
  consumer keep the old ones: a new merchant 1 at version 1 would be ignored by a
  consumer that already applied version 5 of the old merchant 1. Restarting only
  identity-service loses every account, while merchant-service still shows its
  merchants as `LINKED`, and there is no way to link them again. Restarting only
  payment-service loses every transaction and any `PENDING` result: a repeated
  charge request is then charged again under a new `transactionRef`, and a refund
  of an old reference goes to the dead-letter topic. Restarting only
  inventory-service loses every stock row, every reservation and any `PENDING`
  result: a repeated reserve is then decided again (`UNKNOWN_PRODUCT` until the
  stock rows are created again), and a release of an old reservation stores a
  tombstone and gives nothing back. Restarting only catalog-service loses every
  product and starts product ids at 1 again, while inventory-service keeps the
  stock rows of the old ids: a new product of the same merchant then silently
  takes over the old product's stock row (200 `ALREADY_EXISTS`), and one of
  another merchant gets a stock-row `CONFLICT` and stays out of stock for good
  (Section 2, catalog-service). Its local merchant statuses are lost too, and
  every merchant reads as active until its next status event: its consumer
  group's offset is kept in Kafka, so the old events are not read again.
  Restarting only order-service loses every order and starts order ids at 1
  again, while payment-service and inventory-service keep their rows keyed by
  `orderId`: a new order 7 then gets the stored answers of the old order 7 (its
  `charged` with the old reference, its reservations) instead of being decided
  (Section 2, order-service). On Postgres each service can be restarted on its
  own.
- **Interim:** REQUIREMENTS asks for the whole system to start with one
  documented command. For now Compose holds Kafka, Redis and Postgres, and the
  services run from IntelliJ. The rest joins as each piece is built.
- The default build (`./gradlew build`) needs no Docker: it runs on H2, every
  service's Kafka tests use an embedded KRaft broker (its data goes to the
  system temp directory: the broker's test kit ignores `log.dir` and
  `log.dirs`), inventory-service's
  tests use an in-memory fake of the stock counter that passes the same contract
  tests as the Redis one, and catalog-service's tests use an in-memory fake of
  the listing cache (the same contract tests as Redis) and a fake
  inventory-service over real HTTP. Two checks need Docker:
  `./gradlew postgresTest` runs every test again on a real Postgres in a
  Testcontainers container (for inventory-service and catalog-service also on a
  real Redis, the same image and flags as Compose, so the Lua scripts, their
  atomicity under 16 threads, the oversell test and the cache's TTLs run on the
  real stores), and `scripts/e2e-merchant-status.ps1` runs the whole
  status chain on a real broker and real processes (`-Postgres` adds a restart
  of both services on Postgres). `scripts/e2e-payment.ps1` does the same for
  payment-service's requests, results and dead-letter topics, using the Kafka
  commands of services/README.md, so they are tested too (`-Postgres` adds a
  restart and a repeated charge). `scripts/e2e-inventory.ps1` does the same for
  inventory-service's reserves, releases and dead-letter topics, with
  identity-service and merchant-service running to add stock, and runs the
  README's REST, Kafka and `redis-cli` commands as written (`-Postgres` adds a
  restart after a wrong counter: reconciliation must reload it, and a replayed
  reserve and release must take and give back nothing). `scripts/e2e-catalog.ps1`
  does the same for catalog-service's status consumer and listing cache, with
  identity-service, merchant-service and inventory-service running: a merchant
  deactivated and reactivated through the cached listings, a malformed status
  record in catalog-service's own dead-letter topic, the Redis key prefix, TTLs
  and policy, and a Redis outage (`-Postgres` adds a forced restart of
  catalog-service with an event published while it is down: the group must be
  picked back up from its committed offset).
- On Postgres, merchant-service's row-lock reads wait at most 5 s for another
  transaction's lock, then answer 503 `MERCHANT_BUSY` (Postgres would otherwise
  wait forever).

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
