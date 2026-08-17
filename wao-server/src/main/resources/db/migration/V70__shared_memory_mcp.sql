-- Codex 共享记忆 MCP：每台设备使用可独立吊销的随机令牌，只保存 SHA-256 摘要。
CREATE TABLE memory_access_token (
    id           VARCHAR(32)  NOT NULL,
    member_id    VARCHAR(32)  NOT NULL,
    name         VARCHAR(80)  NOT NULL,
    token_prefix VARCHAR(16)  NOT NULL,
    token_hash   CHAR(64)     NOT NULL,
    created_at   DATETIME     NOT NULL,
    last_used_at DATETIME     NULL,
    revoked_at   DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_memory_access_token_hash (token_hash),
    KEY idx_memory_access_token_member (member_id, revoked_at, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- MCP 主动记住的稳定偏好、职责、决策与规划进入真实知识库，所有成员和设备共享。
INSERT INTO kb (id, name, icon, color, project_id, auto, description, group_id, perm, ord, notes)
VALUES ('kb-memory', '共享记忆', 'ri-brain-line', '#5B6FD8', NULL, 1,
        '由 WAO 记忆 MCP 沉淀的稳定偏好、职责、决策和长期规划；可在文档页核对和修订。',
        'g1', '成员可编辑', 90, 0);

INSERT INTO kb_folder (id, kb_id, name, ord)
VALUES ('kb-memory-f', 'kb-memory', 'Codex 记忆', 1);
