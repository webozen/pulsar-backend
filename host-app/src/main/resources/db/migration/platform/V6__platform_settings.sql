-- Platform-level (cross-tenant) settings store. Distinct from tenant_credentials
-- (which is per-tenant DB) — this lives in pulsar_platform and serves the
-- single shared infrastructure config: AnythingLLM URL/key today, future
-- shared OpenAI/Twilio/etc. defaults, etc. Super-admin-managed only.
--
-- Encrypted-at-rest using the same PULSAR_CREDENTIALS_MASTER_KEY as
-- tenant_credentials (no separate key to manage).
CREATE TABLE platform_settings (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    setting_key       VARCHAR(128)    NOT NULL,
    value_ciphertext  VARBINARY(2048),
    value_iv          VARBINARY(12),
    value_tag         VARBINARY(16),
    updated_at        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by_email  VARCHAR(255),
    UNIQUE KEY uniq_setting_key (setting_key)
);
