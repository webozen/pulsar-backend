# Credential Centralization — Phase 1 (Backend)

**Date:** May 2026  
**Companion PR:** `pulsar-frontend` — tenant Settings page + onboarding wizard strip  
**Companion PR:** `pulsar-flow-platform-main` — deep-link "Back to Pulsar" to tenant slug (small UX fix surfaced during this work)

## Why

The Gemini API key was scattered across **two MySQL tables** (`translate_config.gemini_key`, `opendental_ai_config.gemini_key`) and read directly by **seven modules**. Each module's onboarding wizard collected the same key into a different place, with no shared rotation, audit, or encryption story. As we prepare for v1 onboarding, we wanted:

1. **One canonical encrypted store** for tenant credentials.
2. **One UI** to manage them — both for super-admins (cross-tenant) and tenant admins (self-serve).
3. **Onboarding wizards that don't ask for keys** — those become module-enablement + non-credential settings only.
4. **Clean schema for v1** — no transition columns, no legacy fallback paths, since no production tenants exist yet.

This PR is **Phase 1: Gemini only**. Phase 2 will repeat the pattern for OpenDental DeveloperKey/CustomerKey, Twilio, and Plaud.

## What changed

### New table: `tenant_credentials` (kernel-level migration)

Lives in **every** tenant DB (`pulsar_t_<slug>`). Encrypted at rest, audit-tracked, extensible per provider:

```sql
CREATE TABLE tenant_credentials (
    id                   BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
    provider             VARCHAR(64) NOT NULL,           -- 'gemini' today; 'twilio', 'opendental', 'plaud' next
    key_name             VARCHAR(64) NOT NULL,           -- 'api_key', 'account_sid', etc.
    value_ciphertext     VARBINARY(2048),                 -- AES-256-GCM
    value_iv             VARBINARY(12),
    value_tag            VARBINARY(16),
    use_platform_default BOOLEAN NOT NULL DEFAULT FALSE,  -- BYOK / fallback to platform-default env var
    updated_at           TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by_email     VARCHAR(255),
    updated_by_role      VARCHAR(32),
    UNIQUE KEY uniq_provider_keyname (provider, key_name)
);
```

Files:
- `kernel/src/main/resources/db/migration/kernel/V1__tenant_credentials.sql` — new migration.
- `kernel/src/main/java/com/pulsar/kernel/tenant/MigrationRunner.java` — added `migrateKernel(dbName)` that runs `flyway_schema_history_kernel`.
- `host-app/src/main/java/com/pulsar/host/startup/TenantMigrationSweeper.java` — sweeps the kernel migration for every tenant on app startup.

### New service: `CredentialsService` (kernel)

The single read/write path for `tenant_credentials`. Handles encryption, decryption, audit columns, and the `use_platform_default` toggle.

- `kernel/src/main/java/com/pulsar/kernel/credentials/CredentialsService.java` — encrypt/decrypt with AES-256-GCM (96-bit IV, 128-bit tag), random IV per row.
- **Master key**: `PULSAR_CREDENTIALS_MASTER_KEY` (base64-encoded 32 bytes). Required in prod-like Spring profiles; falls back to plaintext storage with a WARN in dev. Mirrors the `JwtService.validateSecret` posture.
- API: `resolve(dbName, provider, keyName)`, `set(...)`, `clear(...)`, `setUseDefault(...)`, `status(...)`, `platformDefaultAvailable(provider)`.
- Resolution priority: `use_platform_default=true && env var set` → `PLATFORM_DEFAULT`; else stored ciphertext → `TENANT`; else `NONE`.

### `GeminiKeyResolver` is now a thin facade in kernel

Same public API the seven existing callers depend on, but internals delegate to `CredentialsService`. Lives in `kernel` so all modules share it without a cross-module dependency on translate.

- **Moved**: `modules/translate/.../GeminiKeyResolver.java` → `kernel/.../credentials/GeminiKeyResolver.java`.
- **Importers updated** (just the import line):
  - `host-app/.../api/admin/AdminApiKeysController.java`
  - `modules/translate/.../ws/GeminiProxyHandler.java`

### Six modules repointed to read Gemini via the resolver

