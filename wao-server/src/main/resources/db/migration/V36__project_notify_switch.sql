-- 项目级通知开关：飞书群里每个项目的动静都在响，多数项目的新建、状态变化并不值得打断人，
-- 通知一多就整体被无视，真正要验收的那条也跟着被划过去。开关落在项目上，粒度和「我关心哪个项目」一致。
ALTER TABLE project ADD COLUMN notify_enabled TINYINT(1) NOT NULL DEFAULT 1;

-- 存量项目按需求方现状收敛：目前只有「Wa All in One」需要通知，其余先静音，随时可在设置里打开。
-- EXISTS 是保险：万一线上没有叫这个名字的项目，就一个都不静音（宁可继续吵，也不要一声不响，
-- 那种情况下用户会以为通知坏了）。子查询套一层派生表是 MySQL 「不能边查边改同一张表」的标准绕法。
UPDATE project
SET notify_enabled = 0
WHERE name <> 'Wa All in One'
  AND EXISTS (SELECT 1 FROM (SELECT name FROM project WHERE name = 'Wa All in One') AS keep);
