CREATE TABLE IF NOT EXISTS english_learning_profile (
    id VARCHAR(32) PRIMARY KEY,
    member_id VARCHAR(32) NOT NULL,
    goal VARCHAR(500) NOT NULL,
    daily_target_minutes SMALLINT UNSIGNED NOT NULL,
    start_date DATE NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_english_profile_member (member_id)
);

CREATE TABLE IF NOT EXISTS english_learning_session (
    id VARCHAR(32) PRIMARY KEY,
    member_id VARCHAR(32) NOT NULL,
    study_date DATE NOT NULL,
    lesson_id VARCHAR(32) NOT NULL,
    listening_done BOOLEAN NOT NULL DEFAULT FALSE,
    speaking_done BOOLEAN NOT NULL DEFAULT FALSE,
    reading_done BOOLEAN NOT NULL DEFAULT FALSE,
    writing_done BOOLEAN NOT NULL DEFAULT FALSE,
    practice_minutes SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    confidence TINYINT UNSIGNED NULL,
    writing_text TEXT NOT NULL,
    reflection TEXT NOT NULL,
    next_focus VARCHAR(500) NOT NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_english_session_member_date (member_id, study_date),
    KEY idx_english_session_member_completed (member_id, completed_at, study_date)
);
