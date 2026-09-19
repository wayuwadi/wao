# WAO Life OS

WAO 是一个由 AI 协作开发的前后端分离系统，采用 Spec-Driven Development。当前已经完成 Phase 0 的工程骨架和 Hello World 纵向切片：浏览器请求经 Vue、Spring Cloud Gateway 到达 Spring Boot App，并返回统一 JSON。

## 当前状态

| 范围             | 状态   | 说明                                                             |
| ---------------- | ------ | ---------------------------------------------------------------- |
| 后端与网关       | 已完成 | Java 17、Spring Boot 3、Gateway、Nacos 2.x、统一响应和异常处理   |
| 前端脚手架       | 已完成 | Vue 3、Vite、TypeScript、Pinia、Vue Router 4、Ant Design Vue     |
| 本地 Demo        | 可运行 | 使用 `local` Profile，不依赖 Docker、MySQL、Redis 或 Nacos       |
| 生产部署代码     | 已完成 | 双节点 Compose、MySQL 主从、Redis Sentinel、云 Nginx、TLS、CI/CD |
| 三节点实机上线   | 已完成 | 云前端/HTTPS、Linux2 主节点与 Linux1 从节点组成主从环境          |
| Phase 1 业务功能 | 待规格 | 需要设计稿、ER 模型和业务 API 契约                               |

## 技术栈

- Web：Vue 3、Vite、TypeScript、Ant Design Vue、Pinia、Vue Router 4
- Server：Java 17、Spring Boot 3、MyBatis-Plus、Flyway
- Gateway：Spring Cloud Gateway、Nacos 2.4.3
- Data：MySQL 9.7、Redis 7 Sentinel
- Delivery：Docker Compose、Nginx、Linux 自建 Jenkins、公网 DDNS

## 五分钟启动 Demo（无需 Docker）

要求：JDK 17+、Maven 3.9+、Node.js 24+。在仓库根目录分别打开三个终端。

终端 1，启动 App：

```powershell
mvn -pl wao-server spring-boot:run "-Dspring-boot.run.profiles=local"
```

终端 2，启动 Gateway：

```powershell
mvn -pl wao-gateway spring-boot:run "-Dspring-boot.run.profiles=local"
```

终端 3，启动 Web：

```powershell
Set-Location wao-web
npm ci
npm run dev
```

