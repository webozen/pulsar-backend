-- Per-tenant config for the OpenDental AI module.
-- gemini_key      — Google AI Studio key for this tenant's Live API session
-- od_developer_key — OpenDental DeveloperKey (paid, one per vendor app)
-- od_customer_key  — OpenDental CustomerKey (one per customer database)
CREATE TABLE opendental_ai_config (
    id               BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    gemini_key       TEXT          NOT NULL,
    od_developer_key TEXT          NOT NULL,
    od_customer_key  TEXT          NOT NULL,
    updated_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
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
