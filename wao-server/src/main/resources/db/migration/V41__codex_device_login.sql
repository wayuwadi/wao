-- Codex 改用官方设备码登录：浏览器不再回调用户电脑的 localhost:1455。
ALTER TABLE ai_account_oauth_session
    MODIFY COLUMN code_verifier VARCHAR(128) NULL COMMENT '授权码模式的 PKCE code_verifier，不下发前端',
    ADD COLUMN flow VARCHAR(24) NOT NULL DEFAULT 'authorization_code' COMMENT 'authorization_code / device_code',
    ADD COLUMN device_auth_id VARCHAR(128) NULL COMMENT 'Codex 设备码会话 id，仅服务端使用',
    ADD COLUMN user_code VARCHAR(32) NULL COMMENT '用户在 OpenAI 页面确认的一次性短码',
    ADD COLUMN poll_interval_seconds INT NULL COMMENT 'OpenAI 返回的最小轮询间隔',
    ADD COLUMN last_polled_at DATETIME NULL COMMENT '最近一次向 OpenAI 查询授权结果的时间';
