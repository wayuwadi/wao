-- 通知中心只新增无法从现有状态还原的“每次成功登录”事实；任务与 AI Run 动态继续读取原业务表。
CREATE TABLE account_login_event (
    id           VARCHAR(36) NOT NULL,
    account_id   VARCHAR(36) NOT NULL,
    member_id    VARCHAR(32) NOT NULL,
    provider     VARCHAR(16) NOT NULL,
    logged_in_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_login_event_member_time (member_id, logged_in_at),
    KEY idx_login_event_time (logged_in_at),
    CONSTRAINT fk_login_event_account FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE CASCADE,
    CONSTRAINT fk_login_event_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
