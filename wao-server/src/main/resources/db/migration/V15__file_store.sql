CREATE TABLE file_blob (
  id VARCHAR(64) NOT NULL,
  name VARCHAR(255) NOT NULL,
  content_type VARCHAR(160) NOT NULL,
  byte_size BIGINT NOT NULL,
  created_by VARCHAR(64),
  created_at DATETIME(3) NOT NULL,
  content LONGBLOB NOT NULL,
  PRIMARY KEY (id),
  KEY idx_file_blob_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
