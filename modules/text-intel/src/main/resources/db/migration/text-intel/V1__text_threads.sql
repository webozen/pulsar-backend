-- Per-tenant SMS / IM conversation store + Gemini-generated summary.
-- Owned by text-intel; text-copilot reads from these tables.

CREATE TABLE text_thread (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    provider_id         VARCHAR(32) NOT NULL DEFAULT 'ringcentral',
    thread_key          VARCHAR(255) NOT NULL,    -- provider's conversation id (or fallback fromto pair)
    patient_phone       VARCHAR(32) NULL,         -- inferred from inbound side
    matched_patnum      BIGINT NULL,              -- caller-match-style enrichment, optional
    last_message_at     TIMESTAMP NULL,
    message_count       INT NOT NULL DEFAULT 0,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uniq_provider_thread (provider_id, thread_key),
    KEY idx_last_message_at (last_message_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE text_message (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    thread_id            BIGINT UNSIGNED NOT NULL,
    provider_message_id  VARCHAR(128) NULL,
    direction            VARCHAR(16) NOT NULL,     -- inbound | outbound
    from_phone           VARCHAR(32) NULL,
    to_phone             VARCHAR(32) NULL,
    body                 TEXT NULL,
    media_urls           JSON NULL,
    status               VARCHAR(32) NULL,         -- received | sent | delivered | failed
    sent_at              TIMESTAMP NULL,
    created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_thread (thread_id, sent_at),
    KEY idx_provider_msg (provider_message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE text_intel_summary (
    thread_id            BIGINT UNSIGNED NOT NULL,
    summary              TEXT NULL,
    sentiment            VARCHAR(24) NULL,
    intent               VARCHAR(64) NULL,
    action_items         JSON NULL,
    last_message_id      BIGINT UNSIGNED NULL,    -- summary covers messages up to this
    generated_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (thread_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Same shape as call-intel's webhook landing — raw provider payloads queued
-- here by the controller, processed by the background job (or /process).
CREATE TABLE text_intel_webhook_queue (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    provider_id   VARCHAR(32) NOT NULL DEFAULT 'ringcentral',
    raw_payload   JSON NOT NULL,
    processed_at  TIMESTAMP NULL,
    error         TEXT NULL,
    received_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_processed (processed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
