-- BYOK / platform-default Gemini key. When use_default_gemini_key is true,
-- the WS handler resolves the platform key (PULSAR_DEFAULT_GEMINI_KEY env)
-- instead of the per-tenant gemini_key. Tenants without their own key can
-- still operate by ticking this flag from the admin UI.
ALTER TABLE translate_config
    ADD COLUMN use_default_gemini_key BOOLEAN NOT NULL DEFAULT FALSE;
