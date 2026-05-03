# Credential Centralization — Phase 2 + Platform Settings (Backend)

**Date:** May 2026
**Companion PR:** `pulsar-frontend` — extended `<ApiKeysSection>`, new `/admin/platform-settings` page, stripped onboarding wizards
**Builds on:** [Phase 1 PR](../docs/2026-05-credential-centralization.md)

## Why

Phase 1 centralized the Gemini API key. Phase 2 finishes the per-tenant track (OpenDental keys, Twilio creds, Plaud token) and adds a parallel platform-level track for shared infrastructure secrets (AnythingLLM URL + key). Goal: every credential a v1 tenant or operator manages lives in one of two encrypted, audit-tracked stores with one UI per scope.

After this PR, **no credential is collected in any onboarding wizard**. Wizards become status-only — they tell the tenant whether they're ready and route them to the right place to configure missing pieces.

## What changed

### New per-tenant resolvers (kernel)

Three thin facades over `CredentialsService`, mirroring the Phase 1 `GeminiKeyResolver`:

- **`OpenDentalKeyResolver`** — `('opendental', 'developer_key')` + `('opendental', 'customer_key')`. No platform-default; clinic-specific.
- **`TwilioCredentialsResolver`** — `('twilio', 'account_sid' | 'auth_token' | 'from_number')`. Auth token field treats blank as "leave untouched" (rotation UX).
- **`PlaudKeyResolver`** — `('plaud', 'bearer_token')`. Single value.

### Module readers repointed

Five modules that wrote/read these credentials directly now go through the resolvers:

- `OpendentalAiHandler`, `OpendentalAiChatController` — OD keys via `OpenDentalKeyResolver`.
- `CoPilotHandler` — same.
- `OpendentalCalendarController` — `loadKeys()` + `loadSmsConfig()` rewritten via `OpenDentalKeyResolver` + `TwilioCredentialsResolver`. Public `/api/opendental-calendar/sms-config` POST now accepts settings only (templates + clinic name/address); credentials moved to `/api/{admin,tenant}/credentials/twilio`.
- `AiNotesController` — `token()` via `PlaudKeyResolver`.
- `OpendentalAiOnboardingController` and `AiNotesOnboardingController` — now read-only status endpoints (no writes).

### Schema cleanups (clean slate, no production data)

V1 migrations of the three modules edited directly to drop credential columns:

- `opendental_calendar/V1__opendental_calendar_config.sql` — **deleted** (the table only held OD keys, now empty).
- `opendental_calendar/V2__add_sms_config.sql` — dropped `account_sid`, `auth_token`, `from_number`. Templates remain.
- `opendental_ai/V1__opendental_ai_config_and_audit.sql` — dropped `od_developer_key`, `od_customer_key`. Timezone (V2) remains.
- `ai_notes/V1__ai_notes_config.sql` — **deleted** (table only held `plaud_token`).

Per-module `isOnboarded(...)` checks updated:

- `OpendentalCalendarModule.isOnboarded` — checks `tenant_credentials` for both OD keys.
- `AiNotesModule.isOnboarded` — checks `tenant_credentials` for plaud bearer token.

### New platform-level track

Distinct from per-tenant credentials. For shared infrastructure values that apply across every tenant.

- **New table** `pulsar_platform.platform_settings` (host-app `V6__platform_settings.sql`) — `(setting_key, value_ciphertext, value_iv, value_tag, updated_at, updated_by_email)`. Reuses `PULSAR_CREDENTIALS_MASTER_KEY`.
- **New service** `kernel/.../platform/PlatformSettingsService.java` — mirrors `CredentialsService` but on the platform DB, keyed by single `setting_key` instead of `(provider, key_name)`. Includes `resolveOrFallback(key, envValue)` so consumers get DB → env-fallback semantics.
- **Static catalog** of allowed keys: `anythingllm.url`, `anythingllm.api_key`. PUTs to anything else 400 fast.
- **New endpoint** `GET/PUT /api/admin/platform-settings[/{key}]` — super-admin only.

### `AnythingLlmClient` repointed

`modules/content/.../AnythingLlmClient.java` now resolves URL + API key via `PlatformSettingsService.resolveOrFallback` per request — DB value if set, else env fallback. Admin can rotate without redeploying or editing `.env`. `isConfigured()` reflects either source.

### Extended admin/tenant credentials surface

`AdminApiKeysController` and `TenantCredentialsController` extended with PUT endpoints for `opendental`, `twilio`, `plaud`. GET returns status flags for all four providers in a single response (`{providers: {gemini:..., opendental:..., twilio:..., plaud:...}}`).

### Tests + coverage

