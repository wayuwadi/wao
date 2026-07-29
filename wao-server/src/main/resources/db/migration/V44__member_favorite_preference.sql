-- 收藏（项目 / 任务 / 文档）与成员级偏好（如左侧菜单顺序），都是按成员维度隔离的数据。
CREATE TABLE member_favorite (
    id          VARCHAR(48) NOT NULL,
    member_id   VARCHAR(32) NOT NULL,
    target_type VARCHAR(16) NOT NULL,
    target_id   VARCHAR(64) NOT NULL,
    created_at  DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_favorite_member_target (member_id, target_type, target_id),
    KEY idx_favorite_member (member_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE member_preference (
    member_id  VARCHAR(32)  NOT NULL,
    pref_key   VARCHAR(64)  NOT NULL,
    pref_value TEXT         NOT NULL,
    updated_at DATETIME     NOT NULL,
    PRIMARY KEY (member_id, pref_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
