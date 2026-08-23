-- 飞书 Open ID 按应用隔离：登录应用与群机器人应用看到的同一成员 Open ID 不同。
-- 额外保存同开发商下稳定的 Union ID，用于跨应用安全归属个人任务。
ALTER TABLE account_identity
    ADD COLUMN provider_union_subject VARCHAR(191) NULL AFTER provider_subject;

CREATE UNIQUE INDEX uk_identity_provider_union_subject
    ON account_identity (provider, provider_union_subject);
