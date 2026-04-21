CREATE TABLE content_items (
    id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    item_id     VARCHAR(128)  NOT NULL,
    title       VARCHAR(255)  NOT NULL,
    category    VARCHAR(64)   NOT NULL DEFAULT 'general',
    type        VARCHAR(32)   NOT NULL DEFAULT 'runbook',
    content_data TEXT         NOT NULL,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_item_id (item_id)
);

CREATE TABLE content_files (
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    file_id         VARCHAR(128)  NOT NULL,
    filename        VARCHAR(255)  NOT NULL,
    content_type    VARCHAR(128)  NOT NULL,
    file_path       VARCHAR(512)  NOT NULL,
    file_size       BIGINT        NOT NULL DEFAULT 0,
    category        VARCHAR(64)   NOT NULL DEFAULT 'general',
    anythingllm_doc VARCHAR(512),
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_file_id (file_id)
);
