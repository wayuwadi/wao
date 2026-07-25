# WAO 项目 - AI 开发上下文

## 项目概述

WAO 是一个前后端分离的 Web 系统，采用 Spec-Driven Development。变更代码前先阅读相关规格和 ADR；接口、数据结构或部署行为变化时同步更新文档。

## 技术栈（未经 ADR 不可更改）

- 前端：Vue 3、Vite、TypeScript、Ant Design Vue、Pinia、Vue Router 4
- 后端：Java 17、Spring Boot 3、MyBatis-Plus、Flyway
- 网关：Spring Cloud Gateway、Nacos 2.x
- 数据：MySQL 9、Redis 7
- 部署：Docker Compose、Nginx、GitHub Actions；Windows Server 从节点在 WSL2 Ubuntu 中运行 Docker Engine

当前兼容性基线：Spring Boot 3.2.4、Spring Cloud 2023.0.1、Spring Cloud Alibaba 2023.0.1.0、Nacos 2.4.3。

## 代码规范

- Java 包名：`cn.wayuwa.wao.*`
- API 路径前缀：`/api/`
- 后端响应统一使用 `ApiResponse<T>`
- Service 公共方法必须有 JavaDoc，核心逻辑必须有单元测试
- Vue 组件使用 `<script setup lang="ts">`，组件命名使用 PascalCase
- API 调用统一放在 `wao-web/src/api/`，组件内不直接发起 HTTP 请求
- 提交信息遵循 Conventional Commits，但除 `type(scope):` 前缀、代码标识、路径和命令外必须使用中文。正文固定包含“改动：”“原因：”“验收：”。CI（`.github/workflows/ci-cd.yml` 的 `notify` job）会把完整提交信息转发到团队飞书通知卡片
- 完成与变更相称的本地验证后，AI 应直接创建详细提交；提交正文必须说明改动、原因和验收结果。开发测试阶段允许直接推送 `main` 以触发唯一一次部署；不得提交任何私有环境文件或凭据。

## 快速交付策略（开发测试阶段，临时）

当前目标是快速获得真实环境反馈。在第一个正式版本上线前，默认流程如下（决策依据见 `docs/adr/011-development-fast-delivery.md`）：

1. 变更前先执行 `git switch main` 和 `git pull --ff-only origin main`，多人并行时避免基于旧代码工作。
2. 完成与改动相称的本地测试后，AI 直接在 `main` 创建详细提交并 push；无需功能分支、PR、云端重复 CI 或人工 Review。
3. `main` 的 push 只触发一次 CD：构建一次前端，并行部署主节点、从节点和云前端，线上健康检查通过后发送一次飞书通知。
4. 大型重构、同一文件多人并行或需要隔离试验时仍可使用功能分支，但 PR 是协作工具而不是强制门禁；合并前以本地验证为准。
5. 本策略只放宽流程门禁，不放宽凭据和数据安全：真实 Secret 不得进入 Git；破坏性生产数据操作仍需明确授权；已执行的 migration 仍不可修改。

第一个正式版本发布前必须撤销本节的临时授权，恢复受保护分支、PR CI、CODEOWNERS Review 和发布审批。

## 目录结构

- `wao-web/`：Vue 前端
- `wao-server/`：Spring Boot 应用服务
- `wao-gateway/`：Spring Cloud Gateway
- `deploy/`：Compose、Nginx 和运维脚本
- `docs/`：规格、API 契约、ADR 与数据库文档

## 当前进度

- Phase 0 代码与本地纵向链路已完成；三节点实机上线正在执行，清单见 `docs/runbooks/phase-0-rollout.md`。
- Phase 1 规格准备中：业务架构放 `docs/business/`，ER 图和字段字典放 `docs/database/`，通过 PR 评审后开始实现。

## 禁止事项

- 不在代码、文档或 Git 历史中写入密码、Token、私钥和真实部署凭据
- 正式版本上线后不直接推送 `main`；开发测试阶段按“快速交付策略”执行
- 不覆盖已有规格；需求变化通过新规格或 ADR 记录
- 不修改已执行的 Flyway migration，数据库变更必须新增 migration
