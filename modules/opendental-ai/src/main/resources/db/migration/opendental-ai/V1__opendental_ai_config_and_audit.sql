-- Per-tenant config for the OpenDental AI module.
-- Credentials (Gemini API key, OD DeveloperKey, OD CustomerKey) live in
-- `tenant_credentials` (kernel V1) — see GeminiKeyResolver and
-- OpenDentalKeyResolver. This table now holds only non-credential settings.
CREATE TABLE opendental_ai_config (
    id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- Every tool call (run_opendental_query) is logged. HIPAA-friendly audit trail.
-- Retain rows indefinitely unless ops explicitly purges.
CREATE TABLE opendental_ai_query_log (
    id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    session_id     VARCHAR(64)   NOT NULL,
    user_email     VARCHAR(255),
    question       TEXT,
    sql_text       MEDIUMTEXT    NOT NULL,
    row_count      INT,
    elapsed_ms     INT,
    status         VARCHAR(32)   NOT NULL,   -- 'ok' | 'sql_rejected' | 'od_error' | 'internal_error'
    error_detail   TEXT,
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session (session_id),
    INDEX idx_created (created_at)
);
