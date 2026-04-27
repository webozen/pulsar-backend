-- Toggle: when true, the frontend uses the @ringcentral/web-phone SDK in the
-- parent page (gives us the remote audio MediaStream). When false (default),
-- it iframes the hosted Embeddable (no remote audio).
ALTER TABLE voice_provider_config
    ADD COLUMN use_web_phone_sdk TINYINT(1) NOT NULL DEFAULT 0 AFTER click_to_dial_url;
