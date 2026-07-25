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
- 提交信息遵循 Conventional Commits
- 完成与变更相称的验证后，AI 应直接在当前功能分支创建详细提交；提交正文必须说明改动、原因和验收结果。不得推送 `main`，不得提交任何私有环境文件或凭据。

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
- 不直接推送 `main`
- 不覆盖已有规格；需求变化通过新规格或 ADR 记录
- 不修改已执行的 Flyway migration，数据库变更必须新增 migration
