ALTER TABLE drawing_file
    ADD COLUMN deleted_at DATETIME(6) NULL,
    ADD INDEX idx_drawing_deleted_at (deleted_at);
