# Requirements — Basketry

**Status:** Draft v1 for review
**Author:** Kevin Lee
**Purpose:** Portfolio project demonstrating multi-merchant marketplace and
payments-domain design, for use in job applications and technical interviews.

---

## 1. Background

Basketry is a small multi-merchant marketplace: buyers browse products
from several merchants in one catalog, add items from different merchants
to a single cart, and check out once. The platform is built around three
connected surfaces — Buyer, Merchant, and Admin — each reflecting what that
role would actually need in a real marketplace, with card/QR payment
authorization and settlement as the centerpiece.

## 2. Goals

- Demonstrate a correct, testable state machine for multi-merchant
  payment settlement, including the case where some merchants in a cart
  are paid and others are declined.
- Demonstrate real authentication/authorization across three roles.
- Show product ownership end-to-end: a merchant can manage their own
  catalog, stock, and see their own sales performance.
- Show platform-operator concerns: onboarding, account management, revenue.
- Stay screen-shareable: the whole system starts locally with one
  documented command (Docker Compose), with no manual setup steps.
- Serve as a real deployment target for AWS study (Cloud Practitioner).

## 3. Non-goals

Explicitly out of scope for this version:

- Real payment network / acquirer integration — payment authorization is
  simulated, using realistic ISO 8583-style response-code semantics
- Email verification, password reset, OAuth/social login, 2FA
- Shipping, tax calculation, multi-currency
- True distributed transaction / two-phase commit across merchants.
  All-or-nothing is instead achieved with a saga: charges and stock
  reservations run in parallel, and anything that succeeded is
  compensated (refunded / released) if any leg fails — see Section 10
- Production-grade security hardening (rate limiting, CSRF beyond framework
  defaults, secrets rotation) beyond what the deploy target requires
- Low-stock alerts / restock workflows (inventory is scoped to a simple
  count in this version, extensible later)
- Merchant payout/settlement runs — commission is bookkeeping only, no
  money actually moves to merchants in this simulation
- Buyer/merchant real-time chat (live typing indicators, push
  notifications) or a formal dispute/chargeback workflow; see Section
  5.4/6.6 for the order-scoped conversation that *is* in scope

## 4. Accounts & authentication

- One account per person, identified by email + password (BCrypt-hashed).
  Email is case-insensitive (normalized to lowercase before it is stored
  or compared). Passwords must be at least 8 characters; no other
  password rules. No email verification, no password reset flow (out of
  scope).
- Authentication is a stateless JWT (Spring Security), valid for 1 hour,
  with no refresh tokens: the user logs in again when it expires. Logout
  means the client discards the token; there is no server-side session.
- **`buyerActive` is the login gate for the whole account.** A deactivated
  buyer cannot log in anywhere, including the Merchant portal, whatever
  their merchant status. Merchant capability is strictly additive on top
  of an active buyer account and never lets an account log in by itself.
- Every account has the **Buyer** capability by default — anyone with an
  account can browse and check out.
- An account can *additionally* be linked to a `Merchant` (at most one),
  granting the Merchant capability on top of Buyer. This reflects that a
  real person can run a store and also shop on the same platform with the
  same login — capabilities are additive, not a single exclusive role.
- **Admin** is a separate, fixed account type: seeded, not self-registered,
  and not combinable with Buyer/Merchant.
- An email identifies exactly one login across the whole platform: it
  cannot belong to both a buyer/merchant account and an admin account.
- Admin can activate or deactivate a buyer's account, or a merchant
  linkage, independently — deactivating a merchant doesn't delete or
  affect the same person's buyer capability. This is deliberately not
  symmetric: deactivating the buyer account blocks login entirely
  (see above), because buyer is the base identity.
- Buyer, Merchant, and Admin are three separate front-end applications at
  three separate URLs — not one app with a role switcher. Only the
  Buyer marketplace has public self-registration; Merchant and Admin are
  reached at their own addresses and never linked from the Buyer site
  (see `DESIGN.md` Section 1 for the deployment shape).

## 5. Part 1 — Buyer (marketplace + payment)

### 5.1 Account
- FR-B1: Buyer can self-register with email + password directly on the
  Buyer marketplace site — no invitation or approval needed.
- FR-B2: Buyer can log in / log out.

