-- 图床按内容去重：服务端自己抓来的图（飞书群聊截图等）以内容摘要为幂等键。
-- 重复运行同一天的聊天记录同步时，同一张图不该在 image_blob 里再存一份 LONGBLOB。

ALTER TABLE image_blob
    ADD COLUMN content_hash CHAR(64) NULL COMMENT '图片正文的 SHA-256 十六进制摘要';

-- 非唯一索引：只有走去重路径的写入才查它，用户主动上传仍允许同图多份
-- （各自独立，删掉其中一份不会让别处的引用失效）。
CREATE INDEX idx_image_blob_hash ON image_blob (content_hash, byte_size);
