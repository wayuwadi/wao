# WAO Life OS

WAO 是一个由 AI 协作开发的前后端分离系统，采用 Spec-Driven Development。当前已经完成 Phase 0 的工程骨架和 Hello World 纵向切片：浏览器请求经 Vue、Spring Cloud Gateway 到达 Spring Boot App，并返回统一 JSON。

## 当前状态

| 范围             | 状态   | 说明                                                             |
| ---------------- | ------ | ---------------------------------------------------------------- |
| 后端与网关       | 已完成 | Java 17、Spring Boot 3、Gateway、Nacos 2.x、统一响应和异常处理   |
| 前端脚手架       | 已完成 | Vue 3、Vite、TypeScript、Pinia、Vue Router 4、Ant Design Vue     |
| 本地 Demo        | 可运行 | 使用 `local` Profile，不依赖 Docker、MySQL、Redis 或 Nacos       |
| 生产部署代码     | 已完成 | 双节点 Compose、MySQL 主从、Redis Sentinel、云 Nginx、TLS、CI/CD |
| 三节点实机上线   | 已完成 | 云前端/HTTPS、Linux 主节点与 Windows 从节点均已通过公网验收       |
| Phase 1 业务功能 | 待规格 | 需要设计稿、ER 模型和业务 API 契约                               |

## 技术栈

- Web：Vue 3、Vite、TypeScript、Ant Design Vue、Pinia、Vue Router 4
- Server：Java 17、Spring Boot 3、MyBatis-Plus、Flyway
- Gateway：Spring Cloud Gateway、Nacos 2.4.3
- Data：MySQL 9.7、Redis 7 Sentinel
- Delivery：Docker Compose、Nginx、GitHub Actions、公网 DDNS

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

## 为什么 Windows 生产节点使用 WSL2 Ubuntu

MySQL、Redis、Nacos 和项目 Dockerfile 当前均使用 Linux 容器镜像，需要 Linux 内核提供容器运行能力。Docker Desktop 不支持 Windows Server，因此落地方案是在 Windows Server 的 WSL2 中安装轻量的 Ubuntu 24.04 和 Docker Engine。

这不会把电脑改成 Ubuntu，也不会影响 Windows 日常使用：代码仍在 `D:\CodeSpace\wao`，部署目录仍在 `D:\deploy`，Ubuntu 只作为无桌面的后台容器运行层。实机将 WSL 限制为 4 核、8GB 内存和 2GB swap；当前完整副本运行约占 3GB 内存，动态 VHD 约 4GB。停止计划任务并执行 `wsl --shutdown` 后，运行内存会释放。

## 完整开发环境

需要验证 MySQL、Redis、Nacos 和服务注册发现时，安装 Docker Compose 后执行：

```bash
docker compose -f docker-compose-dev.yml up -d
mvn -pl wao-server spring-boot:run -Dspring-boot.run.profiles=dev
mvn -pl wao-gateway spring-boot:run -Dspring-boot.run.profiles=dev
cd wao-web && npm run dev
```

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
