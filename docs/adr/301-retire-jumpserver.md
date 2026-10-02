# ADR-301：下线 JumpServer 资源管理

- 状态：已接受
- 日期：2026-10-02

用户要求下线资源管理的 JumpServer。前后端注册表、旧个人布局、手机导航及历史页签过滤 jumpserver；/apps/jumpserver 不再嵌入应用，/api/auth/jumpserver 不再签发 SSO 票据。公共主站的 SSO 与健康探针返回 410，jms 域名保持 TLS 后返回 410，不继续代理终端。

主站部署不再初始化 SSO 密钥、安装资产授权、部署 JumpServer 或等待其健康与证书验收。部署助手执行精确退役脚本：关闭 wao-jumpserver-tunnel 服务，仅对 Compose 工作目录属于 /opt/wao-jumpserver/installer-v4.10.19 的容器禁用重启并停止。容器、数据库、媒体与审计目录、密钥和历史源码保留；不删除 SSH 用户、公钥或其他主机资产。

资源管理没有工作区 MCP / CLI 专属命令，本次不扩权。验收包括旧注册表读写、旧入口、SSO 专属 API、配置渲染和退役脚本的目标隔离。
