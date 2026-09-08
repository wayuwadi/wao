-- 更新日记独立保存成功交付的 Git 产品提交；部署、测试和文档等工程提交由服务层过滤。
CREATE TABLE product_update_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id VARCHAR(32) NOT NULL,
    commit_sha VARCHAR(64) NOT NULL,
    scope VARCHAR(64) NOT NULL,
    commit_subject VARCHAR(255) NOT NULL,
    committed_at DATETIME NOT NULL,
    UNIQUE KEY uk_product_update_commit (commit_sha),
    KEY idx_product_update_committed_at (committed_at)
);

-- 复用既有功能迭代中已由成功 AI Run 验证的 Git 提交；历史行以原事件记录时间作为交付日期。
INSERT IGNORE INTO product_update_event(run_id, commit_sha, scope, commit_subject, committed_at)
SELECT run_id, commit_sha, feature_id, commit_subject, created_at
  FROM function_iteration_event
 WHERE commit_subject LIKE 'feat(%'
    OR commit_subject LIKE 'fix(%'
    OR commit_subject LIKE 'perf(%';
