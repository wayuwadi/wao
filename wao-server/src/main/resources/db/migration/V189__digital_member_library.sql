-- 数字成员私有资产与不可变形象版本；不改变已有账号、图床和旧令牌权限。
CREATE TABLE digital_member_profile (
 member_id VARCHAR(32) PRIMARY KEY, gender VARCHAR(8) NOT NULL DEFAULT 'unknown', revision BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE digital_member_binding (
 member_id VARCHAR(32) PRIMARY KEY, subject_id VARCHAR(32) NULL, version_id VARCHAR(32) NULL, revision BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE digital_subject (
 id VARCHAR(32) PRIMARY KEY, member_id VARCHAR(32) NOT NULL, name VARCHAR(80) NOT NULL,
 kind VARCHAR(8) NOT NULL, relation VARCHAR(80) NOT NULL DEFAULT '', gender VARCHAR(8) NOT NULL DEFAULT 'unknown',
 traits TEXT NOT NULL, main_asset_id VARCHAR(32) NULL, revision BIGINT NOT NULL DEFAULT 0,
 state VARCHAR(12) NOT NULL DEFAULT 'active', created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX idx_digital_subject_owner(member_id,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE digital_asset (
 id VARCHAR(32) PRIMARY KEY, subject_id VARCHAR(32) NOT NULL, member_id VARCHAR(32) NOT NULL,
 name VARCHAR(200) NOT NULL, role VARCHAR(12) NOT NULL, content_type VARCHAR(32) NOT NULL,
 original_content MEDIUMBLOB NOT NULL, content MEDIUMBLOB NOT NULL, content_hash CHAR(64) NOT NULL,
 width INT NOT NULL, height INT NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX idx_digital_asset_subject(subject_id,member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE digital_look (
 id VARCHAR(32) PRIMARY KEY, subject_id VARCHAR(32) NOT NULL, member_id VARCHAR(32) NOT NULL,
 name VARCHAR(80) NOT NULL, style VARCHAR(1000) NOT NULL, revision BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), INDEX idx_digital_look_subject(subject_id,member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE digital_appearance_version (
 id VARCHAR(32) PRIMARY KEY, look_id VARCHAR(32) NOT NULL, member_id VARCHAR(32) NOT NULL,
 version_number INT NOT NULL, state VARCHAR(12) NOT NULL DEFAULT 'candidate',
 asset_ids TEXT NOT NULL, portrait_asset_id VARCHAR(32) NULL, snapshot_json TEXT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), confirmed_at DATETIME(6) NULL,
 UNIQUE KEY uk_digital_version_number(look_id,version_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE digital_generation_use (
 run_id VARCHAR(32) PRIMARY KEY, member_id VARCHAR(32) NOT NULL, subject_id VARCHAR(32) NULL,
 version_id VARCHAR(32) NULL, mode VARCHAR(12) NOT NULL, purpose VARCHAR(20) NOT NULL,
 input_hash CHAR(64) NOT NULL, payload_hash CHAR(64) NOT NULL, snapshot_json TEXT NOT NULL,
 result_look_id VARCHAR(32) NULL, result_version_id VARCHAR(32) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), INDEX idx_digital_use_subject(subject_id,member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 一篇小记每次人工保存的身份选择；后续自动阶段按原cycle读取，不丢失本次设置。
CREATE TABLE digital_note_identity (
 doc_id VARCHAR(32) NOT NULL, cycle_id VARCHAR(32) NOT NULL, member_id VARCHAR(32) NOT NULL,
 selection_json TEXT NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(doc_id,cycle_id,member_id), INDEX idx_digital_note_owner(member_id,doc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
