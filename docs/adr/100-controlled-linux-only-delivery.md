# ADR-100：受控的 Linux 单节点发布

## 状态

已采纳。

## 背景

Windows 从节点可能因关机、家庭网络或 OpenSSH 端口转发暂时不可达。完全阻断发布会让已经通过验证的主站功能
长期无法上线，但自动忽略 Windows 故障又会掩盖真实部署失败。

## 决策

- Jenkins 默认继续执行 ADR-097 的严格双节点发布。
- 只有成员手工勾选 `ALLOW_WINDOWS_OFFLINE`，且 Windows 在部署前的 SSH、WSL、Docker 预检阶段不可达时，
  才允许保持 slave upstream 为 `down`，继续发布 Linux 主节点和云前端。
- Windows 一旦通过预检，后续复制、安装、健康检查或 Gateway 验证失败仍然终止整次发布，不降级为单节点成功。
- 该开关不得与 `BOOTSTRAP_WINDOWS` 同时启用。若 V77 全节点安全迁移窗口尚未完成，流水线必须暂停飞书同步、
  停止 Linux 旧服务后再执行 migration；Linux 成功后不写 V77 完成标记，也不恢复同步，等待 Windows 补齐。
- 单节点发布不宣称 Windows 已升级；从节点恢复后必须通过后续正常发布补齐到与 `main` 相同的镜像 SHA。

## 后果

明确授权时可以恢复 Linux 主站交付，同时云路由持续隔离旧 Windows 节点。代价是 Linux 更新期间无法由另一应用节点
承接流量，可能出现短暂维护窗口；Jenkins 状态和飞书通知必须把 Windows 标记为 `skipped`，不能伪报双节点成功。
V77 未完成期间飞书同步会继续暂停，直到 Windows 恢复并由后续正常发布完成全节点迁移。
