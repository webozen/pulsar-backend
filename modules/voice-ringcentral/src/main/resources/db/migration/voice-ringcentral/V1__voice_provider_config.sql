-- One row per VoIP provider configured by this tenant. Today RingCentral is
-- the only adapter, so the row id "ringcentral" is effectively the entire
-- table; once we ship Twilio/Zoom Phone they get their own rows.
--
-- Tokens are stored plaintext for now to match the existing pattern in
-- opendental_ai_config (Gemini key, OD developer key) — an envelope-encryption
-- pass over all per-tenant secrets is a separate task.
CREATE TABLE voice_provider_config (
    provider_id          VARCHAR(32) NOT NULL,
    embed_url            TEXT NULL,                        -- override; default comes from the adapter
    oauth_access_token   TEXT NULL,
    oauth_refresh_token  TEXT NULL,
    oauth_expires_at     TIMESTAMP NULL,
    webhook_secret       VARCHAR(128) NULL,                -- HMAC verification token from the provider's webhook setup UI
    click_to_dial_url    TEXT NULL,                        -- optional; e.g. RC's tel: handler endpoint
    configured_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (provider_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
