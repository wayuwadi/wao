-- Codex OAuth、自定义 Responses API 与单次运行账号路由（见 ADR-032）。
ALTER TABLE ai_account
    ADD COLUMN base_url     VARCHAR(512) NULL COMMENT '自定义 API 根地址；官方 OAuth 账号为空',
    ADD COLUMN model        VARCHAR(128) NULL COMMENT '自定义 API 默认模型',
    ADD COLUMN api_protocol VARCHAR(24)  NULL COMMENT '自定义 API 协议，目前为 responses';

ALTER TABLE ai_task_run
    ADD COLUMN ai_account_id VARCHAR(32) NULL AFTER engine,
    ADD INDEX idx_ai_run_account (ai_account_id);
