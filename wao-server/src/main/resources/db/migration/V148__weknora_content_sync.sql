INSERT INTO system_setting(cfg_key,cfg_value,updated_at) VALUES('weknora-sync-lock','{}',NOW());
CREATE TABLE weknora_sync_config (
 id VARCHAR(32) PRIMARY KEY,
 owner_member_id VARCHAR(64) NOT NULL,
 kb_id VARCHAR(64) NOT NULL,
 api_key_cipher LONGBLOB NOT NULL,
 api_key_nonce VARBINARY(32) NOT NULL,
 modules_json JSON NOT NULL,
 batch_limit INT NOT NULL DEFAULT 20,
 sync_deletes BOOLEAN NOT NULL DEFAULT TRUE,
 revision INT NOT NULL DEFAULT 1,
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE TABLE weknora_sync_run (
 id VARCHAR(36) PRIMARY KEY,
 trigger_type VARCHAR(16) NOT NULL,
 status VARCHAR(24) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 started_at DATETIME(3) NULL,
 finished_at DATETIME(3) NULL,
 uploaded INT NOT NULL DEFAULT 0,
 skipped INT NOT NULL DEFAULT 0,
 removed INT NOT NULL DEFAULT 0,
 remaining INT NOT NULL DEFAULT 0,
 failed INT NOT NULL DEFAULT 0,
 message VARCHAR(500) NOT NULL DEFAULT '',
 INDEX idx_weknora_run_status (status, created_at)
);
CREATE TABLE weknora_sync_item (
 source_key VARCHAR(200) PRIMARY KEY,
 module_id VARCHAR(64) NOT NULL,
 source_title VARCHAR(500) NOT NULL,
 source_url VARCHAR(1000) NOT NULL,
 remote_id VARCHAR(64) NULL,
 content_hash VARCHAR(64) NULL,
 desired_hash VARCHAR(64) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'pending',
 parse_status VARCHAR(32) NOT NULL DEFAULT '',
 summary_status VARCHAR(32) NOT NULL DEFAULT '',
 error_message VARCHAR(250) NOT NULL DEFAULT '',
 last_run_id VARCHAR(36) NULL,
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
