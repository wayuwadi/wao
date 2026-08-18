-- Runner 通过本机 Codex app-server 读取脱敏额度；OAuth 凭据与认证文件始终留在执行电脑。
-- ADR-083：持久化 Runner 上报的 Codex 脱敏额度快照。
CREATE TABLE codex_runner_quota (
    runner_id        VARCHAR(32)  NOT NULL PRIMARY KEY,
    account_email    VARCHAR(255) NULL,
    plan             VARCHAR(32)  NULL,
    snapshot_json    TEXT         NULL,
    error_message    VARCHAR(500) NULL,
    last_success_at  DATETIME     NULL,
    last_checked_at  DATETIME     NOT NULL,
    CONSTRAINT fk_codex_runner_quota_runner
        FOREIGN KEY (runner_id) REFERENCES ai_runner_node(id) ON DELETE CASCADE
);

-- 新心跳字段只有新版 Runner 会发送；部署后让现有 Codex 节点完成一次原地自更新。
UPDATE ai_runner_node
SET update_requested_at = CURRENT_TIMESTAMP
WHERE CONCAT(',', REPLACE(LOWER(engines), ' ', ''), ',') LIKE '%,codex,%';
