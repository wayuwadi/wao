-- “天人一卦”按需生成不可猜测的公开分享凭据；公开接口只投影卦象，不暴露成员与私有 AI 会话。
ALTER TABLE tianren_daily_reading
    ADD COLUMN share_token CHAR(32) NULL AFTER conversation_id;

ALTER TABLE tianren_daily_reading
    ADD CONSTRAINT uk_tianren_share_token UNIQUE (share_token);
