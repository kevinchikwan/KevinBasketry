# Roadmap
Status as of 19 Nov 2026. One checkbox per small commit; each should fit in a day.

## Done
- [x] identity, catalog, merchant, inventory, payment and order services, with the checkout saga
- [x] End-to-end script across six real processes (scripts/e2e-order.ps1)
- [x] README as a portfolio page; CI runs the default build on every push

## A. promotion-service
- [x] A1 Design answered: DESIGN and REQUIREMENTS updated (docs only)
- [ ] A2 Empty service: module, port 8086, database promotion, Compose and README tables, health check
- [ ] A3 Coupon table (Flyway) and the code and amount rules
- [ ] A4 Discount maths: percentage, fixed with cap, split between merchants, largest-remainder rounding, random-cart test
- [ ] A5 Pricing service: expired, inactive and not-applicable coupons refused with a reason
- [ ] A6 Internal pricing endpoint with the shared key
- [ ] A7 Create, deactivate and list coupons (Merchant and Admin)
- [ ] A8 order.succeeded consumer and the usage count
- [ ] A9 order-service: PromotionClient and CouponValidator, including a fully comped order: the body rule becomes expectedTotal >= 0 (and the coupon code normalised before the fingerprint), Amounts throws on a negative net, and zero-leg tests for the saga, the endpoint, order.succeeded (every merchant in the cart) and e2e
- [ ] A10 e2e: merchant coupon, platform coupon split, comped order

## B. conversation-service
- [ ] B1 Empty service, thread and message tables
- [ ] B2 Open a thread and send a message
- [ ] B3 Read a thread, resolve it
- [ ] B4 Security: nobody else's thread is visible
- [ ] B5 README, status row, short e2e check

## C. platform-service
- [ ] C1 Empty service, order summary table
- [ ] C2 Summary built from order.succeeded (idempotent)
- [ ] C3 GET /platform/stats (Admin)
- [ ] C4 README and a short e2e check

## D. Front end
- [ ] D1 Browse products
- [ ] D2 Cart in the browser
- [ ] D3 Checkout with a fresh Idempotency-Key and the total shown
- [ ] D4 Order page that polls until final
- [ ] D5 Order history
- [ ] D6 Merchant: my products and stock
- [ ] D7 Merchant: my orders
- [ ] D8 Admin: the repair queue

## E. Deployment
- [ ] E1 Topic compaction for merchant.status.changed
- [ ] E2 Secrets and the seeded admin password replaced by a setup step
- [ ] E3 A Dockerfile per service
- [ ] E4 Kubernetes manifests, run on kind
- [ ] E5 k3s on the EC2 instance
- [ ] E6 README with the live URL

## F. Small items (30 to 60 minutes each)
- [ ] GET /merchants/me in merchant-service
- [ ] Idempotency key for stock adjustments
- [ ] iss and aud checks in tokens
- [ ] A weak spot: one more test or mutation
- [ ] README or DESIGN wording fixes
