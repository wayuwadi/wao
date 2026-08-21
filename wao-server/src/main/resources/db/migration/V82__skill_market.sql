-- WAO Skill 市场：独立发布者身份、不可变版本、审核与收藏。
CREATE TABLE skill_publisher (
    id           VARCHAR(64)  NOT NULL,
    member_id    VARCHAR(32),
    handle       VARCHAR(32)  NOT NULL,
    display_name VARCHAR(64)  NOT NULL,
    created_at   DATETIME(3)  NOT NULL,
    updated_at   DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_skill_publisher_member (member_id),
    UNIQUE KEY uk_skill_publisher_handle (handle),
    CONSTRAINT fk_skill_publisher_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE skill (
    id                 VARCHAR(64)  NOT NULL,
    publisher_id       VARCHAR(64)  NOT NULL,
    slug               VARCHAR(64)  NOT NULL,
    display_name       VARCHAR(128) NOT NULL,
    summary            VARCHAR(1000) NOT NULL,
    category           VARCHAR(40)  NOT NULL,
    tags_json          JSON,
    status             VARCHAR(24)  NOT NULL,
    published_version_id VARCHAR(64),
    download_count     BIGINT NOT NULL DEFAULT 0,
    favorite_count     BIGINT NOT NULL DEFAULT 0,
    created_at         DATETIME(3) NOT NULL,
    updated_at         DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_skill_namespace_slug (publisher_id, slug),
    KEY idx_skill_market (status, category, updated_at),
    CONSTRAINT fk_skill_publisher FOREIGN KEY (publisher_id) REFERENCES skill_publisher (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE skill_version (
    id                VARCHAR(64)  NOT NULL,
    skill_id          VARCHAR(64)  NOT NULL,
    version           VARCHAR(32)  NOT NULL,
    display_name      VARCHAR(128) NOT NULL,
    summary           VARCHAR(1000) NOT NULL,
    category          VARCHAR(40) NOT NULL,
    tags_json         JSON,
    changelog         VARCHAR(1000),
    license_code      VARCHAR(32)  NOT NULL,
    rights_confirmed  TINYINT(1) NOT NULL DEFAULT 0,
    tested_platforms_json JSON,
    compatible_platforms_json JSON,
    state             VARCHAR(24)  NOT NULL,
    package_name      VARCHAR(180) NOT NULL,
    package_size      BIGINT NOT NULL,
    unpacked_size     BIGINT NOT NULL,
    file_count        INT NOT NULL,
    sha256            CHAR(64) NOT NULL,
    file_manifest_json JSON NOT NULL,
    scan_findings_json JSON,
    skill_md          MEDIUMTEXT NOT NULL,
    package_content   LONGBLOB NOT NULL,
    submitted_at      DATETIME(3),
    reviewed_at       DATETIME(3),
    reviewer_id       VARCHAR(32),
    review_note       VARCHAR(1000),
    created_at        DATETIME(3) NOT NULL,
    updated_at        DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_skill_version (skill_id, version),
    KEY idx_skill_review_queue (state, submitted_at),
    CONSTRAINT fk_skill_version_skill FOREIGN KEY (skill_id) REFERENCES skill (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE skill_favorite (
    skill_id   VARCHAR(64) NOT NULL,
    member_id  VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (skill_id, member_id),
    CONSTRAINT fk_skill_favorite_skill FOREIGN KEY (skill_id) REFERENCES skill (id) ON DELETE CASCADE,
    CONSTRAINT fk_skill_favorite_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
