-- 额度驱动的自主进化调度状态。单例行通过 next_check_at 条件更新在双节点间原子抢占。
CREATE TABLE ai_evolution_state (
    id                 VARCHAR(32)  NOT NULL,
    next_check_at      DATETIME     NOT NULL,
    last_checked_at    DATETIME,
    last_outcome       VARCHAR(512),
    last_run_id        VARCHAR(32),
    last_suggestion_id VARCHAR(64),
    created_at         DATETIME     NOT NULL,
    updated_at         DATETIME     NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO ai_evolution_state (
    id, next_check_at, last_checked_at, last_outcome, last_run_id, last_suggestion_id, created_at, updated_at
) VALUES (
    'autonomous-evolution', DATE_ADD(NOW(), INTERVAL 2 MINUTE), NULL,
    '等待首次检查', NULL, NULL, NOW(), NOW()
);
