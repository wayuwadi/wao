-- 默认分组（「未分组」）改为按标记识别，不再按名字识别。
--
-- 旧实现里 ensureDefaultGroup 用「名字 == 未分组」找默认分组，找不到就插入固定 id
-- `vg-default-<memberId>`。成员一旦把自己的「未分组」改成别的名字，这条查询就永远落空，
-- 每次 GET /api/vault 都会拿同一个主键重复插入 → DuplicateKeyException → 409，
-- 前端整个密码库读不出来、退回演示数据（见 ADR-036）。
--
-- 存量默认分组只能靠 id 还原：历史上默认分组的 id 只有全局的 `vg-default`（V23 之前）
-- 和每人一个的 `vg-default-<memberId>`（ADR-024）两种写法。同一成员同时命中多行时只保留一行。

ALTER TABLE vault_group
    ADD COLUMN is_default TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否该成员的默认分组；改名后依然成立';

UPDATE vault_group g
    JOIN (SELECT owner_member_id, MIN(id) AS id
          FROM vault_group
          WHERE id = 'vg-default' OR id LIKE 'vg-default-%'
          GROUP BY owner_member_id) picked ON picked.id = g.id
SET g.is_default = 1;
