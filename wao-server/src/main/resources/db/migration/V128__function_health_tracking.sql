-- 功能健康地图从真实页面打开和已验收提交开始累计，不回填演示数据。
CREATE TABLE function_product (
    feature_id VARCHAR(64) PRIMARY KEY,
    manual_adjustment INT NOT NULL DEFAULT 0,
    iteration_count INT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE function_usage_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    member_id VARCHAR(64) NOT NULL,
    feature_id VARCHAR(64) NOT NULL,
    source VARCHAR(32) NOT NULL DEFAULT 'PAGE',
    opened_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_function_usage_member_time (member_id, opened_at),
    KEY idx_function_usage_feature_time (feature_id, opened_at)
);

CREATE TABLE function_iteration_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id VARCHAR(32) NOT NULL,
    feature_id VARCHAR(64) NOT NULL,
    commit_sha VARCHAR(64) NOT NULL,
    commit_subject VARCHAR(255) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_function_iteration_commit (feature_id, commit_sha),
    KEY idx_function_iteration_run (run_id)
);
