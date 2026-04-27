-- Add the tenant's default SMS sender phone (E.164). Optional — when null
-- callers must supply fromPhone explicitly. Same row as the voice config
-- since RC Advanced bundles voice + SMS under one OAuth identity.
ALTER TABLE voice_provider_config
    ADD COLUMN default_sms_from_phone VARCHAR(32) NULL AFTER click_to_dial_url;
