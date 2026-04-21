CREATE TABLE IF NOT EXISTS public_tenants (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    slug                   VARCHAR(63)  NOT NULL UNIQUE,
    name                   VARCHAR(255) NOT NULL,
    db_name                VARCHAR(64)  NOT NULL UNIQUE,
    active_modules         TEXT         NOT NULL DEFAULT (''),
    access_passcode_hash   VARCHAR(100) NOT NULL,
    suspended_at           DATETIME     NULL,
    created_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
