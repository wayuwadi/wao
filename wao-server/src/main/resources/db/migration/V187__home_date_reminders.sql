-- 旧事项维持通用重要日；首页渠道与飞书渠道独立。
ALTER TABLE family_important_date
    ADD COLUMN event_kind VARCHAR(16) NOT NULL DEFAULT 'IMPORTANT',
    ADD COLUMN birthday_subject VARCHAR(16) NOT NULL DEFAULT 'OTHER',
    ADD COLUMN home_reminder_enabled BOOLEAN NOT NULL DEFAULT TRUE;
