CREATE TABLE wao_application_skill_binding (
    id VARCHAR(64) PRIMARY KEY,
    application_id VARCHAR(64) NOT NULL,
    skill_id VARCHAR(64) NOT NULL,
    skill_version_id VARCHAR(64) NOT NULL,
    created_by VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_wao_application_skill (application_id, skill_id),
    KEY idx_wao_application_skill_version (skill_version_id),
    CONSTRAINT fk_wao_application_skill_skill FOREIGN KEY (skill_id) REFERENCES skill(id) ON DELETE CASCADE,
    CONSTRAINT fk_wao_application_skill_version FOREIGN KEY (skill_version_id) REFERENCES skill_version(id)
);
