# pulsar-backend

Java 21 + Spring Boot 3 modular monolith for the Pulsar platform.

## Prerequisites
- Java 21 (sdkman recommended)
- Docker + Docker Compose
- `/etc/hosts` entries for `admin.pulsar.local` and each tenant slug

## Quickstart

Two paths — pick one.

### Docker (recommended; matches deploy)

The root `docker-compose.yml` declares both `mysql` and `backend` services; the
backend is profile-gated so the existing host-bootRun flow still works.

```bash
cp .env.example .env                        # adjust if needed
docker compose --profile backend up -d --build
# → MySQL on :3316, Spring Boot on :18080
```

### Host (faster inner loop during dev)

```bash
docker compose up -d                        # MySQL only (no --profile backend)
export PULSAR_ADMIN_PASSCODE="PULS-DEV-0000"
export SPRING_DATASOURCE_URL="jdbc:mysql://localhost:3316/pulsar_platform?useSSL=false&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true"
export SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD=pulsar
export PULSAR_MYSQL_BASE_JDBC_URL="jdbc:mysql://localhost:3316"
export PULSAR_MYSQL_USER=root PULSAR_MYSQL_PASSWORD=pulsar
SERVER_PORT=18080 ./gradlew :host-app:bootRun
```

The backend listens on **`:18080`** (not 8080 — that port is owned by the Kestra
flow platform). The frontend's Vite proxy in
`pulsar-frontend/apps/web/vite.config.ts` forwards `/api` to `:18080`, so don't
change the port unless you're also changing the proxy.

## Required environment variables

| Var | Required when | Notes |
|---|---|---|
| `PULSAR_JWT_SECRET` | always | ≥32 chars. Refused at boot in prod profiles if it's the dev sentinel. |
| `PULSAR_CREDENTIALS_MASTER_KEY` | prod profiles | Base64-encoded **32 bytes**. Encrypts every row in `tenant_credentials` and `platform_settings`. Generate with `openssl rand -base64 32`. Falls back to plaintext storage with a WARN in dev. |
| `PULSAR_ADMIN_PASSCODE` | always | Super-admin login. Defaults to `PULS-DEV-0000` in dev only. |
| `PULSAR_DEFAULT_GEMINI_KEY` | optional | Platform-wide Gemini key used when a tenant ticks "Use platform default". |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | always | MySQL primary connection (`pulsar_platform` schema). |
| `PULSAR_MYSQL_BASE_JDBC_URL` / `PULSAR_MYSQL_USER` / `PULSAR_MYSQL_PASSWORD` | always | Used to lazily connect per-tenant DBs. |
| `ANYTHINGLLM_URL` / `ANYTHINGLLM_API_KEY` | optional | Default values for the Content module's RAG/chat. Can be overridden at runtime via the new `/admin/platform-settings` UI without redeploying. |

## Credential management (post-2026-05)

All per-tenant credentials (Gemini, OpenDental DeveloperKey/CustomerKey, Twilio
SID/auth/from-number, Plaud bearer token) live in a single encrypted
**`pulsar_t_<slug>.tenant_credentials`** table, managed via two parallel UIs:

- Super-admin (cross-tenant): `/admin/tenants/:id` → API Keys section.
- Tenant admin (self-serve): `/t/:slug/settings`.

Platform-level shared infrastructure secrets (AnythingLLM URL + API key today)
live in **`pulsar_platform.platform_settings`**, managed at
`/admin/platform-settings`. Module reads go through resolver beans in
`kernel/.../credentials/` and `kernel/.../platform/PlatformSettingsService` —
no module reads `*_config.gemini_key` or similar legacy columns anymore.

See [`docs/2026-05-credential-centralization.md`](docs/2026-05-credential-centralization.md)
(Phase 1 — Gemini) and
[`docs/2026-05-credential-centralization-phase2.md`](docs/2026-05-credential-centralization-phase2.md)
(Phase 2 — OD / Twilio / Plaud + AnythingLLM platform settings) for the design.

## Module domains

The host-app fat jar bundles a different set of modules per `DOMAIN` build arg
(see `host-app/build.gradle` `domainModules` map). Today: `dental` (the
production target) and `generic` (always-on baseline). Build with:

```bash
docker build -t pulsar-backend:dental --build-arg DOMAIN=dental .
```

## Tests

```bash
./gradlew test                              # all modules
./gradlew :kernel:check                     # kernel + jacoco coverage gates
                                            # (≥80% on com.pulsar.kernel.credentials
                                            #  and com.pulsar.kernel.platform)
```

CI runs both on every PR (`.github/workflows/test-pr.yml`).
