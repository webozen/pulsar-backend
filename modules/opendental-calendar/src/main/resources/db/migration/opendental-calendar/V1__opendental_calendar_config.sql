CREATE TABLE opendental_calendar_config (
    id               BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    od_developer_key TEXT      NOT NULL,
    od_customer_key  TEXT      NOT NULL,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
