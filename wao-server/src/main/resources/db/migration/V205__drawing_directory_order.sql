CREATE TABLE drawing_directory_order (
    member_id VARCHAR(32) NOT NULL PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0,
    order_json MEDIUMTEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
