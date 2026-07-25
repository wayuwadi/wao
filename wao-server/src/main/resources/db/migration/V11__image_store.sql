-- 图床：用户上传的图片正文与元数据。
-- 存 MySQL 而不是容器本地目录：主 / 从两个节点的 wao-server 都连主节点的 MySQL
-- （见 deploy/docker-compose-slave.yml 的 MYSQL_HOST），落库即天然「放在主节点上」，
-- 且容器重建、节点故障转移都不会丢图，无需额外 volume 与 Nginx 定向路由。

CREATE TABLE image_blob (
    id           VARCHAR(40)  NOT NULL,
    name         VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    byte_size    BIGINT       NOT NULL,
    -- 来源描述，如「任务 · 整理证件盒」，用于图床列表分组展示
    source       VARCHAR(160) NULL,
    created_at   DATETIME     NOT NULL,
    content      LONGBLOB     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_image_blob_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
