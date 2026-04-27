ALTER TABLE voice_provider_config
  ADD COLUMN rc_client_id     VARCHAR(255) NULL AFTER oauth_expires_at,
  ADD COLUMN rc_client_secret VARCHAR(255) NULL AFTER rc_client_id;
