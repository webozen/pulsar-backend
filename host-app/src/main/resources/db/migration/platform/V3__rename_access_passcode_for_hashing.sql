-- Rename access_passcode → access_passcode_hash and widen to fit BCrypt output
-- (bcrypt hashes are typically 60 chars; column grows to 100 for safety margin).
-- The existing plaintext values are migrated to real bcrypt hashes at startup by
-- PasscodeBackfillRunner — see kernel/.../PasscodeBackfillRunner.java.
ALTER TABLE public_tenants CHANGE COLUMN access_passcode access_passcode_hash VARCHAR(100) NOT NULL;