访问 [http://localhost:5173](http://localhost:5173)，点击“调用后端”。页面应显示 `Hello from WAO`，实际链路为：

```text
Browser -> Vite :5173 -> Gateway :9000 -> WAO Server :8080
```

也可以直接检查接口：

```powershell
Invoke-RestMethod http://localhost:9000/api/hello
```

当前 Demo 是 Phase 0 的系统状态页，用于验证真实前后端链路；它不是尚未导入的 `WAO Life OS v3` 设计稿成品。

## 双 Linux 生产节点

生产环境使用两台原生 Linux：`linux2.wayuwa.cn` 作为主节点和 Jenkins 控制器，`linux1.wayuwa.cn` 作为从节点、开发机与 AI Runner。两台应用节点运行同一 Git SHA 的 Linux 容器；MySQL 使用 GTID 主从复制，Redis 使用主从复制和双 Sentinel。旧 Windows1 已重装为 Linux1，不再使用 WSL2、PowerShell 发布或 RDP 运维链路。

## 完整开发环境

需要验证 MySQL、Redis、Nacos 和服务注册发现时，安装 Docker Compose 后执行：

```bash
docker compose -f docker-compose-dev.yml up -d
mvn -pl wao-server spring-boot:run -Dspring-boot.run.profiles=dev
mvn -pl wao-gateway spring-boot:run -Dspring-boot.run.profiles=dev
cd wao-web && npm run dev
```

### 本机开发，连接服务器 MySQL

[工程资产总入口](docs/runbooks/engineering-assets.md) 汇总开发体检、只读性能基准、一致性检查和草稿回归的使用时机、证据与接续方式。

开发前可执行 `python3 deploy/scripts/dev-doctor.py --env-file .env.local-cloud --web-url http://127.0.0.1:5174`
只读检查运行时、依赖和本地连接；失败时输出下一步处理建议。参数、输出含义和独立工作区用法见
[本地开发体检](docs/runbooks/local-development-doctor.md)。体检不安装或启动服务，也不替代真实用户链路验收。

需要记录首页聚合或文档读取的查询次数与耗时时，使用 [只读性能基准](docs/runbooks/read-only-performance-baseline.md)，可生成带版本和采样条件的报告并比较两次结果。

提交前可运行 [工程一致性检查](docs/runbooks/engineering-consistency-check.md)，核对 ADR / migration 编号、已交付 migration 不可变性和本次文档的本地链接。

修改文档编辑与草稿逻辑时，使用 [草稿防丢回归包](docs/runbooks/document-draft-regression.md)，覆盖保存失败、刷新恢复、两窗口冲突和本轮验收文档清理。

前端和 Java 服务在本机运行时，后端使用 `local-cloud` Profile，网关使用 `local` Profile。
通过运行进程的环境变量提供 `MYSQL_HOST`、`MYSQL_PORT`、`DB_NAME`、`DB_USER`、`DB_PASS`；
优先通过 SSH 本地端口转发访问服务器数据库。凭据不要写入仓库、命令历史或日志。

```bash
mvn -pl wao-server spring-boot:run -Dspring-boot.run.profiles=local-cloud
mvn -pl wao-gateway spring-boot:run -Dspring-boot.run.profiles=local
cd wao-web && npm run dev -- --host 127.0.0.1 --port 5174 --strictPort
```

打开 `http://127.0.0.1:5174` 完成编辑、保存、刷新及失败恢复验收，再提交 PR 和发布。
该配置关闭 Flyway、Nacos 注册、Redis 自动配置和全部 `@Scheduled` 任务；独立的本地会话 Cookie
仍通过数据库持久化。文档、文件等业务数据直接落到指定服务器数据库，请使用专门的验收内容。
依赖 Redis 的微信公众号登录和 Skill 限速不属于此配置的验收范围。生产节点的定时任务默认保持启用。

连接完整业务数据时，若 Vue DevTools 注入影响开发页面响应，可在启动前设置
`WAO_VUE_DEVTOOLS=false` 关闭调试面板，Vite 热更新仍保留；不设置时维持原有行为。

## 工程结构

```text
wao/
├── wao-web/       Vue 前端
├── wao-server/    Spring Boot 应用服务
├── wao-gateway/   Spring Cloud Gateway
├── agent-web/     同仓交付的自媒体 Agent 前端
├── agent-server/  同仓交付、独立进程的 Agent 服务
├── deploy/        Compose、Nginx、证书、备份和部署脚本
├── docs/          原始方案、架构基线、ADR、API 与页面规格
└── .github/       CI/CD 工作流
```

## 文档入口

- [WAO Design System / UX 规范](docs/ux/wao-design-system.md)：当前视觉变量、组件复用前提、页面状态和用户旅程验收基线。

- [原版技术方案](docs/WAO系统技术架构方案.md)：用户提供的原始方案，原样归档，作为项目愿景和实施范围来源。
- [当前架构基线](docs/architecture.md)：结合兼容性与实机环境验证后的落地架构。
- [多合一交付决策](docs/adr/049-unified-product-monorepo-delivery.md)：Agent、游戏与 Insight 的统一边界和迁移取舍。
- [自媒体 Agent 技术架构](docs/architecture/media-automation-agent.md)：同仓交付、共享 Session、独立运行进程与平台安全边界。
- [Phase 0 规格](docs/specs/phase-0.md)：当前纵向切片的验收标准。
- [Hello API 契约](docs/api/hello.yaml)：OpenAPI 3.0 定义。
- [Hello 页面规格](docs/pages/hello.md)：页面行为与状态说明。
- [实机上线清单](docs/runbooks/phase-0-rollout.md)：三节点当前状态、复现步骤与验收项。
- [部署手册](deploy/README.md)：环境变量、证书、DDNS、备份和 CI/CD。
- [公网直连决策](docs/adr/005-public-ddns-network.md)：不使用 Tailscale 后的网络与风险边界。
- [贡献指南](CONTRIBUTING.md)：小伙伴拉取代码、提交业务架构/ER 图及发起 PR 的规则。
- [业务架构资料入口](docs/business/README.md)：Phase 1 业务架构图的落位要求。

原版方案与实际实现不一致时，以 `docs/adr/` 中已采纳的 ADR 和当前架构基线为准；原版方案除已确认的主机名勘误外保持归档形态，确保决策过程可追溯。

## 团队协作

```bash
git clone https://github.com/wayuwacn/wao.git
cd wao
git switch main
```

小伙伴先从 `main` 创建 `docs/business-architecture` 分支，把业务架构图放入 `docs/business/`、ER 图和字段字典放入 `docs/database/`，再发起 Pull Request。日常开发禁止直接推送 `main`。

## 质量验证

```bash
mvn test
cd wao-web
npm run lint
npm run test:unit -- --run
npm run build
```

当前基线：后端 4 个测试通过，前端单元测试、类型检查、Lint 和生产构建通过。

## 安全约束

仓库只保存 `.env.example`。真实密码、Token、私钥、DNS 凭据和部署密钥必须保存在各节点私有 `deploy/.env`、系统凭据存储或 GitHub Secrets 中，禁止提交到 Git。
