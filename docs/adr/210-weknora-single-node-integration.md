# ADR-210：Linux2 单机部署 WeKnora，WAO 提供受保护的操作入口

## 状态
已采纳。用户明确指定 Linux2，模型全部通过 API 调用。

## 决策
- WeKnora 固定 v0.8.0，独立 Compose、PostgreSQL、Redis、文件卷及配置目录 `/opt/wao-weknora`，不接管 WAO 数据库。
- 后端与解析服务使用官方镜像；前端从相同 tag 构建到 `/weknora/`，仅通过可重复的兼容脚本处理硬跳转路径。不上线本地模型、技能 Docker 沙箱或全量可选服务。
- Linux2 端口仅绑定 loopback。通过既有可信 SSH 隧道到云 Nginx，`/weknora/`、WeKnora `/api/v1/`、`/files`、`/r/` 统一先校验 WAO Session；原有 WAO API 无 `/api/v1/` 路由。该前缀专供 WeKnora，不与 WAO 自身接口复用。
- WAO 应用注册表新增 WeKnora，默认在 WAO 页签打开；既有注册表按版本补一次，尊重之后的隐藏、排序和删除。
- WAO 外层准入与 WeKnora 内部账号、空间授权独立。页面明确提示首次需建立 WeKnora 账号和配置模型，不宣称已实现 SSO，也不复用 Codex 订阅作为通用 API。
- 配置和凭据只写 Linux2 受限文件；原始资料、数据库、配置及密钥一并纳入备份。升级由单独操作触发，WAO 日常发布不拉取 WeKnora 新版本。

## 验收
核心容器健康；未登录不能读取 UI、API 或文件；登录后从 WAO 菜单进入完整界面，路由、静态资源和刷新均在 `/weknora/` 内；模型缺失显示真实配置状态。
