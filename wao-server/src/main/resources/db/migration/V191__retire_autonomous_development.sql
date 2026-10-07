-- ADR-332: preserve history, revoke autonomous scheduling and outstanding leases.
UPDATE development_policy
SET enabled = FALSE, version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE enabled = TRUE;

UPDATE development_run
SET status = 'PAUSED', phase = '用户已终止自主建设',
    error = '用户明确要求终止并回滚自主建设；已有工作与证据保留，不再续跑',
    active_key = NULL, lease_token = NULL, lease_until = NULL,
    version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE status IN ('QUEUED', 'ANALYZING', 'IMPLEMENTING', 'VERIFYING',
                 'PR_READY', 'MERGED', 'DEPLOYING', 'WAITING_DECISION');

UPDATE development_decision
SET status = 'REJECTED', delivery_status = 'CANCELLED',
    reply = '用户已终止并回滚自主建设，旧决定不得继续执行'
WHERE status = 'PENDING';
