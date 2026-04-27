-- Platform-scoped audit log for outbound tenant lifecycle calls to the
-- workflow platform. One row per attempted POST. Successful calls are
-- recorded as resolved-immediately for traceability; failed calls have
-- resolved_at NULL and are retried by TenantSyncAuditService every 60s.
CREATE TABLE tenant_sync_audit (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    slug          VARCHAR(63) NOT NULL,
    request_path  VARCHAR(255) NOT NULL,
    request_json  LONGTEXT NULL,
    last_status   INT NULL,
    last_error    TEXT NULL,
    attempts      INT NOT NULL DEFAULT 0,
    resolved_at   TIMESTAMP NULL,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_slug (slug),
    KEY idx_unresolved (resolved_at, id),
    KEY idx_attempts (attempts)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