### 5.2 Catalog & cart
- The cart lives client-side only (in the Buyer app) and is sent in full
  at checkout. It is not saved across devices or sessions on the server.
- FR-B3: Buyer can view all products from all active merchants in one
  catalog.
- FR-B4: Buyer can add products from any number of different merchants to
  a single cart.
- FR-B5: Buyer can remove items from the cart before checkout.
- FR-B6: Products from a deactivated merchant, or with zero stock, are not
  purchasable (shown as unavailable).

### 5.3 Checkout & payment outcomes
- FR-B7: Checkout is a single buyer action that may result in multiple
  merchant-level authorizations, one per merchant represented in the cart.
- FR-B8: Each merchant's line items are authorized for that merchant's
  subtotal only, after any applicable coupon discount (see Section 9).
- FR-B9: An order succeeds only if every merchant in the cart is
  successfully charged. If any merchant declines, the whole order fails
  and nothing is charged to the buyer — see Section 10 for the full design
  rationale.
- FR-B10: If some merchants in the order were charged but another merchant
  declines, the charged merchants are automatically refunded as part of
  failing the order, so a failed order never leaves the buyer partially charged.
- FR-B11: Stock is reserved (decremented) for each product line at the
  moment checkout is initiated, not only once the whole order is
  confirmed successful — this prevents two buyers from both being able
  to complete a purchase against the same last unit. If the order
  ultimately fails for any reason, reserved stock is automatically
  released back (see `DESIGN.md` Section 4 for the full mechanism).
- FR-B12: Buyer can enter one coupon code at checkout. The system applies
  it only to the line items it's eligible for (see Section 9) and shows
  the buyer the discounted total before confirming.

### 5.4 Order history
- FR-B13: Buyer can see their own order history with full per-line status,
  including any coupon discount applied.
- FR-B14: Buyer can open a message thread on an order with a specific
  merchant in that order (e.g. a delivery instruction or a question about
  a declined line), and send further messages in that thread. If the
  order has items from multiple merchants, each merchant has its own
  separate thread — a message to Merchant A is never visible to Merchant
  B, even within the same order. Buyer can see whether a thread is open
  or resolved.

## 6. Part 2 — Merchant portal

### 6.1 Account
- FR-M1: Merchant can log in / log out at the Merchant portal's own URL.
  There is no self-registration path for merchants — an account only
  exists once Admin onboards that merchant (see Section 7); the Merchant
  portal has no "sign up" option, only login.

### 6.2 Product maintenance
- FR-M2: Merchant can add, edit, and deactivate their own products.
- FR-M3: Merchant cannot see or edit another merchant's products.

### 6.3 Inventory (v1 depth, built to extend)
- FR-M4: Each product has a stock count. Merchant can view and manually
  adjust it.
- FR-M5: Stock automatically decrements as soon as a buyer's checkout
  reserves it, and is automatically restored if that checkout ultimately
  fails — a merchant's stock count reflects reservations in progress, not
  only fully completed sales.
- FR-M6: A product with zero stock is shown as out of stock to buyers and
  cannot be added to a cart.
- *(Extension point, not built now: low-stock threshold + alert, restock
  history log.)*

### 6.4 Coupons
- FR-M10: Merchant can create a coupon scoped to their own store: code,
  discount type (percentage or fixed amount), and an expiry date.
- FR-M11: Merchant can deactivate their own coupon early.
- FR-M12: A merchant coupon only discounts that merchant's line items in
  a cart, even if the cart also contains other merchants' products.

### 6.5 Sales dashboard
- FR-M7: Merchant can see a summary of their own sales: order count and
  revenue (gross and net of commission — see Section 8), for successful
  orders only. Failed orders (including any that were refunded as part of
  an all-or-nothing rollback) do not count toward revenue.
- FR-M8: Merchant can see their own orders list with per-line-item detail.

### 6.6 Sales follow-up
- FR-M9: Merchant can view individual order/transaction detail for their
  own line items, including the response code/reason for any declined
  line.
- FR-M13: Merchant can see and reply within any message thread a buyer
  has opened with them on one of their orders. Replies are visible to the
  buyer on their order (polled/refreshed, not real-time push — see
  Section 3).
- FR-M14: Merchant can mark a thread resolved. A resolved thread is
  automatically reopened if the buyer sends a further message in it.
