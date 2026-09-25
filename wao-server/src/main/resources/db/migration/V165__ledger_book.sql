-- 账务本默认私人，只有明确加入的成员可读取或编辑；结算记录只追加，不删除。
CREATE TABLE ledger_book (
    id              VARCHAR(36)  NOT NULL,
    owner_member_id VARCHAR(32)  NOT NULL,
    title           VARCHAR(128) NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ledger_book_owner (owner_member_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE ledger_book_member (
    book_id    VARCHAR(36) NOT NULL,
    member_id  VARCHAR(32) NOT NULL,
    role       VARCHAR(12) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (book_id, member_id),
    KEY idx_ledger_member (member_id, book_id),
    CONSTRAINT fk_ledger_member_book FOREIGN KEY (book_id) REFERENCES ledger_book(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE ledger_entry (
    id                VARCHAR(36)   NOT NULL,
    book_id           VARCHAR(36)   NOT NULL,
    title             VARCHAR(160)  NOT NULL,
    counterparty      VARCHAR(160)  NOT NULL,
    category          VARCHAR(12)   NOT NULL,
    direction         VARCHAR(12)   NOT NULL,
    status            VARCHAR(16)   NOT NULL,
    amount            DECIMAL(15,2) NOT NULL,
    occurred_on       DATE NULL,
    due_on            DATE NULL,
    evidence          VARCHAR(1000) NULL,
    dispute_note      VARCHAR(1000) NULL,
    next_action       VARCHAR(1000) NULL,
    created_by        VARCHAR(32)   NOT NULL,
    updated_by        VARCHAR(32)   NOT NULL,
    version           INT           NOT NULL DEFAULT 1,
    created_at        DATETIME(6)   NOT NULL,
    updated_at        DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ledger_entry_book (book_id, updated_at),
    KEY idx_ledger_entry_due (book_id, due_on),
    CONSTRAINT fk_ledger_entry_book FOREIGN KEY (book_id) REFERENCES ledger_book(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE ledger_settlement (
    id         VARCHAR(36)   NOT NULL,
    entry_id   VARCHAR(36)   NOT NULL,
    amount     DECIMAL(15,2) NOT NULL,
    settled_on DATE          NOT NULL,
    note       VARCHAR(500)  NULL,
    created_by VARCHAR(32)   NOT NULL,
    created_at DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ledger_settlement_entry (entry_id, settled_on),
    CONSTRAINT fk_ledger_settlement_entry FOREIGN KEY (entry_id) REFERENCES ledger_entry(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE ledger_entry_revision (
    id         VARCHAR(36) NOT NULL,
    entry_id   VARCHAR(36) NOT NULL,
    before_json JSON       NOT NULL,
    after_json  JSON       NOT NULL,
    changed_by VARCHAR(32) NOT NULL,
    changed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ledger_revision_entry (entry_id, changed_at),
    CONSTRAINT fk_ledger_revision_entry FOREIGN KEY (entry_id) REFERENCES ledger_entry(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
