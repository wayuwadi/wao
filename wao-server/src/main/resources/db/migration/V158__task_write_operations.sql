CREATE TABLE wao_task_write_operation (
    account_id VARCHAR(64) NOT NULL,
    operation_id CHAR(36) NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    result_gzip MEDIUMBLOB NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (account_id, operation_id),
    INDEX idx_task_write_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
