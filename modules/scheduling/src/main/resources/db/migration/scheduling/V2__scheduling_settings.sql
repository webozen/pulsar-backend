CREATE TABLE scheduling_settings (
    id                   TINYINT UNSIGNED PRIMARY KEY,
    timezone             VARCHAR(64)  NOT NULL,
    business_hours_start TIME         NOT NULL,
    business_hours_end   TIME         NOT NULL,
    onboarded_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT single_row CHECK (id = 1)
) ENGINE=InnoDB;
