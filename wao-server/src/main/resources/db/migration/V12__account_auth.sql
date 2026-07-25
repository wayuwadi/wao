-- 统一账号、第三方身份、密码凭据与跨节点 JDBC 会话。

CREATE TABLE account (
    id         VARCHAR(36) NOT NULL,
    member_id  VARCHAR(32) NOT NULL,
    status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_member (member_id),
    CONSTRAINT fk_account_member FOREIGN KEY (member_id) REFERENCES member (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE account_identity (
    id               VARCHAR(36)  NOT NULL,
    account_id       VARCHAR(36)  NOT NULL,
    provider         VARCHAR(16)  NOT NULL,
    provider_subject VARCHAR(191) NOT NULL,
    email            VARCHAR(254),
    display_name     VARCHAR(128),
    avatar_url       VARCHAR(1024),
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_identity_provider_subject (provider, provider_subject),
    UNIQUE KEY uk_identity_account_provider (account_id, provider),
    KEY idx_identity_email (email),
    CONSTRAINT fk_identity_account FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE password_credential (
    account_id          VARCHAR(36)  NOT NULL,
    password_hash       VARCHAR(100) NOT NULL,
    failed_attempts     INT          NOT NULL DEFAULT 0,
    locked_until        TIMESTAMP NULL,
    password_changed_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id),
    CONSTRAINT fk_password_account FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 两名既有成员先拥有空账号；首次飞书登录或设置邮箱密码时再绑定身份。
INSERT INTO account (id, member_id) VALUES
    ('acc-wg', 'wg'),
    ('acc-wd', 'wd');

CREATE TABLE SPRING_SESSION (
    PRIMARY_ID            CHAR(36)     NOT NULL,
    SESSION_ID            CHAR(36)     NOT NULL,
    CREATION_TIME         BIGINT       NOT NULL,
    LAST_ACCESS_TIME      BIGINT       NOT NULL,
    MAX_INACTIVE_INTERVAL INT          NOT NULL,
    EXPIRY_TIME           BIGINT       NOT NULL,
    PRINCIPAL_NAME        VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
) ENGINE = InnoDB ROW_FORMAT = DYNAMIC;

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36)     NOT NULL,
    ATTRIBUTE_NAME     VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES    BLOB         NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
) ENGINE = InnoDB ROW_FORMAT = DYNAMIC;
