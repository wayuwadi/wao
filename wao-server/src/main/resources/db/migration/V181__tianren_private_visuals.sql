-- 卦象解读与配图按成员和解读运行绑定，图片不进入公开图床。
CREATE TABLE tianren_visual (
    interpretation_run_id VARCHAR(48) NOT NULL PRIMARY KEY,
    member_id VARCHAR(32) NOT NULL,
    reading_id VARCHAR(48) NOT NULL,
    protocol_version VARCHAR(40) NOT NULL,
    settings_snapshot TEXT NOT NULL,
    cast_context TEXT NOT NULL,
    state VARCHAR(24) NOT NULL,
    title VARCHAR(96) NULL,
    change_text VARCHAR(240) NULL,
    keep_text VARCHAR(240) NULL,
    scene TEXT NULL,
    requested_attempt_id VARCHAR(48) NULL,
    display_attempt_id VARCHAR(48) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_tianren_visual_reading (member_id, reading_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tianren_visual_attempt (
    id VARCHAR(48) NOT NULL PRIMARY KEY,
    interpretation_run_id VARCHAR(48) NOT NULL,
    member_id VARCHAR(32) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    image_run_id VARCHAR(48) NOT NULL,
    image_prompt TEXT NOT NULL,
    model VARCHAR(96) NOT NULL,
    state VARCHAR(24) NOT NULL,
    content MEDIUMBLOB NULL,
    content_hash VARCHAR(64) NULL,
    byte_size BIGINT NULL,
    error_message VARCHAR(400) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_tianren_visual_request (interpretation_run_id, request_id),
    UNIQUE KEY uk_tianren_visual_image_run (image_run_id),
    KEY idx_tianren_visual_attempt_member (member_id, interpretation_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
