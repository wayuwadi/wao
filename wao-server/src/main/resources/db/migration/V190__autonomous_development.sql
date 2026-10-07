CREATE TABLE development_policy (
 member_id VARCHAR(64) NOT NULL PRIMARY KEY, enabled BOOLEAN NOT NULL DEFAULT FALSE,
 interval_hours INT NOT NULL DEFAULT 24, max_parallel INT NOT NULL DEFAULT 2,
 version BIGINT NOT NULL DEFAULT 0, next_scan_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 feature_cursor INT NOT NULL DEFAULT 0, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE development_run (
 id VARCHAR(64) NOT NULL PRIMARY KEY, member_id VARCHAR(64) NOT NULL, feature_id VARCHAR(64) NOT NULL,
 title VARCHAR(240) NOT NULL, goal TEXT NOT NULL, status VARCHAR(32) NOT NULL, phase VARCHAR(64) NOT NULL,
 plan_json LONGTEXT NULL, agents_json LONGTEXT NULL, evidence_json LONGTEXT NULL, error TEXT NULL,
 version BIGINT NOT NULL DEFAULT 0, active_key VARCHAR(140) NULL, lease_token VARCHAR(64) NULL,
 lease_until DATETIME NULL, worktree_path TEXT NULL, session_id VARCHAR(64) NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uq_development_active(active_key), KEY idx_development_owner(member_id,created_at),
 KEY idx_development_queue(status,lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE development_decision (
 id VARCHAR(64) NOT NULL PRIMARY KEY, run_id VARCHAR(64) NOT NULL, member_id VARCHAR(64) NOT NULL,
 revision BIGINT NOT NULL, question TEXT NOT NULL, reason TEXT NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'PENDING', delivery_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
 app_id VARCHAR(64) NULL, open_id VARCHAR(128) NULL, message_id VARCHAR(128) NULL,
 reply TEXT NULL, send_attempts INT NOT NULL DEFAULT 0, available_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 expires_at DATETIME NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uq_development_decision(run_id,revision), KEY idx_development_delivery(delivery_status,available_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE development_worker (
 member_id VARCHAR(64) NOT NULL PRIMARY KEY, instance_id VARCHAR(64) NOT NULL,
 last_seen_at DATETIME NOT NULL, message VARCHAR(240) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
