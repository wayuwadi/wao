-- Routine rules are private to a member. Revisions define what each business day shows;
-- occurrences exist only after a deliberate status action, never as copied tasks.
CREATE TABLE wao_todo_routine_template (
  id CHAR(36) NOT NULL PRIMARY KEY,
  member_id VARCHAR(64) NOT NULL,
  version INT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  KEY idx_todo_routine_template_member (member_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE wao_todo_routine_revision (
  id CHAR(36) NOT NULL PRIMARY KEY,
  template_id CHAR(36) NOT NULL,
  member_id VARCHAR(64) NOT NULL,
  effective_from DATE NOT NULL,
  effective_to DATE,
  title VARCHAR(120) NOT NULL,
  period VARCHAR(16) NOT NULL,
  weekdays_json JSON NOT NULL,
  sort_order INT NOT NULL,
  active TINYINT(1) NOT NULL,
  archived TINYINT(1) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_todo_routine_revision_start (template_id, effective_from),
  KEY idx_todo_routine_revision_member_dates (member_id, effective_from, effective_to),
  CONSTRAINT fk_todo_routine_revision_template FOREIGN KEY (template_id)
    REFERENCES wao_todo_routine_template (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE wao_todo_routine_occurrence (
  template_id CHAR(36) NOT NULL,
  member_id VARCHAR(64) NOT NULL,
  business_date DATE NOT NULL,
  revision_id CHAR(36) NOT NULL,
  title VARCHAR(120) NOT NULL,
  period VARCHAR(16) NOT NULL,
  sort_order INT NOT NULL,
  status VARCHAR(16) NOT NULL,
  version INT NOT NULL,
  recorded_at DATETIME(6) NOT NULL,
  PRIMARY KEY (template_id, business_date),
  KEY idx_todo_routine_occurrence_member_day (member_id, business_date),
  CONSTRAINT fk_todo_routine_occurrence_template FOREIGN KEY (template_id)
    REFERENCES wao_todo_routine_template (id),
  CONSTRAINT fk_todo_routine_occurrence_revision FOREIGN KEY (revision_id)
    REFERENCES wao_todo_routine_revision (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
