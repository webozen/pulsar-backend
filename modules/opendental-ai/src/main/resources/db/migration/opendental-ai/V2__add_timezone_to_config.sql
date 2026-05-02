-- Add timezone column so the AI system prompt can inject the correct local date/time.
-- Defaults to America/New_York; updated via the onboarding wizard or admin API.
ALTER TABLE opendental_ai_config
    ADD COLUMN timezone VARCHAR(64) NOT NULL DEFAULT 'America/New_York';
