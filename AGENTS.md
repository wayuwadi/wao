# WAO 项目 - AI 开发上下文

## 项目概述

WAO 是一个前后端分离的 Web 系统，采用 Spec-Driven Development。变更代码前先阅读相关规格和 ADR；接口、数据结构或部署行为变化时同步更新文档。

## 技术栈（未经 ADR 不可更改）

- 前端：Vue 3、Vite、TypeScript、Ant Design Vue、Pinia、Vue Router 4
- 后端：Java 17、Spring Boot 3、MyBatis-Plus、Flyway
- 网关：Spring Cloud Gateway、Nacos 2.x
- 数据：MySQL 9、Redis 7
- 部署：Docker Compose、Nginx、Linux 自建 Jenkins；Windows Server 从节点在 WSL2 Ubuntu 中运行 Docker Engine

当前兼容性基线：Spring Boot 3.2.4、Spring Cloud 2023.0.1、Spring Cloud Alibaba 2023.0.1.0、Nacos 2.4.3。

## 代码规范

- Java 包名：`cn.wayuwa.wao.*`
- API 路径前缀：`/api/`
- 后端响应统一使用 `ApiResponse<T>`
- Service 公共方法必须有 JavaDoc，核心逻辑必须有单元测试
- Vue 组件使用 `<script setup lang="ts">`，组件命名使用 PascalCase
- API 调用统一放在 `wao-web/src/api/`，组件内不直接发起 HTTP 请求
- 提交信息遵循 Conventional Commits，但除 `type(scope):` 前缀、代码标识、路径和命令外必须使用中文。正文固定包含“改动：”“原因：”“验收：”。Jenkins 的 `notify-feishu.sh` 只把提交标题整理成精简改动清单，完整正文保留在 Git 历史中供链接查阅
- 完成与变更相称的本地验证后，AI 应直接创建详细提交；提交正文必须说明改动、原因和验收结果。开发测试阶段按「一个需求 / 一个 BUG 一个 PR」交付（见下节）；不得提交任何私有环境文件或凭据。

## 快速交付策略（开发测试阶段，临时）

当前目标是快速获得真实环境反馈。在第一个正式版本上线前，默认流程如下（决策依据见 `docs/adr/011-development-fast-delivery.md`，交付单元的调整见 `docs/adr/014-one-pr-per-change.md`）：

1. 变更前先执行 `git switch main` 和 `git pull --ff-only origin main`，多人并行时避免基于旧代码工作。
2. **一个需求或一个 BUG 一个 PR。** 完成与改动相称的本地测试后，AI 在功能分支上创建详细提交，push 分支、建 PR、合并 `main`，全程不必逐次征求用户同意。多条需求不合并进同一个 PR，一条需求也不拆成多个 PR。
3. PR **不承担重复 CI 门禁**：Jenkins 只在 `main` 的 push 上跑，PR 上没有 checks 是预期行为，不是失败。合并前以本地验证为准——本地测试全绿且 `mergeable` 为 `MERGEABLE` 即可 Squash Merge 并删除源分支。
4. `main` push 先进入 Jenkins 五分钟批处理窗口；窗口内多个需求提交合为一次 CD，已有发布运行时后续 push 也只保留一个待执行批次。每批构建最新 Git SHA 镜像，按 Linux1 从节点→Linux2 主节点→云前端/Nginx 严格滚动发布，批次内提交状态、双节点与线上健康检查通过后发送一次飞书通知（见 ADR-144、ADR-156）。
5. push 前必须核对与 `main` 的冲突，**包括 git 不会报的语义冲突**：两边各自新建了同编号的 ADR 或同版本号的 Flyway migration，文件名不同所以 git 静默通过，但语义上撞车，必须重新编号。migration 撞号会让 Flyway 启动失败、整次部署回滚，`FlywayMigrationsTest` 会在 `mvn test` 里拦下这种情况——但 ADR 撞号仍然只能靠人核对。
6. 本策略只放宽流程门禁，不放宽凭据和数据安全：真实 Secret 不得进入 Git；破坏性生产数据操作仍需明确授权；已执行的 migration 仍不可修改。

第一个正式版本发布前必须撤销本节的临时授权，恢复受保护分支、PR CI、CODEOWNERS Review 和发布审批。

## 需求来源：WAO 分享链接

需求和 bug 主要以 **WAO 自身的任务分享链接**下发，形如：

```text
https://wao.wayuwa.cn/api/share/tasks/<taskId>.md?openOnly=true
```

一个链接就是系统里的一个任务，未完成的子任务就是待办条目（`openOnly=true` 已过滤掉做完的）。收到链接后按下面的流程处理。

### 1. 先取原文，别信摘要

用 WebFetch 抓取时，**必须要求逐字返回原文**，明确禁止 summarize / translate / renumber / rephrase，并要求保留每一行 `- [ ]` 与其下的缩进描述、图片链接。抓取工具背后是小模型，默认会把文档压成摘要——摘要会丢掉子任务的具体措辞，而措辞往往就是需求本身。**拿到的内容如果读起来像概述而不是原始清单（比如 `- [ ]` 变成了有序列表、或出现「该任务包含两个主要改进需求」这类句子），就是被摘要了，换更严格的提示词重抓一次。**

### 2. 分门别类，先分析再动手

多个链接一次给过来时，先把所有条目摊平，按性质分类（真 bug / 交互优化 / 数据与展示等），定位根因后再向用户汇报整理结果。**根因和用户的表述可能不一致**——用户描述的是现象，值得先读代码确认，往往一个现象背后是几处代码叠加的结果，也可能几条不同的抱怨其实是同一个病灶。

### 3. 一个链接一个 PR

一个分享链接（= 系统里的一个任务）对应一个 PR，PR 里关闭它的全部子任务，其余按「快速交付策略」执行。这样用户在自己的任务列表里勾掉一条，就正好对应一个已合并的 PR。

### 4. 有想法就直接提

用户明确要求过：**以后每个任务，只要你在使用便利性或体验上有补充想法，就直接给出建议**，
不要等他问。做法是：

- 需求本身该做的先做完，不用建议替代交付。
- 建议写在最终结果摘要里（必要时也写进 PR 描述），一条一句话说清「现在是什么样 → 建议改成什么样 → 为什么」。
- 明显属于同一件事、代价很小又不改变需求边界的补充（比如成功通知顺手加上失败通知），可以直接做掉，
  并在提交正文里说明这是补充项；改变边界或有取舍的，只提建议不擅自做。

### 5. 拿不准就问

子任务的措辞常常是一句话，可能有多种实现方向（例如「这个 title 就不要了」既可能指工具条也可能指提示文字）。**改错方向就是白做**，与其猜，不如在动手前一次性问清楚。

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
- 不直接推送 `main`；一律走「一个需求 / 一个 BUG 一个 PR」，按“快速交付策略”执行
- 不覆盖已有规格；需求变化通过新规格或 ADR 记录
- 不修改已执行的 Flyway migration，数据库变更必须新增 migration

## 体验验收原则

- AI 在宣布功能完成前必须亲自验证真实用户链路，不能只以接口返回、编译通过或页面能打开代替验收。
- 所有可编辑内容都要覆盖草稿防丢、加载中、保存中、已保存、失败重试和空数据状态；多人或定时同步不得覆盖尚未落库的本地输入。
- 弹窗和浮层首次渲染就位，禁止先出现在错误位置后跳动；长标题、窄屏和大量页签必须保持内容可见且有明确的浏览方式。
- 原型数据只能用于明确标注的演示环境；进入真实业务链路的账号、成员、文件和绑定状态必须来自后端持久化数据。
