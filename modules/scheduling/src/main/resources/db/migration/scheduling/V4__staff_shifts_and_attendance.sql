ALTER TABLE staff_members ADD COLUMN location TINYINT UNSIGNED NOT NULL DEFAULT 0;

CREATE TABLE staff_shifts (
    id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    staff_id    BIGINT UNSIGNED NOT NULL,
    shift_date  DATE            NOT NULL,
    status      VARCHAR(20)     NOT NULL DEFAULT 'SCHEDULED',
    location    TINYINT UNSIGNED NOT NULL DEFAULT 0,
    notes       TEXT,
    created_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_staff_date (staff_id, shift_date),
    FOREIGN KEY (staff_id) REFERENCES staff_members(id) ON DELETE CASCADE
);

CREATE TABLE staff_attendance (
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    staff_id        BIGINT UNSIGNED NOT NULL,
    attendance_date DATE            NOT NULL,
    clock_in        TIME,
    clock_out       TIME,
    notes           TEXT,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (staff_id) REFERENCES staff_members(id) ON DELETE CASCADE
);
