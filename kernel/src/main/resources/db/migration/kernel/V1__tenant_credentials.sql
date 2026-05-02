-- Centralized per-tenant credential store. Replaces the scattered `*_config.gemini_key`,
-- and (in later phases) Twilio creds, OpenDental keys, Plaud token, etc.
--
-- Encryption-at-rest: when PULSAR_CREDENTIALS_MASTER_KEY is set, the value is AES-256-GCM
-- encrypted client-side (in CredentialsService) and the ciphertext + iv + tag are stored.
-- When the master key is absent (dev only), the service falls back to plaintext in
-- value_ciphertext (still as VARBINARY) with a WARN log — matching the pattern used for
-- the JWT dev sentinel.
--
-- (provider, key_name) is the unique key. Examples:
--   ('gemini',     'api_key')
--   ('twilio',     'account_sid'), ('twilio', 'auth_token'), ('twilio', 'from_number')
--   ('opendental', 'developer_key'), ('opendental', 'customer_key')
--   ('plaud',      'bearer_token')
CREATE TABLE tenant_credentials (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    provider             VARCHAR(64)     NOT NULL,
    key_name             VARCHAR(64)     NOT NULL,
    value_ciphertext     VARBINARY(2048),
    value_iv             VARBINARY(12),
    value_tag            VARBINARY(16),
    use_platform_default BOOLEAN         NOT NULL DEFAULT FALSE,
    updated_at           TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by_email     VARCHAR(255),
    updated_by_role      VARCHAR(32),
    UNIQUE KEY uniq_provider_keyname (provider, key_name)
);
