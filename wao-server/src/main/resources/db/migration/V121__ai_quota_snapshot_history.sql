-- 保存真实额度采样的脱敏历史，供数据统计页回看刷新与每日消耗趋势。
-- 不复制供应商原始响应、OAuth Token、Cookie 或 API Key。
CREATE TABLE ai_quota_snapshot (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    source_key        VARCHAR(320) NOT NULL,
    account_label     VARCHAR(255) NOT NULL,
    provider          VARCHAR(24)  NOT NULL,
    window_key        VARCHAR(128) NOT NULL,
    window_label      VARCHAR(128) NOT NULL,
    remaining_percent TINYINT      NOT NULL,
    resets_at         DATETIME     NULL,
    observed_at       DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_quota_snapshot_observed (observed_at),
    KEY idx_quota_snapshot_series (source_key, window_key, observed_at),
    CONSTRAINT chk_quota_snapshot_remaining CHECK (remaining_percent BETWEEN 0 AND 100)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 已有集中账号的最新真实值可以安全形成第一条历史基线；未知值不补零。
INSERT INTO ai_quota_snapshot (
    source_key, account_label, provider, window_key, window_label,
    remaining_percent, resets_at, observed_at
)
SELECT CONCAT('central:', id), label, provider, quota_window,
       CASE quota_window
           WHEN 'session' THEN '五小时会话额度'
           WHEN 'five_hour' THEN '五小时会话额度'
           WHEN 'weekly_all' THEN '七天额度'
           WHEN 'seven_day' THEN '七天额度'
           WHEN 'weekly_opus' THEN '七天 Opus 额度'
           WHEN 'seven_day_opus' THEN '七天 Opus 额度'
           ELSE quota_window
       END,
       100 - quota_percent, quota_resets_at, last_synced_at
FROM ai_account
WHERE quota_status = 'SYNCED'
  AND quota_percent IS NOT NULL
  AND quota_window IS NOT NULL
  AND last_synced_at IS NOT NULL;
