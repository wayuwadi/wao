# Linux1 发布后再次摘流

> 历史故障记录：本文及其中的双节点发布测试已退役，仅供追溯；当前发布流程见 [部署说明](../../deploy/README.md) 与 [ADR-291](../adr/291-linux2-only-deployment-and-wao-recovery.md)。

2026-09-20 实机观察：两个应用节点已运行同一版本，Linux1 复制及健康正常，
Jenkins 日志先显示 `Gateway slave is up`，成功结束后云端又留下 `slave.down`。

`retry_node_deploy` 在子 shell 和 tee 管道内调用 `deploy_slave`，安装函数内的
`slave_deployed=true` 不会传播回主流程。主流程的 `restore_upstreams` 因此仍读取
初始 false，将刚验证的 Linux1 再次摘流。当前请求依赖 Linux2，双节点分流没有保持。

成功标记改在主流程设置，并且必须同时通过安装和云端回源探针。
安装失败、回源失败以及显式允许离线的节点在退出清理后继续保持 down。
保留实时日志、部署重试、主节点切换和既有失败收口策略。

## 验证

运行 `bash deploy/jenkins/test-slave-rollout-state.sh`。测试组合真实重试函数、
调用段和退出清理，替换外部安装及探针，不调用 SSH 或修改生产状态。覆盖成功、
安装三次失败、回源失败以及显式允许离线四种结果。

生产发布后需要同时检查：两节点镜像和业务健康、终态 upstream 无错误 down、
对应运行标记已清除。不能只在云端安装阶段看到一次 up 就判定成功。

本次不修改数据或新增 migration。代码回退会重新引入成功后摘流问题，
应保留该修正；故障节点仍可通过已有摘流脚本单独设置为 down。
