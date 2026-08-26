CREATE TABLE project_skill_binding (
    id VARCHAR(64) PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL,
    skill_id VARCHAR(64) NOT NULL,
    skill_version_id VARCHAR(64) NOT NULL,
    created_by VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_project_skill (project_id, skill_id),
    KEY idx_project_skill_version (skill_version_id),
    CONSTRAINT fk_project_skill_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
    CONSTRAINT fk_project_skill_skill FOREIGN KEY (skill_id) REFERENCES skill(id) ON DELETE CASCADE,
    CONSTRAINT fk_project_skill_version FOREIGN KEY (skill_version_id) REFERENCES skill_version(id)
);

ALTER TABLE ai_task_run
    ADD COLUMN capability_manifest_json JSON NULL COMMENT '入队时固化的项目 Skill 能力清单',
    ADD COLUMN capability_audit_json JSON NULL COMMENT 'Runner 实际加载 Skill 的审计结果';
