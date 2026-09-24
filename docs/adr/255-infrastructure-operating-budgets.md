# ADR-255：基础设施运行预算与独立就绪告警

状态：采用。续接 ADR-211、三节点实施计划阶段 G。

## 依据与范围

2026-09-21 Linux1 可用内存约24.6GiB、磁盘75GiB；Linux2 可用约15.2GiB、磁盘507GiB。WAO App各2GiB、Agent/Gateway各1GiB、Redis各2GiB、Sentinel/公开入口各128MiB已生效。Linux1 MySQL6GiB。当前不是内存耗尽，但 Jenkins 控制器和三个并行测试容器没有上限，不能以单个执行器代替资源隔离。

## 决策

- Jenkins控制器观测当前约1.15GiB、历史峰值约1.67GiB；设置MemoryHigh=3GiB、MemoryMax=4GiB、两核及较低CPU/IO优先级。在线应用不进入该cgroup。
- 后端及两个前端测试容器各4GiB、两核、512进程，不增加swap额度；前端测试最多两个worker。三个阶段并行上限12GiB，加控制器4GiB。镜像打包顺序执行，Maven堆限制2GiB并限制JVM可见处理器为2；不宣称这等于BuildKit整个进程树的硬隔离。
- 现有WeKnora各服务已有独立限制。未来Linux Runner模板设6GiB硬限、4GiB软限、两核及低IO优先级；未通过本模板启动的既有任意进程不宣称受其控制。
- 云机新增64MiB、0.25CPU的一分钟就绪检查，固定探测主域与两个公开节点。使用已有/api/edge/ready合同，验证应用能连接可写权威数据库，不以hello代替。不把Nacos单点当作所有业务必须不可用。
- 连续两次失败才通过已有飞书机器人告警；相同失败四小时去重，恢复后通知一次。凭据仅在云机0600EnvironmentFile；TLS正常校验，禁止重定向，不持有用户会话。通知失败不记成功发送，并下次重试。
- 此探针是能力观测，不控制选主、摘流或进程重启；复制/备份新鲜度继续由独立Linux1备份检查负责。云机本身故障仍需其他节点/用户观测，不能声称不存在监控单点。

云端历史/etc/hosts把Linux1主机名指向反向代理loopback，不能直接用系统解析声称公网9443探测。独立探针通过既有dig向223.5.5.5查询公开A记录，只接受公网地址，curl --resolve保留原域名和完整TLS校验；不修改现有hosts，不使用-k，不跟随重定向。DNS异常计探针失败，不偷偷替换成私网成功。

Linux2私网MySQL通道只允许既有WAO Docker网段172.22.0.0/16访问10.66.0.2:13306；UFW持久规则已配置。宿主机连接成功不能代替容器网络验收，必须执行真实发布维护查询。第一次遗漏规则导致Jenkins #456在重建应用之前终止，补齐后rollout-status=complete。

## 运维

将jenkins-resource-budget.conf安装为jenkins.service.d/wao-resources.conf。先核对实际占用低于软限，可使用systemctl set-property在线施加CPU/Memory/Tasks限制，无需重启当前构建；Nice/IO配置在后续正常服务启动生效。

云端安装health-watch.py和wao-health-watch.service/timer，私有通知配置放/etc/wao-health-watch/notification.env，执行--check-only确认三入口。启用timer后查看/var/lib/wao-health-watch/state.json与journalctl。单元测试用伪通知接收方验证去重和失败重试，不向真实群发送故障演习垃圾消息。

回退删除对应drop-in并撤销在线属性限制；停用云端timer不会影响业务路径。高内存任务应降低并发或停止，不通过取消限制挤占生产。两机20%磁盘与12GiB恢复工作空间继续由备份前检查保护。

## 保留边界

Linux2其他既有JumpServer/Dify及本机数据库不是这次随意重启或重建对象，仍需各自容量治理。MySQL按应用独立账号/库、Redis ACL/前缀、Nacos命名空间是新应用接入门槛；不把WAO共享凭据分发给未来应用。生产整机掉电、网络分区不在无窗口情况下测试。
