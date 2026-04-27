-- Per-tenant screen-pop audit log for the caller-match module.
-- Every inbound/outbound call event the frontend forwards lands here so staff
-- can review a "Recent calls" strip and admins can audit lookups.
CREATE TABLE caller_match_log (
    id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    phone                  VARCHAR(32) NOT NULL,
    direction              VARCHAR(16) NOT NULL DEFAULT 'unknown',
    rc_session_id          VARCHAR(64) NULL,
    caller_name            VARCHAR(128) NULL,
    matched_patnum         BIGINT NULL,
    matched_patient_name   VARCHAR(128) NULL,
    matched_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_matched_at (matched_at),
    KEY idx_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