Pre-Phase-1 these modules ran their own SQL against `opendental_ai_config.gemini_key`. Now they all call `geminiKeyResolver.resolveForDb(t.dbName())`, so a single source of truth. Same end-user behavior; admin rotations are picked up by every module on the next request.

- `modules/opendental-ai/.../ws/OpendentalAiHandler.java`
- `modules/opendental-ai/.../chat/OpendentalAiChatController.java`
- `modules/copilot/.../ws/CoPilotHandler.java`
- `modules/call-intel/.../api/CallIntelController.java`
- `modules/text-intel/.../api/TextIntelController.java`
- `modules/text-copilot/.../api/TextCopilotController.java`

### Admin endpoint already routed through the resolver

`AdminApiKeysController` (`PUT /api/admin/tenants/{id}/api-keys/gemini`) was already calling `GeminiKeyResolver`. Since the resolver now writes via `CredentialsService`, no controller change was needed beyond the import update.

### New tenant-self-serve endpoint

`host-app/src/main/java/com/pulsar/host/api/tenant/TenantCredentialsController.java`

```
GET  /api/tenant/credentials               → presence flags for current tenant (JWT-scoped)
PUT  /api/tenant/credentials/gemini        → { apiKey?: string, useDefault?: boolean }
```

Auth via `TenantContext.require()` + `Principal.TenantUser`; refuses cross-tenant access. Audit columns populated with the tenant user's email and role.

### Onboarding contract cleanup (no production data exists yet → safe to edit V1 migrations directly)

- `translate/V1__translate_config.sql`: dropped the `gemini_key TEXT NOT NULL` column.
- `translate/V3__use_default_gemini_key.sql`: **deleted**. Flag now lives in `tenant_credentials.use_platform_default`.
- `opendental-ai/V1__opendental_ai_config_and_audit.sql`: dropped the `gemini_key TEXT NOT NULL` column.
- `OpendentalAiOnboardingController`: dropped `geminiKey` from `ConfigRequest`. Wizard sends only OD keys.
- `TranslateOnboardingController`: stripped POST/DELETE write endpoints. Now read-only — returns boolean configured status from the resolver.
- `TranslateSettingsService`: no longer inserts a `gemini_key` placeholder when pre-creating the row.
- `TranslateModule.isOnboarded`: now checks `tenant_credentials` for a Gemini key (or use-default flag); pre-Phase-1 it checked the existence of a `translate_config` row, which broke once the wizard stopped writing it. Without this fix, "Open Translate" looped users back to the wizard.

### Bug fix surfaced during this work

- `modules/opendental-ai/.../ws/SystemInstructions.java`: pre-existing main branch had `OpendentalAiChatController.java:92` calling `SystemInstructions.build(catalog, slug, tz)` (3 args) but the method only accepted 2. Fixed by adding a 3-arg overload that injects timezone into the system prompt; kept the 2-arg version as a delegate so the voice WS handler doesn't break. Without this fix the docker build fails on master.

### Tests + coverage

- `kernel/src/test/java/com/pulsar/kernel/credentials/CredentialsServiceTest.java` — 10 specs: encrypt/decrypt round-trip, dev-mode plaintext, **prod-mode refusal when master key absent**, clear-keeps-row-for-audit, set-use-default doesn't touch ciphertext, resolution priority (`PLATFORM_DEFAULT > TENANT > NONE`), `useDefault=true` with env unset returns `NONE`, audit columns populated, env-default-available reflects env var presence.
- `kernel/src/test/java/com/pulsar/kernel/credentials/GeminiKeyResolverTest.java` — 5 specs: resolve-after-update, clear via empty string, use-default routes to platform default, no-key returns NONE, audit overload propagates email + role.
- H2 (MySQL-compat mode) added as `testImplementation` in `kernel/build.gradle`.

### CI coverage gate

- `build.gradle`: added jacoco plugin to all subprojects. New `:kernel:jacocoTestCoverageVerification` rule fails the build if `com.pulsar.kernel.credentials` package coverage drops below 80% line. Currently at ~88%.
- `.github/workflows/test-pr.yml`: added the coverage check step + jacoco HTML upload artifact.

## Migration impact / operator notes

