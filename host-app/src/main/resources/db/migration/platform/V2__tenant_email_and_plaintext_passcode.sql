ALTER TABLE public_tenants
    ADD COLUMN contact_email VARCHAR(255) NOT NULL DEFAULT '',
    ADD COLUMN access_passcode VARCHAR(64) NOT NULL DEFAULT '';

UPDATE public_tenants SET access_passcode = access_passcode_hash WHERE access_passcode = '';

ALTER TABLE public_tenants DROP COLUMN access_passcode_hash;
