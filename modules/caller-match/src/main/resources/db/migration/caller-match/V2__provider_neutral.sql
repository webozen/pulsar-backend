-- Make caller_match_log provider-agnostic. Existing rows (currently from RC)
-- pick up provider_id='ringcentral' via the column default.
ALTER TABLE caller_match_log
    CHANGE COLUMN rc_session_id provider_session_id VARCHAR(64) NULL,
    ADD COLUMN provider_id VARCHAR(32) NOT NULL DEFAULT 'ringcentral' AFTER direction;

CREATE INDEX idx_caller_match_log_provider_session ON caller_match_log (provider_session_id);
