# Roadmap
Status as of 25 Nov 2026. One checkbox per small commit; each should fit in a day.

## Done
- [x] identity, catalog, merchant, inventory, payment and order services, with the checkout saga
- [x] End-to-end script across six real processes (scripts/e2e-order.ps1)
- [x] README as a portfolio page; CI runs the default build on every push

## A. promotion-service
- [x] A1 Design answered: DESIGN and REQUIREMENTS updated (docs only)
- [x] A2 Empty service: module, port 8086, database promotion, Compose and README tables, health check
- [x] A3 Coupon table (Flyway) and the code and amount rules; FlywayWiringTests then expects V1, and spring-boot-starter-data-jpa comes in
- [x] A4 Discount maths: percentage, fixed with cap, split between merchants, largest-remainder rounding, random-cart test
- [x] A5 Pricing service: expired, inactive and not-applicable coupons refused with a reason
- [x] A6 Internal pricing endpoint with the shared key (copy order-service's InternalApiKey: whitespace stripped, at least 32 characters, unset means a WARN and every call refused); the contract in docs/contracts/coupon-pricing.json
- [ ] A7 Create, deactivate and list coupons (Merchant and Admin); the strict JSON settings A6 set (no scalar coercion, strict strings, unknown fields, repeated fields and trailing tokens refused, the body read by the controller so no Jackson message is logged) apply to these bodies too. Part 1 done: POST /coupons and GET /coupons/{id}, the JWT chain, the 409 decided by the unique constraint (race test on PostgreSQL), docs/contracts/coupon-admin.json
- [ ] A8 order.succeeded consumer and the usage count: one group, promotion-service (listeners with idIsGroup = false, and the one-group test from order-service's ResultListenerKafkaTests); copy the corrected isDatabaseFailure comment and the fixed TransactionBoundaryTests (calls counted when they return); and pricing writes no coupon_usage row (the assertion A5's CouponPricingDatabaseTests could not make before the table existed)
- [ ] A9 order-service: PromotionClient and CouponValidator, including a fully comped order: the body rule becomes expectedTotal >= 0 (and the coupon code normalised before the fingerprint), Amounts throws on a negative net, and zero-leg tests for the saga, the endpoint, order.succeeded (every merchant in the cart) and e2e; the buyer order view gains subtotal and discount per merchant and lists comped merchants (amount 0.00, built from their lines); order-service's copy of the code rule is tested in its own test against docs/coupon-code-vectors.txt (the file found from the module's directory, as promotion-service's CouponCodeVectorTests does), and decide there whether coupon_code gets promotion-service's ck_coupon_code CHECK; order-service sends the merchants in merchantId order and does not need a cart order; PromotionClient's test reads docs/contracts/coupon-pricing.json, the file promotion-service's PricingContractTests reads (found from the module's directory), and a problem detail has no "type" member (Spring leaves out about:blank)
- [ ] A10 e2e: merchant coupon, platform coupon split, comped order; the -Postgres run needs BASKETRY_PROMOTION_DB_PASSWORD = New-Secret, as the other databases have

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
- [x] identity-service: read the body in the controller (F2). The password was never quoted (a JSON number for a string field is taken as text, so Jackson never failed on it, and toString masked it); the email was, at TRACE in every request object and at DEBUG as an invalid email's @Valid rejected value, and so were wrong-typed buyerActive and merchantId values. RequestBodyReader reads, validates and answers the same 400s and 415s (IdentityBadRequestBodiesTests); HttpJacksonMessageLeakTests (27 cases)
- [x] merchant-service (F3): Spring still reads the body; QuietBodiesConfig replaces Boot's JSON converter with one (the application's own JsonMapper) whose failed read keeps no Jackson message, and validates @Valid bodies in a RequestBodyAdvice before Spring does, so no rejected value is logged; the request, answer and identity-call records mask the name and the email; unknown field names follow order-service's rule. MerchantBadRequestBodiesTests pins the answers; HttpJacksonMessageLeakTests (36 cases and the successful answers) is the alarm for a Spring or Boot upgrade
- [x] identity-service (F4): a successful login's access token is no longer in Spring's TRACE log; LoginResponse prints accessToken=*** (the answer still holds the token); the leak test's successful answers check that neither the buyer's nor the Admin's token is in any log line. order-, merchant- and payment-service hold no other unmasked token or secret in a record
- [ ] Optional: identity-service could use merchant-service's converter approach (QuietBodiesConfig, one file) instead of RequestBodyReader (three files that re-implement Spring's content-type, charset and 400/415 rules); IdentityBadRequestBodiesTests and the leak test would have to pass unchanged
- [ ] Strict strings for email and password in identity-service: a JSON number is a 400 (today it is accepted as text); this changes answers (register: the policy's 400 today; login: 401 today)
- Known limitation, accepted: catalog-service and inventory-service still let Spring read their bodies. No personal data is in them (product fields; ids and stock deltas), but a wrong-typed value can be quoted in Spring's DEBUG and TRACE log.
- [ ] GET /merchants/me in merchant-service
- [ ] Idempotency key for stock adjustments
- [ ] iss and aud checks in tokens
- [ ] A weak spot: one more test or mutation
- [ ] README or DESIGN wording fixes
- [ ] Port order-service's web hardening (69f513f) back to identity, catalog, merchant and inventory: log-request-details false, Tomcat's access log off, request objects that mask secrets in toString, strict strings, a fixed 400 detail with only the field path, a body limit before security, ResponseEntityExceptionHandler. Done in order-service itself (F1): CheckoutController reads the body, so Jackson's message (which quoted a card number sent as a string for expectedTotal, productId or quantity) never reaches Spring's DEBUG log (HttpJacksonMessageLeakTests)
- [ ] Refuse repeated fields and trailing tokens in HTTP bodies in every service (STRICT_DUPLICATE_DETECTION and FAIL_ON_TRAILING_TOKENS, as promotion-service's StrictJsonConfig); today only the Kafka parsers do
