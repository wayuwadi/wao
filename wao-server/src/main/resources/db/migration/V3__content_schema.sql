-- 内容 / 参考域：知识库、文档、自动化、文件、图床、密码库、日历、日程、AI 建议、目标。
-- 均为「文档表」：payload 存完整 JSON（与前端类型一致），ord 保证展示顺序。

CREATE TABLE kb             (id VARCHAR(32) NOT NULL, ord INT NOT NULL, payload JSON, PRIMARY KEY (id)) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
CREATE TABLE doc            (id VARCHAR(32) NOT NULL, ord INT NOT NULL, payload JSON, PRIMARY KEY (id)) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
CREATE TABLE job            (id VARCHAR(32) NOT NULL, ord INT NOT NULL, payload JSON, PRIMARY KEY (id)) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
CREATE TABLE file_item      (id VARCHAR(32) NOT NULL, ord INT NOT NULL, payload JSON, PRIMARY KEY (id)) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
CREATE TABLE image_item     (id VARCHAR(32) NOT NULL, ord INT NOT NULL, payload JSON, PRIMARY KEY (id)) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
CREATE TABLE vault_group    (id VARCHAR(32) NOT NULL, ord INT NOT NULL, payload JSON, PRIMARY KEY (id)) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
CREATE TABLE calendar_event (id VARCHAR(32) NOT NULL, ord INT NOT NULL, payload JSON, PRIMARY KEY (id)) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
CREATE TABLE schedule_item  (id VARCHAR(32) NOT NULL, ord INT NOT NULL, payload JSON, PRIMARY KEY (id)) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
CREATE TABLE suggestion     (id VARCHAR(32) NOT NULL, ord INT NOT NULL, payload JSON, PRIMARY KEY (id)) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
CREATE TABLE goal           (id VARCHAR(32) NOT NULL, ord INT NOT NULL, payload JSON, PRIMARY KEY (id)) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