- FR-M15: Merchant sales dashboard (Section 6.5) shows a count of open
  (unresolved) conversation threads, so it's visible alongside sales
  figures without opening each order.

## 7. Part 3 — Admin

### 7.1 Merchant onboarding
- FR-A1: Admin can onboard a new merchant: name, category, KYC-verified
  flag, commission rate (see Section 8), and creates the merchant's login
  account. Onboarding identifies the login by email:
  - If an account with that email already exists, merchant capability is
    added to it and its existing password is kept.
  - If not, a new account is created with an initial password that Admin
    enters on the onboarding form (at least 8 characters). Admin gives it
    to the merchant outside the system; there is no automated credential
    delivery, since that would need the email/password-reset machinery
    that is out of scope.
  - Onboarding is rejected if the existing account is deactivated as a
    buyer; Admin must reactivate it first.

### 7.2 Merchant management
- FR-A2: Admin can view all merchants and their status (active/inactive,
  KYC-verified).
- FR-A3: Admin can activate or deactivate a merchant. A deactivated
  merchant's products become unpurchasable (FR-B6).
- FR-A4: Admin can change a merchant's commission rate.

### 7.3 Buyer account management
- FR-A5: Admin can view all buyer accounts.
- FR-A6: Admin can activate or deactivate a buyer account. A deactivated
  buyer cannot log in (to any portal) or check out. Checkout re-checks
  buyer status live, so it is blocked immediately. For other actions, a
  token issued before deactivation keeps working until it expires (at
  most 1 hour); this delay is accepted.

### 7.4 Platform revenue / sales management
- FR-A7: Admin can see platform-wide stats: total orders, total GMV
  settled, total commission revenue collected, total discount value given
  via coupons.
- FR-A8: Admin can see per-merchant commission revenue.

### 7.5 Platform coupons
- FR-A9: Admin can create a platform-wide coupon: code, discount type
  (percentage or fixed amount), expiry date. Unlike a merchant coupon, a
  platform coupon applies across every merchant's line items in the cart.
- FR-A10: Admin can deactivate a platform coupon early.

## 8. Commission model

- Each `Merchant` has a `commissionRate` (percentage, e.g. 5.00 for 5%),
  set at onboarding and editable by Admin.
- On a successfully captured merchant-level authorization within an order,
  platform commission = `merchantSubtotal (after coupon discount) ×
  commissionRate`. The merchant's net proceeds are
  `merchantSubtotal − discount − commission`.
- Commission is only recognized on an order that succeeds in full — a
  declined merchant contributes zero commission, and if a merchant's
  charge is later refunded as part of an all-or-nothing rollback, its
  commission is reversed along with it.
- **Design decision:** commission is calculated on the *discounted*
  amount, not the pre-discount price. This means the merchant bears the
  cost of any coupon discount (their own, or a platform-wide one applied
  to their line items) — the platform doesn't profit from a discount it
  didn't fund. The rejected alternative — commission on pre-discount price
  — would mean the platform's take is unaffected by a discount it applies
  unilaterally (a platform coupon), which shifts that cost entirely onto
  the merchant without the merchant's agreement. Flagged as a decision
  worth revisiting if platform-funded coupons are added later (see below).

## 9. Coupons

