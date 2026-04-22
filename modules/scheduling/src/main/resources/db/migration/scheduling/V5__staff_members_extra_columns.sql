-- StaffController's INSERT references columns not added by V3/V4.
-- `location` is already added by V4 as TINYINT UNSIGNED; only these two are missing.
ALTER TABLE staff_members
    ADD COLUMN address            VARCHAR(500) NULL,
    ADD COLUMN emergency_contact  VARCHAR(255) NULL;