- `kernel/src/test/java/com/pulsar/kernel/credentials/Phase2ResolversTest.java` — 7 specs covering OD/Twilio/Plaud round-trip, partial clears, status flags, blank-token rotation behavior.
- `kernel/src/test/java/com/pulsar/kernel/platform/PlatformSettingsServiceTest.java` — 8 specs covering encrypt round-trip, env-fallback priority, prod-mode key requirement, unknown-key rejection, dev-mode plaintext, status flags.
- Jacoco gate extended to cover `com.pulsar.kernel.platform` package alongside the existing `com.pulsar.kernel.credentials` rule. Both at the 80% line threshold.

## Operator notes

- **No new env vars.** `PULSAR_CREDENTIALS_MASTER_KEY` from Phase 1 covers both stores.
- **`AnythingLlmClient` keeps reading `pulsar.anythingllm.url` and `pulsar.anythingllm.api-key` env vars** as fallback. Existing `.env` deployments work unchanged. To migrate: super-admin sets the value once in `/admin/platform-settings`; subsequent requests resolve from DB.
- **Wizards no longer accept credential payloads.** Old clients that POST `geminiKey` / `plaudToken` / `accountSid` to `/api/.../onboarding` get a 405 (or fields ignored, depending on endpoint). Frontend updated in companion PR.
- **`opendental_calendar_config` and `ai_notes_config` tables removed entirely.** Tenants don't reference them anymore.

## Files touched (29 file changes)

```
kernel/src/main/java/com/pulsar/kernel/credentials/OpenDentalKeyResolver.java     (new)
kernel/src/main/java/com/pulsar/kernel/credentials/TwilioCredentialsResolver.java (new)
kernel/src/main/java/com/pulsar/kernel/credentials/PlaudKeyResolver.java          (new)
kernel/src/main/java/com/pulsar/kernel/platform/PlatformSettingsService.java      (new)
kernel/src/test/java/com/pulsar/kernel/credentials/Phase2ResolversTest.java       (new)
kernel/src/test/java/com/pulsar/kernel/platform/PlatformSettingsServiceTest.java  (new)

host-app/src/main/java/com/pulsar/host/api/admin/AdminApiKeysController.java                  (3 new providers)
host-app/src/main/java/com/pulsar/host/api/admin/AdminPlatformSettingsController.java         (new)
host-app/src/main/java/com/pulsar/host/api/tenant/TenantCredentialsController.java            (3 new providers)
host-app/src/main/resources/db/migration/platform/V6__platform_settings.sql                   (new)

modules/opendental-ai/.../OpendentalAiOnboardingController.java                  (read-only status)
modules/opendental-ai/.../chat/OpendentalAiChatController.java                   (use OD resolver)
modules/opendental-ai/.../ws/OpendentalAiHandler.java                            (use OD resolver)
modules/opendental-ai/.../db/migration/opendental-ai/V1__...                     (drop OD cols)
modules/opendental-calendar/.../OpendentalCalendarController.java                (rewritten via resolvers)
modules/opendental-calendar/.../OpendentalCalendarModule.java                    (isOnboarded → tenant_credentials)
modules/opendental-calendar/.../db/migration/opendental-calendar/V1__...         (deleted)
modules/opendental-calendar/.../db/migration/opendental-calendar/V2__...         (drop Twilio cols)
modules/copilot/.../ws/CoPilotHandler.java                                       (use OD resolver)
modules/ai-notes/.../AiNotesController.java                                      (use Plaud resolver)
modules/ai-notes/.../AiNotesOnboardingController.java                            (read-only status)
modules/ai-notes/.../AiNotesModule.java                                          (isOnboarded → tenant_credentials)
modules/ai-notes/.../db/migration/ai-notes/V1__...                               (deleted)
modules/content/.../service/AnythingLlmClient.java                               (PlatformSettingsService + env fallback)

build.gradle                                                                     (jacoco gate ext to platform pkg)
```

## End-to-end verified

- All 4 providers (`gemini`, `opendental`, `twilio`, `plaud`) write through `/api/admin/tenants/{id}/api-keys/{provider}` and land as encrypted rows in `pulsar_t_<slug>.tenant_credentials`.
- Same paths work via `/api/tenant/credentials/{provider}` with tenant-user JWT.
- AnythingLLM URL + key configurable via `/admin/platform-settings` — content chat uses DB value when set, falls back to env when absent.
- Backend `:kernel:check` green (15 specs, 80%+ coverage on both `credentials` and `platform` packages).
- Wizards no longer expose any credential input.

## Out of scope

- **RingCentral OAuth** — stays in `voice_provider_config`. OAuth dance is structurally different from typed-in keys; needs its own design PR.
- **Per-operator admin accounts** — `Principal.Admin` still has no email, so super-admin writes record `(updated_by_email=NULL, role='super_admin')`. Acceptable for v1 with one trusted ops persona; revisit when admin team grows.
