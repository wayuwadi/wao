-- 密码库归属到人：过去 vault_group 没有归属列，任何登录用户都能看到全部分组和条目。
-- 加上 owner_member_id 后，列表 / 增删改查一律只在「当前成员自己的分组」范围内生效。
--
-- 存量数据没有任何创建人信息可还原，只能统一归到组织管理员（wg / 娃哥）；
-- 数据本身不删除，需要移交时由管理员改这一列即可（见 docs/adr/024-vault-per-member-ownership.md）。

ALTER TABLE vault_group
    ADD COLUMN owner_member_id VARCHAR(32) NULL COMMENT '归属成员 id；每个人只能看到自己的分组';

UPDATE vault_group SET owner_member_id = 'wg' WHERE owner_member_id IS NULL;

CREATE INDEX idx_vault_group_owner ON vault_group (owner_member_id, ord);
