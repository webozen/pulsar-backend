-- Per-call summary written after Gemini post-call analysis.
CREATE TABLE call_intel_entry (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    rc_session_id     VARCHAR(64) NULL,
    direction         VARCHAR(16) NOT NULL DEFAULT 'unknown',
    caller_phone      VARCHAR(32) NULL,
    duration_sec      INT NULL,
    recording_url     TEXT NULL,
    transcript        LONGTEXT NULL,
    summary           TEXT NULL,
    action_items      JSON NULL,       -- list of short strings
    sentiment         VARCHAR(24) NULL,
    patient_intent    VARCHAR(64) NULL,
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_created_at (created_at),
    KEY idx_rc_session (rc_session_id),
    KEY idx_phone (caller_phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Raw RingCentral webhook payloads arrive here first; an enrichment job
-- (Kestra flow, to be added tomorrow) turns each into a call_intel_entry.
CREATE TABLE call_intel_webhook_queue (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    raw_payload   JSON NOT NULL,
    processed_at  TIMESTAMP NULL,
    error         TEXT NULL,
    received_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_processed (processed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
