-- A row version must advance independently of timestamp precision, including legacy writes.
ALTER TABLE hosted_page ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
