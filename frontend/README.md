# Frontend

Three separate React + TypeScript apps, each its own deployment — not one
app with a role switcher. See [`../docs/DESIGN.md`](../docs/DESIGN.md)
Section 1 for the reasoning (in particular, why Merchant has no
self-registration page).

| App | Status |
|---|---|
| `buyer-web` | Started — Register, Login, Dashboard (token claims) |
| `merchant-web` | Not started |
| `admin-web` | Not started |

## Running buyer-web locally

```sh
# terminal 1 — identity-service on :8080
./gradlew :identity-service:bootRun

# terminal 2 — Vite dev server on :5173 (the origin identity-service allows via CORS)
cd frontend/buyer-web
npm install
npm run dev
```

The API base URL defaults to `http://localhost:8080`; override it with
`VITE_IDENTITY_API_URL` (see `.env.example`). `npm test` runs the Vitest
suite; `npm run build` type-checks and builds.

Until the deployed key store exists, identity-service generates its RS256
key pair at startup, so restarting it invalidates any token the browser holds.
