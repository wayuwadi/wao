-- 同一条飞书消息可在客户端内继续编辑。保存最后一次成功同步的原始内容指纹，
-- 让 Linux / Windows 双节点都扫到消息时仍只更新一次原任务。
ALTER TABLE feishu_quick_collect_event
    ADD COLUMN source_fingerprint CHAR(64) NULL AFTER target_id;
