-- AI 账号支持 OAuth 登录与真实额度快照（见 docs/adr/031-ai-account-oauth-login-and-quota.md）。
-- V38 只能手动粘贴凭据 JSON，且额度依赖一个从未配置的 usage-url，实际永远是 NO_API。
-- 这里补上：PKCE 登录会话、令牌有效期（用于自动续期）和结构化的额度字段。

ALTER TABLE ai_account
    ADD COLUMN auth_method      VARCHAR(16)  NOT NULL DEFAULT 'manual' COMMENT '凭据来源：oauth 登录 / manual 粘贴',
    ADD COLUMN plan             VARCHAR(64)  NULL COMMENT '订阅套餐，如 pro / max',
    ADD COLUMN token_expires_at DATETIME     NULL COMMENT 'access token 过期时间，到期前自动用 refresh token 续期',
    ADD COLUMN quota_window     VARCHAR(32)  NULL COMMENT '最紧张的那条限额窗口，如 five_hour / seven_day',
    ADD COLUMN quota_percent    INT          NULL COMMENT '该窗口已用百分比，0-100',
    ADD COLUMN quota_resets_at  DATETIME     NULL COMMENT '该窗口的重置时间';

-- PKCE 登录会话：state 与 code_verifier 一一对应，换到 token 或过期后即删除。
CREATE TABLE ai_account_oauth_session (
    state         VARCHAR(64)  NOT NULL COMMENT 'OAuth state，同时是本表主键',
    provider      VARCHAR(24)  NOT NULL COMMENT 'claude / codex',
    code_verifier VARCHAR(128) NOT NULL COMMENT 'PKCE code_verifier，不下发前端',
    label         VARCHAR(128) NULL COMMENT '登录完成后要给账号起的名字',
    created_by    VARCHAR(32)  NULL COMMENT '发起登录的成员 id',
    created_at    DATETIME     NOT NULL,
    expires_at    DATETIME     NOT NULL COMMENT '超过即视为失效，十分钟',
    PRIMARY KEY (state),
    INDEX idx_ai_oauth_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
