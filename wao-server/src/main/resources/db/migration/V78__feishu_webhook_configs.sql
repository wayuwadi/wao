-- 通用飞书自定义机器人 Webhook 注册表。
-- 地址继续复用 WAO_VAULT_KEY 做 AES-256-GCM 静态加密；列表接口只返回脱敏地址。
CREATE TABLE feishu_webhook_config (
    id                 VARCHAR(32)     NOT NULL,
    name               VARCHAR(64)     NOT NULL,
    description        VARCHAR(255)    NOT NULL DEFAULT '',
    webhook_ciphertext VARBINARY(2048) NOT NULL,
    webhook_nonce      VARBINARY(32)   NOT NULL,
    enabled            TINYINT(1)      NOT NULL DEFAULT 1,
    default_marker     VARCHAR(16)     NULL,
    created_at         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_feishu_webhook_default (default_marker),
    KEY idx_feishu_webhook_updated (updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 无损接住原先 system_setting 中的单例配置。旧值格式为 enc:<nonce base64>:<ciphertext base64>；
-- 这里只搬运密文和 nonce，不在 migration 或日志里还原明文。不可解码、nonce 非 12 bytes、
-- ciphertext 不在 AES-GCM 合法列边界内的旧值全部跳过，交给运行时兼容层落为“需重新填写”。
INSERT INTO feishu_webhook_config
    (id, name, description, webhook_ciphertext, webhook_nonce, enabled,
     default_marker, created_at, updated_at)
SELECT
    '00000000000000000000000000000001',
    '系统通知（原配置）',
    '由原全局飞书 Webhook 配置自动迁移',
    decoded.webhook_ciphertext,
    decoded.webhook_nonce,
    decoded.enabled,
    'SYSTEM',
    decoded.updated_at,
    decoded.updated_at
FROM (
    SELECT
        FROM_BASE64(SUBSTRING_INDEX(url_setting.cfg_value, ':', -1)) AS webhook_ciphertext,
        FROM_BASE64(SUBSTRING_INDEX(SUBSTRING_INDEX(url_setting.cfg_value, ':', 2), ':', -1))
            AS webhook_nonce,
        CASE WHEN LOWER(COALESCE(enabled_setting.cfg_value, 'false')) = 'true' THEN 1 ELSE 0 END
            AS enabled,
        COALESCE(url_setting.updated_at, CURRENT_TIMESTAMP) AS updated_at
    FROM system_setting url_setting
    LEFT JOIN system_setting enabled_setting
        ON enabled_setting.cfg_key = 'feishu.webhook.enabled'
    WHERE url_setting.cfg_key = 'feishu.webhook.url'
      AND url_setting.cfg_value LIKE 'enc:%:%'
) decoded
WHERE decoded.webhook_nonce IS NOT NULL
  AND OCTET_LENGTH(decoded.webhook_nonce) = 12
  AND decoded.webhook_ciphertext IS NOT NULL
  AND OCTET_LENGTH(decoded.webhook_ciphertext) BETWEEN 16 AND 2048;
