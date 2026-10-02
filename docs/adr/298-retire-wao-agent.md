# ADR-298：下线 WAO Agent

- 状态：已接受
- 日期：2026-10-02

用户要求下线 WAO Agent（导航名称为自媒体 Agent）。默认与历史注册表、个人布局、手机导航和持久页签均过滤 media-agent / mediaAgent；旧 /media-agent 与 /apps/media-agent 不再嵌入工作台。移除旧单菜单配置 API。

公开 /agent、/agent/**、/api/agent 与 /api/agent/** 统一返回 410。网关不再转发 Agent，Compose 不再启动 Agent 或把它作为网关的依赖；常规部署成功后通过项目与服务双标签停用已有容器并禁用重启，保留媒体卷和数据库。主站发布不再构建、打包或复制 Agent 制品，也不再要求它的健康探针成功。

历史 agent-web / agent-server 源码和已执行 migration 保留。自动化不属于本项下线范围；用户追加要求下线项目 AI Run、Runner 和 WeKnora，分别在后续独立变更中交付。

CLI / 工作区 MCP 没有此独立应用的专属命令或工具，不新增命令、不扩权。验收覆盖旧注册表、导航、旧页签、静态及 API 路由、部署脚本和保留应用。
