-- 旧文档 tags_json 为 NULL，接口按空标签返回；正文和标签共用文档版本。
ALTER TABLE doc ADD COLUMN tags_json JSON NULL COMMENT '小记标签名称数组';

CREATE TABLE note_tag (
    name VARCHAR(32) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY COMMENT '共享标签名称'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '小记标签字典';
