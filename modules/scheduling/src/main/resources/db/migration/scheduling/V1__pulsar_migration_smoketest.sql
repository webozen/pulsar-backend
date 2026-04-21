-- Smoke-test migration: proves the per-tenant, per-module Flyway runner fires.
CREATE TABLE IF NOT EXISTS scheduling_smoketest (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
