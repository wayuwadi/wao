-- Skill 发布者可显式 Pass 扫描警告；原始命中仍保留给管理员复核。
ALTER TABLE skill_version
    ADD COLUMN scan_override_confirmed TINYINT(1) NOT NULL DEFAULT 0 AFTER scan_findings_json;
