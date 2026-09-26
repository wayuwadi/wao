-- Expand first: old nodes keep writing content, new nodes can read either source.
-- The historical bytes stay in MySQL until migration, backup and rollback gates pass.
ALTER TABLE image_blob
    ADD COLUMN object_key VARCHAR(255) NULL,
    ADD COLUMN object_content_type VARCHAR(100) NULL,
    ADD COLUMN object_byte_size BIGINT NULL,
    ADD COLUMN object_hash CHAR(64) NULL,
    ADD COLUMN preview_key VARCHAR(255) NULL,
    ADD COLUMN preview_checked BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN original_archive_key VARCHAR(255) NULL,
    ADD COLUMN lossless_webp_checked BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN webp_activated_at DATETIME(3) NULL,
    MODIFY COLUMN content LONGBLOB NULL,
    ALGORITHM=INPLACE,
    LOCK=NONE;

ALTER TABLE file_blob
    ADD COLUMN object_key VARCHAR(255) NULL,
    ADD COLUMN content_hash CHAR(64) NULL,
    MODIFY COLUMN content LONGBLOB NULL,
    ALGORITHM=INPLACE,
    LOCK=NONE;
