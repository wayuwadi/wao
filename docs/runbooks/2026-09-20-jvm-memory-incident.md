# 2026-09-20 业务超时与 JVM 容器内存越界

## 已确认的故障

Linux2 运行 `wao-server:2e4b61c`，容器内存限制 2 GiB，镜像入口使用
`-XX:MaxRAMPercentage=75.0`。在同一容器内执行相同参数的 JVM 检查，最大堆显示
22.78 GiB；cgroup `memory.max` 为 2147483648，Java 系统指标也能读到 2 GiB。
因此不能仅凭 cgroup 指标正确就认定 JVM 堆预算正确，也不能把此现象概括为完全不支持容器。

内核记录该容器的 Java 在 02:25、02:49、09:54、10:22 四次被 cgroup OOM killer
终止。Docker 重启后的 `State.OOMKilled=false` 和 `healthy` 不能否定历史 OOM。
最近三小时客户端指标中，任务接口有 17 次达到 10 秒超时，文档接口有 4 次达到
60 秒超时。OOM 能解释服务中断；文档列表传输本身的慢请求仍需独立验证。

## 修复

- 2 GiB 应用容器：显式 `-Xms256m -Xmx1024m`。
- 1 GiB Gateway / Agent 容器：显式 `-Xms128m -Xmx512m`。
- 去掉依赖宿主内存比例计算的入口参数；通过镜像 `JAVA_TOOL_OPTIONS` 设置默认值。
- 节点发布校验除镜像 SHA 和健康状态外，还校验有效 JVM 最大堆值，防止错误配置重新上线。

应急处理先验证 Linux1 的版本和健康，临时接管流量，再用同版本应用镜像与最小
Compose override 重建 Linux2 应用容器。重建前比较配置，确认除 JVM 参数外现有环境
不变。验证 1 GiB 最大堆、健康和真实任务分享读取后恢复原有路由状态。
应急 override 位于 Linux2 `/run/wao-memory-hotfix.yml`，只含内存参数，不含凭据；
正式发布使用镜像默认值，部署成功后该临时文件不再需要。

## 验证与回滚

验证 `java -XX:+PrintFlagsFinal -version` 输出的 `MaxHeapSize`、容器实际内存、重启次数、
新内核 OOM 记录及真实业务请求耗时。基础 `/api/hello` 成功不能替代业务请求验证。
JVM 参数修改不涉及业务数据或数据库迁移。
若新镜像需回滚代码，应继续保留显式堆限制，不能重新启用已证实越界的百分比配置。
