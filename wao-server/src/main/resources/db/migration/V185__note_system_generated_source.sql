-- 展示来源与真实账号所有权分开；历史记录不按标题、作者或标签自动猜测。
ALTER TABLE doc ADD COLUMN system_generated TINYINT(1) NOT NULL DEFAULT 0;