1. **Set `PULSAR_CREDENTIALS_MASTER_KEY`** in any prod-like deploy. Required at boot when the active Spring profile is `prod` (or any non-dev/local/test profile). Generate with: `openssl rand -base64 32`.
2. `PULSAR_DEFAULT_GEMINI_KEY` (already-existing env var) is still honored — used when a tenant has the "use platform default" toggle on.
3. **No data-migration step needed** because there were no production tenants. Dev DBs were wiped and re-created on a clean slate.
4. The `tenant_credentials` table is created lazily on every tenant DB at startup via `TenantMigrationSweeper`. Existing dev tenants need a backend restart to pick it up; new tenants get it as part of provisioning.

## Files touched

```
kernel/build.gradle                                                                             (+H2 testImpl)
kernel/src/main/resources/db/migration/kernel/V1__tenant_credentials.sql                        (new)
kernel/src/main/java/com/pulsar/kernel/credentials/CredentialsService.java                      (new)
kernel/src/main/java/com/pulsar/kernel/credentials/GeminiKeyResolver.java                       (moved + thinned)
kernel/src/main/java/com/pulsar/kernel/tenant/MigrationRunner.java                              (added migrateKernel)
kernel/src/test/java/com/pulsar/kernel/credentials/CredentialsServiceTest.java                  (new)
kernel/src/test/java/com/pulsar/kernel/credentials/GeminiKeyResolverTest.java                   (new)

modules/translate/src/main/java/com/pulsar/translate/GeminiKeyResolver.java                     (deleted — moved to kernel)
modules/translate/src/main/java/com/pulsar/translate/TranslateModule.java                       (isOnboarded → tenant_credentials)
modules/translate/src/main/java/com/pulsar/translate/TranslateOnboardingController.java         (read-only)
modules/translate/src/main/java/com/pulsar/translate/TranslateSettingsService.java              (drop placeholder insert)
modules/translate/src/main/java/com/pulsar/translate/ws/GeminiProxyHandler.java                 (import update)
modules/translate/src/main/resources/db/migration/translate/V1__translate_config.sql            (drop gemini_key)
modules/translate/src/main/resources/db/migration/translate/V3__use_default_gemini_key.sql      (deleted)

modules/opendental-ai/src/main/java/com/pulsar/opendentalai/OpendentalAiOnboardingController.java (drop geminiKey)
modules/opendental-ai/src/main/java/com/pulsar/opendentalai/chat/OpendentalAiChatController.java  (use resolver)
modules/opendental-ai/src/main/java/com/pulsar/opendentalai/ws/OpendentalAiHandler.java           (use resolver)
modules/opendental-ai/src/main/java/com/pulsar/opendentalai/ws/SystemInstructions.java            (3-arg overload bug fix)
modules/opendental-ai/src/main/resources/db/migration/opendental-ai/V1__opendental_ai_config_and_audit.sql (drop gemini_key)

modules/copilot/src/main/java/com/pulsar/copilot/ws/CoPilotHandler.java                          (use resolver)
modules/call-intel/src/main/java/com/pulsar/callintel/api/CallIntelController.java               (use resolver)
modules/text-intel/src/main/java/com/pulsar/textintel/api/TextIntelController.java               (use resolver)
modules/text-copilot/src/main/java/com/pulsar/textcopilot/api/TextCopilotController.java         (use resolver)

host-app/src/main/java/com/pulsar/host/api/admin/AdminApiKeysController.java                     (import update)
host-app/src/main/java/com/pulsar/host/api/tenant/TenantCredentialsController.java               (new)
host-app/src/main/java/com/pulsar/host/startup/TenantMigrationSweeper.java                       (sweep kernel migrations)

build.gradle                                                                                     (jacoco + coverage gate)
.github/workflows/test-pr.yml                                                                    (coverage step + artifact)
```

## Out of scope (Phase 2)

- OpenDental DeveloperKey + CustomerKey (currently in `opendental_ai_config` and `opendental_calendar_config`)
- Twilio account_sid / auth_token / from_number (currently in `opendental_calendar_sms_config`)
- Plaud bearer token (currently in `ai_notes_config`)
- RingCentral OAuth (different shape — OAuth dance, not typed-in keys; deferred)
- AnythingLLM URL + API key (platform-level, not per-tenant; deferred to a separate "platform_settings" pattern)
