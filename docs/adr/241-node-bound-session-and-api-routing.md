# ADR-241：绑定主站权威会话的节点 API 直连

## 状态

已实现并完成本地连接真实数据库的受控读取验收；生产 API 开关默认关闭。静态分流保持 ADR-240。

## 决策

浏览器继续以 `https://wao.wayuwa.cn` 为页面与登录入口。两台 Linux 的 HTTPS 9443
提供白名单业务 API。云端只承担登录、配置、小流量票据与故障回退，不改变 Agent、OAuth、Runner
和匿名文件下载契约。首批包含任务、项目、领域、成员、组织、知识库、小记与标签；图片、文件、SSE
另行验收，不因已有通用 API 前缀自动开启。

主站 Spring Session JDBC 仍是唯一登录权威。主站验证 Origin 和 CSRF 后签发随机 256 位、
60 秒、绑定账号/主站会话/精确节点 origin 的票据。票据只通过 POST 请求体传递，数据库仅存 SHA-256，
条件更新保证跨实例最多兑换一次。票据不进入 URL、日志、localStorage 或 IndexedDB。

节点兑换后轮换自己的 host-only、Secure、HttpOnly、SameSite=Lax Cookie。节点会话保存独立的
`wao.edgeAccountId`，不写 `wao.accountId`，避免不认识节点绑定的旧服务（尤其 Agent）将它当作
完整主站会话接受。每次认证检查目标节点、精确主站 Origin 和 MySQL 中主站会话的有效性；写请求另验
CSRF。主站注销后所有派生节点会话在下一次请求失效；通过节点注销时同样撤销主站权威会话。

主站 CSRF 令牌保存在服务端会话，浏览器只在内存保存。节点 Cookie 无 Domain 属性。现有 Agent
继续通过主站自己的 Cookie 访问 `/api/agent/**`，未接入节点会话。

公共 Nginx 同时限制 Host、Origin、路径和方法，精确 credentialed CORS，不反射任意来源。
`/api/edge/ready` 检查所连接数据库的 `read_only` 与 `super_read_only`，只读或不可达返回503，
避免可读副本被当作权威会话库；该探针按来源限制为5次/秒、突发10次，业务请求不套用探针限额。
它覆盖 Forwarded 系列头，只转发本机网关，不重试上游写请求。`WAO_EDGE_API_ENABLED=false`
和 `WAO_EDGE_SESSION_ENABLED=false` 为默认值，浏览器还必须通过配置的 API 开关与发布 SHA 白名单。

浏览器在后台建立节点连接，冷启动的业务请求仍可走原云入口。确认节点 Cookie 可用后再直连。
首次只启用读取；`apiWritesEnabled` 未显式设为 true 时，保存继续走主站。写直连需额外完成
真实持久化与断网结果未知场景验收，不能把读取验收冒充写入验收。
配置最长缓存 30 秒；单节点故障冷却 60 秒；主站认证变更丢弃内存绑定。读请求遇网络错误或
502/503/504 时只回云端一次，不叠加旧网关的三次重试。取消和 401/403 不重放。写请求断连时提示
保存结果未知，交由用户检查后重试，不自动创建第二次业务操作，不刷新页面或清除草稿。

## 回退与边界

将公共 routing.json 的 `apiEnabled` 设为 false（或 forceCloud=true）并原子替换；30 秒内停止新增
节点业务请求。已发送的写请求不因回退重放。保留服务端认证直到在途请求结束；随后可关闭节点 API
入口与服务端开关。新增 V157 票据表为加法迁移，回退代码时保留，不删除历史会话或业务表。

该设计没有消除 MySQL 单写主库依赖。数据库切主必须按单写、隔离旧主与恢复流程执行。
验收证据见 [节点会话验收](../validation/node-session-bridge-2026-09-20.md)。