- Two coupon sources: **merchant coupons** (created by a merchant, scope
  limited to that merchant's own line items) and **platform coupons**
  (created by Admin, applied across every merchant's line items in the
  cart).
- A coupon has: code (unique), discount type (`PERCENTAGE` or `FIXED`),
  discount value, expiry date, active flag.
- Buyer enters at most one coupon code per checkout in this version (no
  stacking multiple coupons).
- The discount is applied per eligible merchant's subtotal *before*
  authorization — the merchant is authorized for the discounted amount,
  not the full amount with a separate refund/rebate step.
- A `FIXED` coupon never discounts more than the items it applies to. A
  merchant coupon is capped at that merchant's subtotal. A platform
  coupon is capped at the cart's total subtotal and split between
  merchants in proportion to each merchant's subtotal, with rounding
  defined in `DESIGN.md` Section 2 (promotion-service).
- A merchant whose net amount owed (subtotal − discount) comes to zero is
  not charged at all (no zero-amount authorization), but their items
  still go through stock reservation like any other line.
- If the order fails (any merchant declines, triggering the all-or-nothing
  rollback), the coupon is **not** considered used and the buyer can
  retry with the same code.
- A coupon past its expiry date, or deactivated, is rejected at checkout
  with a clear reason (not silently ignored).
- **Who funds a platform coupon's discount is the merchant**, per the
  commission-on-discounted-amount decision above. This is called out
  explicitly because it's a real business-model choice a real platform
  would have to negotiate with merchants (e.g. via merchant agreement
  terms), not something to leave implicit.
- *(Explicitly out of scope: stacking multiple coupons, per-merchant
  or per-category restriction on a platform coupon, usage-count limits
  per buyer, minimum-spend thresholds — all reasonable v2 extensions.)*

## 10. Key open design decision (resolved)

**Q: What happens when a multi-merchant cart has a mix of approved and
declined merchants?**

**Decision: all-or-nothing.** The order only succeeds if every merchant in
the cart is successfully charged. If any merchant declines, the whole
order fails: merchants that were charged in the same checkout are
automatically refunded, any stock reserved for it is released, and the
buyer sees a single `FAILED` order with the decline reason for whichever
merchant caused it.

**Implementation implication:** because each merchant is authorized
independently (in parallel), "all-or-nothing" isn't free — it requires a
compensating action for every leg that succeeded: a refund for each
charged merchant and a stock release for each reserved product. This is
an orchestrated saga coordinated by `order-service` (see `DESIGN.md`
Section 4), not a two-phase-commit distributed transaction, which stays
out of scope (see Section 3).

**Rejected alternative:** partial success (charge who you can, flag who
declined). Considered first, but rejected as the final design because an
all-or-nothing cart is the more familiar and expected checkout behavior
for a buyer, and keeps order status simple (succeed/fail) rather than
introducing a third, harder-to-reason-about state.

## 11. Acceptance criteria (high level)

- A cart with items from 2+ merchants, all approved → order succeeds,
  full amount charged, stock remains decremented (reserved) for every
  line item.
- A cart with items from 2+ merchants, one declines → order `FAILED`;
  any merchant already charged in the same checkout is
  automatically refunded; any stock already reserved for that same
  checkout is automatically released back; the buyer sees which merchant
  (or which out-of-stock line) caused the failure and why.
- A cart with items from 2+ merchants, all declined → order `FAILED`,
  nothing charged, nothing to refund, any reserved stock released.
- A deactivated merchant's products cannot be added to a cart or purchased.
- A deactivated buyer account cannot log in.
- Stock reserved during a checkout that ultimately fails is fully
  released back — a failed order leaves stock levels unchanged from
  before the attempt, not merely "not decremented further."
- Merchant view never shows another merchant's orders that don't include
  their own products.
- Merchant sales dashboard totals only include successful orders; refunded
  merchant charges from failed orders are excluded.
- Admin's total commission figure equals the sum of each merchant's
  individually-reported commission, and excludes any refunded amounts.
- A merchant coupon applied to a cart containing other merchants' products
  only discounts that merchant's line items.
- A platform coupon applied to the same cart discounts every merchant's
  line items.
- An expired or deactivated coupon is rejected with a clear reason, not
  silently ignored or silently applied at zero discount.
- A coupon used in a failed (and rolled back) order is not consumed —
  it can be reused on a subsequent checkout attempt.
- A note the buyer addresses to Merchant A is visible to Merchant A and
  not to Merchant B, even when both are part of the same order.
- A merchant's reply in a thread is visible to the buyer, and to no other
  merchant on the same order.
- Each merchant on a multi-merchant order has its own independent thread
  with the buyer — messages don't cross between them.
- A thread the merchant marked resolved reopens automatically as soon as
  the buyer sends a new message in it.
- The merchant dashboard's open-conversation count matches the number of
  threads across all their orders that are not currently resolved.

## 12. Next steps

Proceed to **Design**: data model (including `Coupon`, how it links to
merchants vs. platform-wide, and `OrderThread`/`OrderMessage` linking a
buyer-merchant conversation to a specific order), API contract, and a
sequence diagram for the checkout flow — including the happy path, the
mid-cart decline path with compensating refunds, coupon
validation/application, and stock/commission handling on each — written
and reviewed before implementation begins.
