ALTER TABLE feishu_notification ADD COLUMN card_title VARCHAR(512) NULL;
ALTER TABLE feishu_notification ADD COLUMN card_body TEXT NULL;
ALTER TABLE feishu_notification ADD COLUMN message_id VARCHAR(128) NULL;
ALTER TABLE feishu_notification ADD COLUMN feedback_message_id VARCHAR(128) NULL;
