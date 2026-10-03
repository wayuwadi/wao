-- Daily history begins at this migration. Existing current state is a baseline, never a reconstructed past.
CREATE TABLE wao_todo_recording_scope (
  id TINYINT NOT NULL PRIMARY KEY,
  started_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO wao_todo_recording_scope (id, started_at)
VALUES (1, UTC_TIMESTAMP(6) + INTERVAL 8 HOUR);

CREATE TABLE wao_todo_task_day_event (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  member_id VARCHAR(64) NOT NULL,
  task_id VARCHAR(64) NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  business_date DATE NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  actor_id VARCHAR(64),
  before_snapshot JSON,
  after_snapshot JSON,
  operation_id CHAR(36),
  KEY idx_todo_event_member_day (member_id, business_date, id),
  KEY idx_todo_event_member_task_time (member_id, task_id, occurred_at, id),
  KEY idx_todo_event_task (task_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE wao_todo_task_day_entry (
  member_id VARCHAR(64) NOT NULL,
  task_id VARCHAR(64) NOT NULL,
  business_date DATE NOT NULL,
  first_at DATETIME(6) NOT NULL,
  last_at DATETIME(6) NOT NULL,
  snapshot JSON NOT NULL,
  PRIMARY KEY (member_id, task_id, business_date),
  KEY idx_todo_entry_member_day (member_id, business_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE wao_todo_day_note_context (
  doc_id VARCHAR(32) NOT NULL PRIMARY KEY,
  member_id VARCHAR(64) NOT NULL,
  business_date DATE NOT NULL,
  created_at DATETIME(6) NOT NULL,
  late TINYINT(1) NOT NULL DEFAULT 0,
  task_id VARCHAR(64),
  KEY idx_todo_note_member_day (member_id, business_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_todo_doc_author_created ON doc (author_id, kb_id, created_at);

CREATE TABLE wao_todo_operation_receipt (
  account_id VARCHAR(64) NOT NULL,
  operation_id CHAR(36) NOT NULL,
  operation_type VARCHAR(32) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  result_json JSON NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (account_id, operation_id),
  KEY idx_todo_receipt_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- The baseline is captured at the actual adoption time. It does not assert any earlier action.
INSERT INTO wao_todo_task_day_event
    (member_id, task_id, occurred_at, business_date, event_type, actor_id, after_snapshot)
SELECT t.owner_id, t.id, s.started_at, DATE(s.started_at), 'BASELINE', NULL,
       JSON_OBJECT('title', t.title, 'status', t.status, 'owner', t.owner_id,
         'parts', IF(JSON_TYPE(JSON_EXTRACT(t.detail_json, '$.parts')) = 'ARRAY', JSON_EXTRACT(t.detail_json, '$.parts'), JSON_ARRAY()),
         'steps', IF(JSON_TYPE(JSON_EXTRACT(t.detail_json, '$.steps')) = 'ARRAY', JSON_EXTRACT(t.detail_json, '$.steps'), JSON_ARRAY()),
         'subs', IF(JSON_TYPE(JSON_EXTRACT(t.detail_json, '$.subs')) = 'ARRAY', JSON_EXTRACT(t.detail_json, '$.subs'), JSON_ARRAY()),
         'start', t.start_date, 'startAt', NULLIF(JSON_UNQUOTE(JSON_EXTRACT(t.detail_json, '$.startAt')), 'null'),
         'endAt', NULLIF(JSON_UNQUOTE(JSON_EXTRACT(t.detail_json, '$.endAt')), 'null'),
         'schedulePeriod', NULLIF(JSON_UNQUOTE(JSON_EXTRACT(t.detail_json, '$.schedulePeriod')), 'null'),
         'parentTaskId', t.parent_task_id, 'project', t.project_id)
  FROM task t CROSS JOIN wao_todo_recording_scope s
 WHERE t.owner_id IS NOT NULL AND t.owner_id <> '';

INSERT INTO wao_todo_task_day_event
    (member_id, task_id, occurred_at, business_date, event_type, actor_id, after_snapshot)
SELECT DISTINCT p.member_id, t.id, s.started_at, DATE(s.started_at), 'BASELINE', NULL,
       JSON_OBJECT('title', t.title, 'status', t.status, 'owner', t.owner_id,
         'parts', IF(JSON_TYPE(JSON_EXTRACT(t.detail_json, '$.parts')) = 'ARRAY', JSON_EXTRACT(t.detail_json, '$.parts'), JSON_ARRAY()),
         'steps', IF(JSON_TYPE(JSON_EXTRACT(t.detail_json, '$.steps')) = 'ARRAY', JSON_EXTRACT(t.detail_json, '$.steps'), JSON_ARRAY()),
         'subs', IF(JSON_TYPE(JSON_EXTRACT(t.detail_json, '$.subs')) = 'ARRAY', JSON_EXTRACT(t.detail_json, '$.subs'), JSON_ARRAY()),
         'start', t.start_date, 'startAt', NULLIF(JSON_UNQUOTE(JSON_EXTRACT(t.detail_json, '$.startAt')), 'null'),
         'endAt', NULLIF(JSON_UNQUOTE(JSON_EXTRACT(t.detail_json, '$.endAt')), 'null'),
         'schedulePeriod', NULLIF(JSON_UNQUOTE(JSON_EXTRACT(t.detail_json, '$.schedulePeriod')), 'null'),
         'parentTaskId', t.parent_task_id, 'project', t.project_id)
  FROM task t CROSS JOIN wao_todo_recording_scope s
  JOIN JSON_TABLE(COALESCE(t.detail_json, '{}'), '$.parts[*]'
       COLUMNS (member_id VARCHAR(64) PATH '$')) p
 WHERE p.member_id IS NOT NULL AND p.member_id <> '' AND p.member_id <> COALESCE(t.owner_id, '');
