-- 草图仅保留最近三个实际发生内容变化的场景快照；revision 继续单调递增用于乐观锁。
CREATE TABLE drawing_file_version (
    file_id     VARCHAR(32) NOT NULL,
    revision    INT         NOT NULL,
    scene_json  LONGTEXT    NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (file_id, revision),
    CONSTRAINT fk_drawing_version_file FOREIGN KEY (file_id) REFERENCES drawing_file (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 已有且已经改动过的文件至少保留当前快照，后续每次真实改动后滚动淘汰到三份。
INSERT INTO drawing_file_version (file_id, revision, scene_json, created_at)
SELECT id, revision, scene_json, updated_at
FROM drawing_file
WHERE revision > 1;
