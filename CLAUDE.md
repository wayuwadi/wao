# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# Claude Code 项目指令

本文件继承 `AGENTS.md` 中的全部规范（技术栈基线、代码规范、目录结构、禁止事项），此处只补充命令、架构大图与 Claude 特定约定，不重复 `AGENTS.md`。

## 工作前必读

- 修改代码前阅读 `docs/architecture.md` 和相关 Spec/ADR（`docs/specs/`、`docs/adr/`）。方案原稿 `docs/WAO系统技术架构方案.md` 与实现冲突时，以已采纳 ADR 和 `docs/architecture.md` 为准。
- 采用 Spec-Driven Development：实现业务代码前，规格须先落到对应目录并通过 PR 评审（业务架构 `docs/business/`、ER/字段 `docs/database/`、OpenAPI `docs/api/`、页面 `docs/pages/`、验收场景 `docs/specs/`、技术决策 `docs/adr/`）。
- 后端接口变化时同步更新 `docs/api/` 下的 OpenAPI 文件；新增页面时同步更新 `wao-web/src/router/index.ts`。

## 常用命令

后端（在仓库根目录，Maven 多模块）：

```bash
mvn test                                    # 全部后端测试（CI 基线）
mvn -pl wao-server test                     # 只测应用服务模块
mvn -pl wao-server test -Dtest=HelloServiceTest       # 单个测试类
mvn -pl wao-server test -Dtest=HelloServiceTest#hello # 单个测试方法
mvn -pl wao-server spring-boot:run -Dspring-boot.run.profiles=local   # 起 App（:8080）
mvn -pl wao-gateway spring-boot:run -Dspring-boot.run.profiles=local  # 起 Gateway（:9000）
```

前端（在 `wao-web/`）：

```bash
npm run dev                       # Vite 开发服务器（:5173，/api 代理到 :9000）
npm run test:unit -- --run        # 单元测试跑一次（Vitest）
npm run test:unit -- --run App    # 按文件名过滤单个测试
npm run lint                      # oxlint + eslint（均带 --fix）
npm run build                     # type-check + 生产构建
```

后端改动后跑 `mvn test`；前端改动后跑 `npm run lint`、`npm run test:unit -- --run`、`npm run build`。涉及数据库时新增 Flyway migration（`wao-server/src/main/resources/db/migration/`），禁止修改已执行的 migration。

## Profile 说明

三个模块共用 `local` / `dev` / `prod` 三套 Profile：

- `local`：不依赖 Docker、MySQL、Redis、Nacos，用于五分钟起本地纵向链路 Demo。
- `dev`：连本机 Docker 基础设施（`docker compose -f docker-compose-dev.yml up -d`），Swagger 开启，Nacos 可关鉴权。
- `prod`：敏感值全部来自环境变量（节点私有 `deploy/.env` / GitHub Secrets），Swagger 关闭，跨节点走公网/DDNS。

## 架构大图

三个可部署单元 + 云 Nginx：

- `wao-web/`：Vue 3 + Vite + TS + Ant Design Vue + Pinia + Vue Router。所有 HTTP 调用封装在 `wao-web/src/api/`（`http.ts` 是 axios 实例，响应拦截器统一解包 `ApiResponse` 并对 `code != 0` 抛错），组件内不直接发请求。
- `wao-server/`（Spring Boot，:8080）：业务应用。统一返回 `ApiResponse<T>`（`code == 0` 为成功），异常由 `common/GlobalExceptionHandler` 收敛；API 路径前缀 `/api/`，包名 `cn.wayuwa.wao.*`。
- `wao-gateway/`（Spring Cloud Gateway，:9000）：入口网关。`RequestIdFilter`（order -200）为每个请求注入 / 回写 `X-Request-Id`。

请求链路：`浏览器 → 云 Nginx（静态托管 + HTTPS）→ Gateway → 同机 wao-server`。关键约定：**每个 Gateway 只路由到同机 `wao-server:8080`**（见 ADR-006），节点级故障转移由云 Nginx 完成；两个 App 实例仍注册到 Nacos，仅用于注册状态与后续服务治理，不用于 Gateway 选址。

生产为三节点：云服务器（Nginx/TLS/前端）、Linux 主节点、Windows 从节点（容器跑在 WSL2 Ubuntu 的 Docker Engine 中）。部署编排见 `deploy/`（`docker-compose-master.yml`、`docker-compose-slave.yml`、`scripts/`），运维细节见 `deploy/README.md` 与 `docs/runbooks/phase-0-rollout.md`。

## Claude 特定约定

- Service 公共方法必须提供 JavaDoc，核心逻辑必须有单元测试。
- 提交遵循 Conventional Commits；禁止直接推送 `main`，所有变更走 PR。
- 同一业务模块或部署文件同一时间只由一个分支修改。
- 不在代码、文档或 Git 历史中写入真实密码、Token、私钥、DNS 凭据或部署密钥；仓库只保留 `.env.example`。
