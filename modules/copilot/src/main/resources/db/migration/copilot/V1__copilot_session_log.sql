-- Per-call Co-Pilot session log. One row per (provider_session_id, tenant) —
-- written when the WS opens, finalized when it closes. Suggestions accumulate
-- as JSON for replay/audit; transcript holds the staff-side mic transcription
-- (patient side requires WebPhone SDK hoist, deferred).
CREATE TABLE copilot_session_log (
    id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    provider_id            VARCHAR(32) NOT NULL DEFAULT 'ringcentral',
    provider_session_id    VARCHAR(64) NULL,
    started_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at               TIMESTAMP NULL,
    transcript             LONGTEXT NULL,
    suggestions            JSON NULL,
    PRIMARY KEY (id),
    KEY idx_started_at (started_at),
    KEY idx_provider_session (provider_session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
