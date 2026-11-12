CREATE TABLE task_comment_image (
    image_id VARCHAR(64) NOT NULL PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL,
    KEY idx_task_comment_image_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
