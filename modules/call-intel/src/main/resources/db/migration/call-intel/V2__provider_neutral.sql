-- Make call_intel provider-agnostic. RingCentral remains the default but
-- the column shape now accommodates any VoIP adapter (Twilio, Zoom Phone,
-- Aircall…). RENAME COLUMN preserves existing rows.
ALTER TABLE call_intel_entry
    CHANGE COLUMN rc_session_id provider_session_id VARCHAR(64) NULL,
    ADD COLUMN provider_id VARCHAR(32) NOT NULL DEFAULT 'ringcentral' AFTER id;

ALTER TABLE call_intel_webhook_queue
    ADD COLUMN provider_id VARCHAR(32) NOT NULL DEFAULT 'ringcentral' AFTER id;

CREATE INDEX idx_call_intel_entry_provider_session ON call_intel_entry (provider_session_id);
