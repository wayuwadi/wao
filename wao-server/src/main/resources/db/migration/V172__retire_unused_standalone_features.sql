-- 下线已确认废弃的独立功能。仅删除专属表；项目 AI 执行、决策与工程记忆继续保留。
DROP TABLE IF EXISTS personal_principle_revision;
DROP TABLE IF EXISTS personal_principle_checkin;
DROP TABLE IF EXISTS personal_principle_target;
DROP TABLE IF EXISTS personal_principle;

DROP TABLE IF EXISTS crawler_capability;
DROP TABLE IF EXISTS verification_message;
DROP TABLE IF EXISTS account_resource;

DROP TABLE IF EXISTS ledger_entry_revision;
DROP TABLE IF EXISTS ledger_settlement;
DROP TABLE IF EXISTS ledger_entry;
DROP TABLE IF EXISTS ledger_book_member;
DROP TABLE IF EXISTS ledger_book;

DROP TABLE IF EXISTS english_learning_session;
DROP TABLE IF EXISTS english_learning_profile;
DROP TABLE IF EXISTS vocabulary_entry;
DROP TABLE IF EXISTS food_workspace;
DROP TABLE IF EXISTS appearance_photo;
DROP TABLE IF EXISTS appearance_workspace;

DROP TABLE IF EXISTS game_run_record;
DROP TABLE IF EXISTS game_save;
DROP TABLE IF EXISTS product_update_event;
DROP TABLE IF EXISTS ai_session_preference;

DROP TABLE IF EXISTS action_reminder_delivery;
DROP TABLE IF EXISTS action_day_note;
DROP TABLE IF EXISTS action_checkin;
DROP TABLE IF EXISTS action_plan_item;
DROP TABLE IF EXISTS action_plan;
DROP TABLE IF EXISTS personal_workspace;

DROP TABLE IF EXISTS caldav_event;
DROP TABLE IF EXISTS caldav_calendar;
DROP TABLE IF EXISTS caldav_account;

-- 独立记忆中心、设备 MCP 与本机 Codex 会话归档；项目工程记忆和决策记录继续使用统一版本表。
DROP TABLE IF EXISTS memory_collection_document;
DROP TABLE IF EXISTS memory_access_grant;
DROP TABLE IF EXISTS memory_usage_log;
DROP TABLE IF EXISTS memory_access_token;
DROP TABLE IF EXISTS personal_memory;

DELETE FROM memory_document
 WHERE scope_type <> 'project'
   AND NOT (scope_type = 'personal' AND category = 'decision');
DELETE FROM memory_outbox WHERE aggregate_id NOT IN (SELECT id FROM memory_document);

DELETE FROM doc WHERE kb_id IN ('kb-memory', 'kb-codex-records');
DELETE FROM kb_folder WHERE kb_id IN ('kb-memory', 'kb-codex-records');
DELETE FROM kb WHERE id IN ('kb-memory', 'kb-codex-records');

ALTER TABLE ai_runner_node DROP COLUMN memory_collection_enabled;
ALTER TABLE ai_runner_node DROP COLUMN last_memory_collection_attempt_at;
ALTER TABLE ai_runner_node DROP COLUMN last_memory_collected_at;
ALTER TABLE ai_runner_node DROP COLUMN last_memory_collection_error;

DELETE FROM system_setting WHERE cfg_key IN (
    'account-resources.ingest-token-hash',
    'account-resources.crawler-token-hash',
    'account-resources.notification-webhook-id',
    'auth.wechat-official.enabled',
    'auth.wechat-official.app-id',
    'auth.wechat-official.app-secret',
    'auth.wechat-official.token'
);

-- 微信登录下线后删除其历史绑定和专属登录事件；账号、成员及其他登录方式保留。
DELETE FROM account_login_event WHERE provider = 'wechat';
DELETE FROM account_identity WHERE provider = 'wechat';
