CREATE TABLE ai_notes_config (
    id           TINYINT UNSIGNED PRIMARY KEY,
    plaud_token  TEXT             NOT NULL,
    connected_at TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT single_row CHECK (id = 1)
);
