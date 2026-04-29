-- Per-tenant translate settings overlay. NULL means "use platform default" (env-driven).
ALTER TABLE translate_config
    ADD COLUMN session_duration_min   SMALLINT UNSIGNED NULL,
    ADD COLUMN extend_grant_min       SMALLINT UNSIGNED NULL,
    ADD COLUMN max_extends            SMALLINT UNSIGNED NULL,
    ADD COLUMN history_enabled        BOOLEAN           NULL,
    ADD COLUMN history_retention_days SMALLINT UNSIGNED NULL,
    ADD COLUMN settings_updated_at    TIMESTAMP         NULL;

-- Conversation history. transcript_ciphertext is AES-256-GCM-encrypted JSON of
-- the turn list ([{role, text, ts}, ...]); iv (12-byte) and tag (16-byte) are
-- stored alongside so a single key (PULSAR_TRANSLATE_HISTORY_KEY env) can
-- decrypt without per-row metadata. Soft-delete with deleted_at; the retention
-- scheduler hard-deletes rows where deleted_at < NOW() - history_retention_days.
CREATE TABLE translate_conversations (
    id                    BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
    started_at            TIMESTAMP        NOT NULL,
    ended_at              TIMESTAMP        NULL,
    source_lang           VARCHAR(16)      NOT NULL,
    target_lang           VARCHAR(16)      NOT NULL,
    mode                  VARCHAR(32)      NOT NULL,
    extends_used          SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    transcript_ciphertext LONGBLOB         NULL,
    transcript_iv         VARBINARY(12)    NULL,
    transcript_tag        VARBINARY(16)    NULL,
    deleted_at            TIMESTAMP        NULL,
    created_at            TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_translate_conversations_started_at (started_at),
    INDEX idx_translate_conversations_deleted_at (deleted_at)
);
