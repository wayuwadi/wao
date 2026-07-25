-- 知识库模块完善：小记知识库、真实时间戳、回收站软删除、协同在场。
--
-- 1) 小记知识库：全局「新建小记」的默认落点，每个组织只保留一个（notes = 1）。
-- 2) 真实时间戳：doc 原来只有 created_label / updated_label 这种现成文案，前端无法
--    做「几分钟前」这类相对展示，也无法排序；补 created_at / updated_at，旧行保留文案兜底。
-- 3) 回收站：kb / kb_folder / doc 一律软删除，deleted_at 非空即在回收站，7 天后由
--    调度任务物理删除。cascade = 1 表示随父级一起被删，恢复父级时一并恢复，回收站列表里不单独展示。
-- 4) doc_presence：文档协同在场表，记录「谁正打开这篇文档、光标在第几行」，心跳续期。

ALTER TABLE kb
    ADD COLUMN notes      TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否为小记知识库',
    ADD COLUMN deleted_at DATETIME NULL COMMENT '进入回收站的时间，NULL 表示未删除',
    ADD COLUMN deleted_by VARCHAR(32) NULL COMMENT '删除人 member id';

ALTER TABLE kb_folder
    ADD COLUMN deleted_at DATETIME NULL,
    ADD COLUMN cascade_del TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1 = 随父级删除，恢复父级时一并恢复';

ALTER TABLE doc
    ADD COLUMN created_at  DATETIME NULL,
    ADD COLUMN updated_at  DATETIME NULL,
    ADD COLUMN rev         INT NOT NULL DEFAULT 1 COMMENT '内容版本号，每次正文更新 +1，供协同增量拉取',
    ADD COLUMN deleted_at  DATETIME NULL,
    ADD COLUMN deleted_by  VARCHAR(32) NULL,
    ADD COLUMN cascade_del TINYINT(1) NOT NULL DEFAULT 0;

CREATE INDEX idx_kb_deleted ON kb (deleted_at);
CREATE INDEX idx_folder_deleted ON kb_folder (deleted_at);
CREATE INDEX idx_doc_deleted ON doc (deleted_at);

CREATE TABLE doc_presence (
    id        VARCHAR(96) NOT NULL COMMENT '<doc_id>:<member_id>',
    doc_id    VARCHAR(32) NOT NULL,
    member_id VARCHAR(32) NOT NULL,
    line      INT         NOT NULL DEFAULT -1 COMMENT '光标所在的顶层块序号，-1 表示只在查看',
    editing   TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '最近一次心跳前是否在输入',
    seen_at   DATETIME    NOT NULL COMMENT '最后一次心跳时间，过期即视为离开',
    PRIMARY KEY (id),
    KEY idx_presence_doc (doc_id, seen_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 小记知识库（固定 id，便于各节点判等；notes = 1 保证唯一语义）
INSERT INTO kb (id, name, icon, color, project_id, auto, description, group_id, perm, ord, notes)
VALUES ('kb-notes', '小记', 'ri-sticky-note-line', '#7A5CC6', NULL, 0,
        '随手记下的想法与片段，不用先想清楚该放进哪个知识库。', 'g1', '仅自己', 0, 1);

INSERT INTO kb_folder (id, kb_id, name, ord) VALUES ('kb-notes-f', 'kb-notes', '小记', 1);
