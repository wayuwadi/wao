-- Record subsequent edits without assigning a fictitious edit time to existing rows.
ALTER TABLE task ADD COLUMN updated_at DATETIME(6) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(6);
