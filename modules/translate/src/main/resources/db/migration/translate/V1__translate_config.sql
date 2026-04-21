CREATE TABLE translate_config (
    id            TINYINT UNSIGNED PRIMARY KEY,
    gemini_key    TEXT             NOT NULL,
    configured_at TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT translate_config_single_row CHECK (id = 1)
);
