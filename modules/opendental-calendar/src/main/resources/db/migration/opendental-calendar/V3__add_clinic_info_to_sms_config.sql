ALTER TABLE opendental_calendar_sms_config
  ADD COLUMN clinic_name    VARCHAR(128) NOT NULL DEFAULT '',
  ADD COLUMN clinic_address VARCHAR(256) NOT NULL DEFAULT '';
