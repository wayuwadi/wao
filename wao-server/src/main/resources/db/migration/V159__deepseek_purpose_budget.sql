ALTER TABLE ai_assistant_run ADD COLUMN usage_purpose VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN' COMMENT '服务端可信 AI 用途';

CREATE TABLE deepseek_policy (
    id INT NOT NULL PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    daily_token_limit BIGINT NOT NULL DEFAULT 10000000,
    polish_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    assistant_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    tianren_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    background_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) COMMENT='全局 DeepSeek 功能白名单及每日预算';
INSERT INTO deepseek_policy(id) VALUES (1);

CREATE TABLE deepseek_daily_usage (
    usage_day DATE NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    budget_tokens BIGINT NOT NULL DEFAULT 0,
    actual_tokens BIGINT NOT NULL DEFAULT 0,
    request_count BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (usage_day, purpose)
) COMMENT='北京时间每日预算占用及已确认 Token；跨账号共享';
