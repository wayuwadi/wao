# WAO

WAO 是一个用于工作和生活的浏览器系统，把任务、项目、小记、知识库、文件、日历和 AI 协作放在同一处。当前只维护浏览器产品。

唯一主仓库：[wayuwadi/wao](https://github.com/wayuwadi/wao)。维护者与社区贡献者在这里修改同一份源码，生产部署使用独立私有配置。

## 在本地运行独立系统

需要 JDK 17+、Maven 3.9+、Node.js 24.12+、Python 3.10+ 和 Docker Compose。以下命令创建独立数据库和随机密钥，不导入维护者的生产数据。

```bash
git clone https://github.com/wayuwadi/wao.git
cd wao
python scripts/local-dev.py init
python scripts/local-dev.py services
mvn -pl wao-server,wao-gateway -am install -DskipTests
cd wao-web
npm ci
cd ..
cd wao-game
npm ci
npm run build:dino
npm run build:rift
npm run build:starfield
npm run build:beasts
npm run build:camp
cd ..
```

分别在三个终端运行：

```bash
python scripts/local-dev.py server
python scripts/local-dev.py gateway
python scripts/local-dev.py web
```

打开终端显示的 Vite 本机地址，通常为 `http://127.0.0.1:5173`。首次使用通过注册入口创建自己的账号。依赖和开发服务只监听本机，不要把此开发配置直接用于公网。

`.env` 保存你的私有配置，脚本不会覆盖已有文件。妥善备份其中的密码和 `WAO_VAULT_KEY`，不要提交到 Git。飞书、AI、日历及其他外部服务需要配置自己的账号与授权。

没有 Docker 时，参见 [开发指南](docs/development.md)，连接自己已有的 MySQL 9 和 Redis。仅查看页面的 `local` 演示模式不能代替持久化系统。

## 源码结构

| 目录 | 用途 |
| --- | --- |
| `wao-web` | Vue 3 浏览器界面 |
| `wao-server` | Spring Boot 业务服务、MySQL 与 Flyway |
| `wao-gateway` | API 网关 |
| `wao-game` | 浏览器放松与练习内容 |
| `wao-cli` | 工作区命令行工具 |
| `agent-server` / `agent-web` | 可选内容工作台 |
| `deploy` | 部署工具和兼容资源接口 |
| `docs` | 当前有效的说明与规范 |

历史 Windows、macOS、iOS、Android 客户端源码和版本标签保留用于追溯，当前不开发或发布新客户端。使用系统无需安装独立客户端。

## 开发与部署

从最新 `main` 创建分支，本地完成相称验证后提交 PR。公开 CI 不接触生产凭据；维护者 Jenkins 只从受信任 `main` 发布生产。

```bash
mvn test
cd wao-web
npm run test:unit -- --run
npm run type-check
npm run build
```

完整说明见 [贡献指南](CONTRIBUTING.md)、[当前架构](docs/architecture.md)、[部署与数据保护](docs/deployment.md) 和 [安全说明](SECURITY.md)。

## 版本和历史

原有 Git 提交、分支和版本标签经过脱敏后保留。历史 PR 提交归档为 `archive/pull/…` 标签，避免遗漏 Squash Merge 前的修改记录。作者姓名、提交时间和信息保留，私人邮箱改为匿名 noreply 地址；脱敏后 SHA 会变化。

当前主分支只保留最新文档。旧说明和退役方案可从 Git 历史查看，不作为当前生产操作步骤。已有生产通过受保护的兼容资源保持已执行 Flyway 校验和，不重置数据库或运行 `repair` 绕过校验。

## 许可证

WAO 自有源码采用 [MIT](LICENSE)。第三方依赖和资产遵循各自许可，见 [第三方说明](THIRD_PARTY_NOTICES.md)。
