# 天人一卦图文结合配图：本地验收

日期：2026-10-04。依据用户确认的第一版 Demo、ADR-320 和 `tianren-illustration.md`。

## 环境与范围

使用独立受管 worktree 和 `codex/tianren-illustration` 分支，保护原工作区其他任务的未提交内容。同步至 `origin/main:400b58ae` 后实施；V181 经 Flyway 应用到既有 MySQL，没有清库、修改旧 migration 或替用户开启群通知。

本地 Spring Boot 在环回地址 18086、浏览器前端在 5251；复用既有数据库和本机 ChatGPT 登录的 Codex。专用合成账号通过真实注册、登录和起卦接口完成验收。执行器目录独立，关闭顺手发现，并将小记扫描限定到不存在的专用验收标识，避免处理无关材料。凭据、日志、截图和 PNG 留在本机私有验收目录，不进入 Git。

## 自动检查

- 后端：TianrenVisualServiceTest、TianrenSettingsServiceTest、LocalNoteAiServiceTest、LocalNoteCodexProcessTest、AiAssistantServiceTest、TianrenDailyAutomationServiceTest、TianrenReadingServiceTest、TianrenNotificationServiceTest、AiAssistantConversationServiceTest、FlywayMigrationsTest、ArticleImageServiceTest，合计 139 项通过，零失败/错误。
- 覆盖文字/视觉摘要分离、九章节校验、事务回滚、失效 claim、重复请求、自动入队失败、历史只读、通用聊天续写后隐藏旧图、图片替换失败保留旧图、成员隔离、4000 字边界及首次 CAS 冲突；既有本机会话、通知、小记配图和 migration 唯一性回归通过。
- 前端：9 个天人相关测试文件，45 项通过；包括设置草稿/冲突和失败重试、配图状态/轮询/Object URL、解读正文兼容、分享、通知与首页入口。
- `npm run build`：vue-tsc 与 Vite 构建通过；保留已有大包体提示，没有编译错误。
- 工程一致性与 `git diff --check` 通过；一致性工具保留 5 组历史 ADR 重号警告，本次 ADR-320 和 V181 不撞号、不改写历史。

主要命令：

```powershell
mvn -q -pl wao-server -am test '-Dtest=TianrenVisualServiceTest,TianrenSettingsServiceTest,LocalNoteAiServiceTest,LocalNoteCodexProcessTest,AiAssistantServiceTest,TianrenDailyAutomationServiceTest,TianrenReadingServiceTest,TianrenNotificationServiceTest,AiAssistantConversationServiceTest,FlywayMigrationsTest' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -q -pl wao-server -am test '-Dtest=ArticleImageServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false'
npm run test:unit -- --run src/__tests__/TianrenView.spec.ts src/__tests__/TianrenAiSettingsDialog.spec.ts src/__tests__/TianrenVisualCard.spec.ts src/__tests__/tianrenReadingBody.spec.ts src/__tests__/tianrenVisualApi.spec.ts src/__tests__/TianrenShareView.spec.ts src/__tests__/HomeTianrenGlance.spec.ts src/__tests__/TianrenNotificationStatus.spec.ts src/__tests__/TianrenNotificationSettingsPanel.spec.ts --maxWorkers=2
npm run build
python deploy/scripts/check-engineering-consistency.py --base origin/main
```

## 真实浏览器验收

1. 在 Edge/Playwright 中用合成关注点起卦，真实文字解读保存为完整九章节 Markdown，后台持久配图排队，刷新恢复原任务。
2. 首次内置图片工具在同一线程生成两张 PNG，原校验拒绝多图导致配图失败；文字仍保存。修正可信线程最后落盘图片选择，并补充歧义/越界测试后，通过页面「仅重试配图」得到真实 PNG（2,590,995 字节），文字运行 ID 保持不变。
3. 页面通过鉴权 Blob 显示真实图片与同源的变/不变短句；放大、刷新、重新打开均读取已保存结果，无额外绘图。1440px 与 390px 无横向溢出。
4. 手机设置实测：编辑后关闭/重开恢复本机草稿；另一设备保存制造真实 CAS 冲突；原草稿保留，对比并基于最新版本保存成功；刷新读取已保存提示词，恢复默认保存成功。随后恢复专用账号原设置。
5. 匿名读取设置/视觉状态/原图返回 401；另一账号读取本人的视觉状态/原图返回 404，其设置仍是独立默认版本 0。
6. 真实分享链接匿名读取仍为纯 Markdown，不包含视觉 JSON、图片 attempt 或私有图片地址。未开启群通知；通知行为以现有回归验证。
7. 同步其他任务代码时，Vite 热更新曾出现一次过时模块 URL 读取错误；源码稳定后用全新浏览器上下文复验，页面脚本错误为零。

8. 另一个专用合成账号从未起卦状态执行完整新链路：起卦 → 真实文字保存 → 自动配图入队 → 生成中刷新 → 私有图片显示，最终 `SUCCEEDED`，PNG 为 2,320,156 字节；没有点击图片重试。1440px/390px 无横向溢出，页面脚本错误为零。

## 交付边界

本地验证与 PR 合并是本次交付完成条件。未进行线上部署验收，不等待或轮询 Jenkins，不将合并称为已上线。没有新增公开图片分享、历史批量补图或工作区 MCP/CLI 权限。