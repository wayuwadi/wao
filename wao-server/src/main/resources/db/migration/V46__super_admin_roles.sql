-- 初始维护者拥有组织级权限；后续由超级管理员在账号管理中授权普通管理员。
UPDATE member
SET role = '超级管理员'
WHERE id IN ('wg', 'wd');
