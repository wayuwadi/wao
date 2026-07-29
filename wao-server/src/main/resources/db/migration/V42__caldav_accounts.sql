-- CalDAV 日历账户接入：账户（凭据 AES-256-GCM 加密）、订阅的日历、同步下来的事件。
-- 事件是服务端定时/手动从 CalDAV 服务器单向拉取的只读副本，每次同步按日历整体重建。

CREATE TABLE caldav_account (
    id                  VARCHAR(32)   NOT NULL,
    member_id           VARCHAR(32)   NOT NULL,
    name                VARCHAR(128)  NOT NULL,
    server_url          VARCHAR(512)  NOT NULL,
    username            VARCHAR(255)  NOT NULL,
    password_ciphertext VARBINARY(1024) NOT NULL,
    password_nonce      VARBINARY(32) NOT NULL,
    status              VARCHAR(16)   NOT NULL DEFAULT 'ok',
    last_sync_at        DATETIME      NULL,
    sync_error          VARCHAR(512)  NULL,
    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_caldav_account_member (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE caldav_calendar (
    id         VARCHAR(32)  NOT NULL,
    account_id VARCHAR(32)  NOT NULL,
    href       VARCHAR(512) NOT NULL,
    name       VARCHAR(128) NOT NULL,
    color      VARCHAR(16)  NOT NULL DEFAULT '#0E8C8C',
    enabled    TINYINT(1)   NOT NULL DEFAULT 1,
    ord        INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_caldav_calendar_account (account_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE caldav_event (
    id          VARCHAR(40)  NOT NULL,
    calendar_id VARCHAR(32)  NOT NULL,
    uid         VARCHAR(255) NOT NULL,
    summary     VARCHAR(512) NOT NULL,
    location    VARCHAR(512) NULL,
    start_at    DATETIME     NOT NULL,
    end_at      DATETIME     NULL,
    all_day     TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_caldav_event_cal_start (calendar_id, start_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
