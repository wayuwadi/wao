-- 在既有家庭重要日真实投递链路上扩展阴历录入和可选飞书群提醒。
-- 表名保留以兼容已上线数据；新增记录在产品层统一称为“日历提醒事项”。
ALTER TABLE family_important_date
    ADD COLUMN calendar_type VARCHAR(16) NOT NULL DEFAULT 'SOLAR' AFTER event_date,
    ADD COLUMN lunar_month INT NULL AFTER calendar_type,
    ADD COLUMN lunar_day INT NULL AFTER lunar_month,
    ADD COLUMN lunar_leap_month TINYINT(1) NOT NULL DEFAULT 0 AFTER lunar_day,
    ADD COLUMN notification_type VARCHAR(16) NOT NULL DEFAULT 'DIRECT' AFTER reminder_time,
    ADD COLUMN chat_id VARCHAR(128) NULL AFTER notification_type,
    ADD COLUMN chat_name VARCHAR(128) NULL AFTER chat_id;

CREATE INDEX idx_family_important_date_calendar_type
    ON family_important_date (member_id, calendar_type, event_date);
