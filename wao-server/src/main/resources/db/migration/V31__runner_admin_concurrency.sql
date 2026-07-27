-- 支持在后台为每台执行电脑单独配置最大并发线程数，优先于 Runner 本地自报值。
ALTER TABLE ai_runner_node
    ADD COLUMN admin_max_concurrency INT NULL AFTER max_concurrency;
