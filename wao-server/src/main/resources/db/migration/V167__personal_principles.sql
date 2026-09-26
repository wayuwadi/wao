CREATE TABLE personal_principle (
    id VARCHAR(36) PRIMARY KEY,
    owner_member_id VARCHAR(64) NOT NULL,
    title VARCHAR(240) NOT NULL,
    category VARCHAR(24) NOT NULL,
    trigger_text VARCHAR(240) NOT NULL,
    desired_action VARCHAR(500) NOT NULL,
    exception_text VARCHAR(500) NOT NULL DEFAULT '',
    reminder_time TIME NULL,
    evidence_source VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    version INT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    INDEX idx_principle_owner (owner_member_id, updated_at)
);

CREATE TABLE personal_principle_target (
    principle_id VARCHAR(36) NOT NULL,
    member_id VARCHAR(64) NOT NULL,
    consent VARCHAR(16) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (principle_id, member_id),
    INDEX idx_principle_target_member (member_id, consent),
    CONSTRAINT fk_principle_target_rule FOREIGN KEY (principle_id) REFERENCES personal_principle(id)
);

CREATE TABLE personal_principle_checkin (
    principle_id VARCHAR(36) NOT NULL,
    member_id VARCHAR(64) NOT NULL,
    checkin_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL,
    note VARCHAR(500) NOT NULL DEFAULT '',
    principle_version INT NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (principle_id, member_id, checkin_date),
    CONSTRAINT fk_principle_checkin_rule FOREIGN KEY (principle_id) REFERENCES personal_principle(id)
);

CREATE TABLE personal_principle_revision (
    principle_id VARCHAR(36) NOT NULL,
    version INT NOT NULL,
    snapshot_json JSON NOT NULL,
    changed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (principle_id, version),
    CONSTRAINT fk_principle_revision_rule FOREIGN KEY (principle_id) REFERENCES personal_principle(id)
);
