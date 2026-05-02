-- Per-tenant translate config (non-credential settings only).
--
-- The Gemini API key is centrally managed in `tenant_credentials` (kernel V1)
-- and resolved via the kernel CredentialsService — it is NOT stored in this
-- table. Pre-Phase-1 history (gemini_key column, use_default_gemini_key column)
-- was deleted before any production tenants existed.
CREATE TABLE translate_config (
    id            TINYINT UNSIGNED PRIMARY KEY,
    configured_at TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT translate_config_single_row CHECK (id = 1)
);
