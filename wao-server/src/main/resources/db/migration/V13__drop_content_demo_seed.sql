-- 清空「文件与图床」模块的演示数据。
--
-- V4__content_seed.sql 往 file_item / image_item 塞了一批虚构的示例文件与图片
-- （链接指向 i.wayuwa.example 这种不存在的域名）。图床已经是真实功能，用户上传
-- 的图存在 image_blob 里，示例数据混在列表里只会干扰。
--
-- 不修改 V4 本身（已执行的 migration 不可改），改为在这里删除；其他模块
-- （项目、任务、知识库、密钥库、日历、自动化）的演示数据保持不变。

DELETE FROM image_item;
DELETE FROM file_item;
