ALTER TABLE doc
    ADD COLUMN auto_sync_scope VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '自动归档的稳定来源标识；飞书群聊使用 chat_id',
    ADD COLUMN auto_sync_date DATE NULL
        COMMENT '按自然日覆盖的自动归档日期',
    ADD COLUMN auto_sync_version BIGINT NULL
        COMMENT '数据库签发的自动归档抓取版本；拒绝旧快照晚到覆盖',
    ADD UNIQUE KEY uk_doc_auto_sync_identity (kb_id, auto_sync_scope, auto_sync_date);

-- 只为迁移时已配置的唯一群回填旧的“每日一篇”归档，保留原 doc_id/链接。
-- 若历史并发曾产生同标题重复文档，优先领用存活文档中 id 最小的一篇；
-- 只有全部副本均已删除时才回退到全体最小 id，避免恢复旧副本后出现两篇可见文档。
UPDATE doc AS target
JOIN (
    SELECT COALESCE(
               MIN(CASE WHEN candidate.deleted_at IS NULL THEN candidate.id END),
               MIN(candidate.id)
           ) AS id
      FROM doc AS candidate
      JOIN system_setting AS kb_setting
        ON kb_setting.cfg_key = 'feishu.chat-sync.kb-id'
       AND candidate.kb_id = TRIM(kb_setting.cfg_value)
     WHERE candidate.auto = 1
       AND candidate.title REGEXP '^飞书群聊 · [0-9]{4}-[0-9]{2}-[0-9]{2}$'
     GROUP BY candidate.kb_id, candidate.title
) AS legacy ON legacy.id = target.id
JOIN system_setting AS chat_setting
  ON chat_setting.cfg_key = 'feishu.chat-sync.chat-id'
 AND TRIM(chat_setting.cfg_value) <> ''
   SET target.auto_sync_scope = TRIM(chat_setting.cfg_value),
       target.auto_sync_date = STR_TO_DATE(
           SUBSTRING(target.title, CHAR_LENGTH('飞书群聊 · ') + 1), '%Y-%m-%d'),
       target.auto_sync_version = 0;

ALTER TABLE image_blob
    ADD COLUMN dedupe_key CHAR(64) NULL
        COMMENT '仅服务端去重写入使用的 SHA-256；用户主动上传保持 NULL',
    ADD UNIQUE KEY uk_image_blob_dedupe_key (dedupe_key);

CREATE TABLE feishu_chat_archive_sequence (
    id TINYINT NOT NULL PRIMARY KEY,
    next_value BIGINT NOT NULL
);

INSERT INTO feishu_chat_archive_sequence (id, next_value) VALUES (1, 0);

CREATE TABLE chat_review_run (
    id VARCHAR(32) PRIMARY KEY,
    chat_id VARCHAR(128) NOT NULL,
    chat_name VARCHAR(255) NOT NULL,
    kb_id VARCHAR(32) NOT NULL,
    speaker_one_id VARCHAR(128) NOT NULL,
    speaker_one_name VARCHAR(128) NOT NULL,
    speaker_two_id VARCHAR(128) NOT NULL,
    speaker_two_name VARCHAR(128) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    focus_text VARCHAR(1000) NULL,
    stage VARCHAR(16) NOT NULL,
    failed_stage VARCHAR(16) NULL,
    progress_current INT NOT NULL DEFAULT 0,
    progress_total INT NOT NULL,
    processing_date DATE NULL,
    failed_date DATE NULL,
    message_count INT NOT NULL DEFAULT 0,
    ai_run_id VARCHAR(32) NULL,
    retry_of_id VARCHAR(32) NULL,
    sources_json JSON NULL,
    report_json JSON NULL,
    error_text TEXT NULL,
    created_by VARCHAR(32) NOT NULL,
    created_by_name VARCHAR(128) NOT NULL,
    worker_token VARCHAR(64) NULL,
    lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL,
    active_chat_id VARCHAR(128) GENERATED ALWAYS AS (
        CASE WHEN stage IN ('WAITING', 'SYNCING', 'VERIFYING', 'ANALYZING') THEN 'active' ELSE NULL END
    ) STORED,
    UNIQUE KEY uk_chat_review_active_chat (active_chat_id),
    INDEX idx_chat_review_stage_created (stage, created_at),
    INDEX idx_chat_review_finished (finished_at),
    INDEX idx_chat_review_ai_run (ai_run_id),
    CONSTRAINT fk_chat_review_ai_run FOREIGN KEY (ai_run_id)
        REFERENCES ai_assistant_run(id) ON DELETE SET NULL,
    CONSTRAINT fk_chat_review_retry FOREIGN KEY (retry_of_id)
        REFERENCES chat_review_run(id) ON DELETE SET NULL
);

ALTER TABLE ai_assistant_run
    ADD COLUMN claim_token VARCHAR(64) NULL
        COMMENT '每次 Runner 领取的唯一尝试令牌，防止旧结果完成重领任务';
