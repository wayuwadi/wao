-- 密钥库域关系建模：分组 / 条目，替换 V3 中通用 payload 的 vault_group 表。
-- 密码以 AES-256-GCM 加密后存储（密文 + 随机 nonce），加密密钥来自应用配置（环境变量 WAO_VAULT_KEY），
-- 不落库、不出现在任何列表 / 详情响应中，只在显式「查看」接口按需解密一次。

DROP TABLE IF EXISTS vault_group;

CREATE TABLE vault_group (
    id   VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    ord  INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE vault_item (
    id                VARCHAR(32)  NOT NULL,
    group_id          VARCHAR(32)  NOT NULL,
    name              VARCHAR(128) NOT NULL,
    icon              VARCHAR(64)  NOT NULL DEFAULT 'ri-key-2-line',
    user_name         VARCHAR(128),
    site              VARCHAR(256),
    note              VARCHAR(512),
    secret_ciphertext VARBINARY(1024),
    secret_nonce      VARBINARY(32),
    updated_label     VARCHAR(32),
    ord               INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_item_group (group_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
