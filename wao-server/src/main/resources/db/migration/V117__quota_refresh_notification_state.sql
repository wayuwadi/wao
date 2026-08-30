-- 额度刷新群通知的独立比较基线。这里只保存脱敏后的窗口、剩余百分比和重置时间，
-- 不复制供应商原始响应或任何登录凭据；发送失败时保留旧基线，下一次真实采样继续重试。
CREATE TABLE quota_refresh_notification_state (
    source_key    VARCHAR(320) NOT NULL,
    account_label VARCHAR(255) NOT NULL,
    snapshot_json TEXT         NOT NULL,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    PRIMARY KEY (source_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
